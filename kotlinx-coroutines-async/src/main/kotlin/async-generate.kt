package kotlinx.coroutines

import java.util.*
import java.util.concurrent.CompletableFuture

fun <Element> asyncGenerate(
        coroutine c: AsyncGeneratorController<Element>.() -> Continuation<Unit>) =
        object : AsyncSequence<Element> {
            override fun iterator(): AsyncIterator<Element> {
                val controller = AsyncGeneratorController<Element>()
                controller.start = c(controller)
                return controller
            }
        }

// a controller for an async generator coroutine
// this controller provides both `yield` and `await` suspension points
// `Element` is the yield type of the generator
// TODO : consider which data structures in this code need to be synchronized/volatile and otherwise thread-safe
class AsyncGeneratorController<E> : AsyncIterator<E> {
    enum class State {
        // the initial state
        INITIAL,

        // a state after `next()` invocation
        READY,

        // after `hasNext()` invocation, if a value was stashed
        HAS_VALUE,

        // after `hasNext()` invocation, if reached the end of the generator
        STOPPED,

        // if an unhandled exception has been thrown
        EXCEPTION,

        // temporary state during a step execution, to prevent re-entrancy
        RUNNING
    }

    // must be initialized immediately upon the controller creation
    lateinit var start: Continuation<Unit>

    private var state = State.INITIAL

    private var currentHasNext: CompletableFuture<Boolean>? = null
    private var exception: Throwable? = null

    private var value: E? = null

    private var continuation: Continuation<Unit>? = null

    // This method is available in a generator in a yielding invocation
    // An argument to the parameter `continuation` is provided by the compiler
    suspend fun yield(element: E, continuation: Continuation<Unit>): Unit {
        assert(state == State.RUNNING)
        value = element
        this.continuation = continuation
        state = State.HAS_VALUE

        completeWithCurrentState(currentHasNext)
    }

    // TODO: add sync shortcut for completed tasks
    // TODO: handle cancellation
    // This method is available in an asynchronous coroutine
    suspend fun <R> await(future: CompletableFuture<R>, continuation: Continuation<R>) {
        future.whenComplete { result, exception ->
            if (exception != null)
                continuation.resumeWithException(exception)
            else
                continuation.resume(result)
        }
    }

    // Every `return` expression (or implicit `return` at the end of block) in the coroutine corresponds to an invocation of this method
    // Can be used to the effect similar to `yield break` in C#
    operator fun handleResult(result: Unit, c: Continuation<Nothing>) {
        state = State.STOPPED
        completeWithCurrentState(currentHasNext)
    }

    operator fun handleException(e: Throwable, c: Continuation<Nothing>) {
        state = State.EXCEPTION
        exception = e
        completeWithCurrentState(currentHasNext)
    }

    private fun start() {
        assert(state == State.INITIAL)
        state = State.RUNNING
        start.resume(Unit)
    }

    private fun step() {
        assert(state == State.READY)
        state = State.RUNNING
        val continuation = this.continuation!!
        continuation.resume(Unit)
    }

    private fun fetchValue(): E {
        assert(state == State.HAS_VALUE)
        state = State.READY
        return value as E
    }

    private fun dropValue(): Unit {
        fetchValue() // and ignore the result
    }

    override fun hasNext(): CompletableFuture<Boolean> {
        when (state) {
            State.INITIAL -> {
                assert(currentHasNext == null)

                val result = CompletableFuture<Boolean>()

                // TODO: this runs on the caller thread, but should probably be run on the thread pool
                start()
                currentHasNext = completeWithCurrentState(result)

                return result
            }

            State.READY -> {
                val result = CompletableFuture<Boolean>()

                // TODO: this runs on the caller thread, but should probably be run on the thread pool
                step()
                currentHasNext = completeWithCurrentState(result)

                return result
            }

            State.HAS_VALUE -> {
                dropValue()
                return hasNext()
            }

            State.STOPPED -> return CompletableFuture.completedFuture(false)

            State.RUNNING -> throw IllegalStateException("Illegal re-entrancy")

            else -> throw IllegalStateException("Unexpected state ${state}")
        }
    }

    private fun completeWithCurrentState(future: CompletableFuture<Boolean>?): CompletableFuture<Boolean>? {
        if (future == null) return null
        return when (state) {
            State.HAS_VALUE -> {
                future.complete(true)
                null
            }
            State.STOPPED -> {
                future.complete(false)
                null
            }
            State.EXCEPTION -> {
                future.completeExceptionally(exception)
                null
            }
            else -> return future
        }
    }

    override fun next(): E {
        when (state) {
            State.INITIAL,
            State.READY -> {
                // this branch means a client invoked the `next()` method without preceding call to `hasNext()`
                hasNext().get() // `get()` means wait for completion
                return next()
            }

            State.HAS_VALUE -> return fetchValue()

            State.STOPPED -> throw NoSuchElementException("The sequence has ended")

            State.RUNNING -> throw IllegalStateException("Illegal re-entrancy")

            else -> throw IllegalStateException("Unexpected state ${state}")
        }
    }
}
