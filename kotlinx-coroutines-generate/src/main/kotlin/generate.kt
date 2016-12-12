package kotlinx.coroutines

/**
 * Creates a Sequence object based on received coroutine [c].
 *
 * Each call of 'yield' suspend function within the coroutine lambda generates
 * next element of resulting sequence.
 */
interface Generator<T> {
    suspend fun yield(value: T)
}

fun <T> generate(block: @Suspend() (Generator<T>.() -> Unit)): Sequence<T> = GeneratedSequence(block)

class GeneratedSequence<T>(private val block: @Suspend() (Generator<T>.() -> Unit)) : Sequence<T> {
    override fun iterator(): Iterator<T> = GeneratedIterator(block)
}

class GeneratedIterator<T>(private val block: @Suspend() (Generator<T>.() -> Unit)) : AbstractIterator<T>(), Generator<T> {
    private lateinit var nextStep: Continuation<Unit>

    private var isInitialized = false

    private fun startCoroutine() {
        coroutineStart(block, this, object : Continuation<Unit> {
            override fun resume(data: Unit) {
                done()
            }

            override fun resumeWithException(exception: Throwable) {
                throw exception
            }
        })
    }

    override fun computeNext() {
        if (!isInitialized) {
            isInitialized = true
            startCoroutine()
        }
        else {
            nextStep.resume(Unit)
        }
    }
    suspend override fun yield(value: T) = suspendWithCurrentContinuation<Unit> { c ->
        setNext(value)
        nextStep = c

        SuspendMarker
    }
}