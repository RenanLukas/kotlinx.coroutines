import kotlinx.coroutines.async
import java.util.*
import java.util.concurrent.CompletableFuture

// === Async generators ===
interface AsyncIterator<T> {
    fun hasNext(): CompletableFuture<Boolean>
    fun next(): T
}

interface AsyncSequence<T> {
    fun iterator(): AsyncIterator<T>
}

fun <Element> asyncGenerate(
        coroutine c: AsyncGeneratorController<Element>.() -> Continuation<Unit>) =
        object : AsyncSequence<Element> {
            override fun iterator(): AsyncIterator<Element> {
                val controller = AsyncGeneratorController<Element>()
                controller.start = c(controller)
                return AsyncIteratorTask(controller)
            }
        }

// a controller for an async generator coroutine
// this controller provides both `yield` and `await` suspension points
// `Element` is the yield type of the generator
class AsyncGeneratorController<Element> {
    enum class State {
        // the initial state
        INITIAL,

        // a state after `next()` invocation
        READY,

        // after `hasNext()` invocation, if a value was stashed
        HAS_VALUE,

        // after `hasNext()` invocation, if reached the end of the generator
        STOPPED,

        // temporary state during a step execution, to prevent re-entrancy
        RUNNING
    }

    // must be initialized immediately upon the controller creation
    lateinit var start: Continuation<Unit>

    internal var state = State.INITIAL

    private val maybeValue = ArrayList<Element>()

    private var maybeContinuation = ArrayList<Continuation<Unit>>()

    // This method is available in a generator in a yielding invocation
    // An argument to the parameter `continuation` is provided by the compiler
    suspend fun yield(element: Element, continuation: Continuation<Unit>): Unit {
        assert(state == State.RUNNING)
        maybeValue.push(element)
        maybeContinuation.push(continuation)
        state = State.HAS_VALUE
    }

    // TODO: add sync shortcut for completed tasks
    // TODO: handle cancellation
    // This method is available in an asynchronous coroutine
    suspend fun <Result> await(future: CompletableFuture<Result>, continuation: Continuation<Result>): Unit {
        future.whenComplete { result, exception ->
            if (exception != null)
                continuation.resumeWithException(exception)
            else
                continuation.resume(result)
        }
    }

    // Every `return` expression (or implicit `return` at the end of block) in the coroutine corresponds to an invocation of this method
    // Can be used to the effect similar to `yield break` in C#
    operator fun handleResult(result: Unit, c: Continuation<Nothing>): Unit {
        state = State.STOPPED
    }

    fun start() {
        assert(state == State.INITIAL)
        state = State.RUNNING
        start.resume(Unit)
        assert(state == State.HAS_VALUE || state == State.STOPPED)
    }

    fun step() {
        assert(state == State.READY)
        state = State.RUNNING
        val continuation = maybeContinuation.pop()
        continuation.resume(Unit)
        assert(state == State.HAS_VALUE || state == State.STOPPED)
    }

    fun fetchValue(): Element {
        assert(state == State.HAS_VALUE)
        assert(maybeValue.size == 1)
        state = State.READY
        return maybeValue.pop()
    }

    fun dropValue(): Unit {
        fetchValue() // and ignore the result
    }
}

class AsyncIteratorTask<T>(val controller: AsyncGeneratorController<T>) : AsyncIterator<T> {
    override fun hasNext(): CompletableFuture<Boolean> {
        when (controller.state) {
            AsyncGeneratorController.State.INITIAL -> {
                return CompletableFuture.supplyAsync {
                    controller.start()
                    controller.state == AsyncGeneratorController.State.HAS_VALUE
                }
            }

            AsyncGeneratorController.State.READY -> {
                return CompletableFuture.supplyAsync {
                    controller.step()
                    assert(controller.state == AsyncGeneratorController.State.HAS_VALUE ||
                            controller.state == AsyncGeneratorController.State.STOPPED)
                    controller.state == AsyncGeneratorController.State.HAS_VALUE
                }
            }

            AsyncGeneratorController.State.HAS_VALUE -> {
                controller.dropValue()
                return hasNext()
            }

            AsyncGeneratorController.State.STOPPED -> return CompletableFuture.completedFuture(false)

            AsyncGeneratorController.State.RUNNING -> throw IllegalStateException("Illegal re-entrancy")

            else -> throw IllegalStateException("Unexpected state ${controller.state}")
        }
    }

    override fun next(): T {
        when (controller.state) {
            AsyncGeneratorController.State.INITIAL,
            AsyncGeneratorController.State.READY -> {
                // this branch means a client invoked the `next()` method without preceding call to `hasNext()`
                hasNext().get() // `get()` means wait for completion
                return next()
            }

            AsyncGeneratorController.State.HAS_VALUE -> return controller.fetchValue()

            AsyncGeneratorController.State.STOPPED -> throw NoSuchElementException("The sequence has ended")

            AsyncGeneratorController.State.RUNNING -> throw IllegalStateException("Illegal re-entrancy")

            else -> throw IllegalStateException("Unexpected state ${controller.state}")
        }
    }
}

private fun <T> ArrayList<T>.push(t: T) = add(t)
private fun <T> ArrayList<T>.pop() = removeAt(lastIndex)

fun <T> work(t: T) = async<T> {
    t
}

fun <T> AsyncSequence<T>.forEach(body: (T) -> Unit): CompletableFuture<Unit> {
    return async<Unit> {
        val iterator = iterator()
        while (await(iterator.hasNext())) {
            body(iterator.next())
        }
    }
}

fun <T> AsyncSequence<T>.toList(expectedSize: Int = -1): CompletableFuture<List<T>> {
    return async {
        val result = if (expectedSize >= 0) ArrayList<T>(expectedSize) else ArrayList()
        val iterator = iterator()
        while (await(iterator.hasNext())) {
            result += iterator.next()
        }
        result.trimToSize()
        result
    }
//    forEach {
//        result += it
//    }.get()
//    result.trimToSize()
//    return result
}

fun main(args: Array<String>) {
    println("At least this")

    val ag = asyncGenerate<String> {
        yield("Start")
        val first = await(work(1))
        val second = await(work("second: $first"))
        yield("Continue")
        yield(second)
    }

    ag.forEach {
        println(it)
    }.get()
}