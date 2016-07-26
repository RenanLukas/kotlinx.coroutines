package kotlinx.coroutines

import java.util.*

private enum class State {
    Ready,
    NotReady,
    Done,
    Failed,
    ManyReady
}

/**
 * A base class to simplify implementing iterators so that implementations only have to implement [computeNext]
 * to implement the iterator, calling [done] when the iteration is complete.
 */
public abstract class AbstractIterator<T>: Iterator<T> {
    private var state = State.NotReady
    private var nextValue: Any? = null

    override fun hasNext(): Boolean {
        require(state != State.Failed)
        while (true) {
            when (state) {
                State.Failed,
                State.Done -> return false
                State.Ready -> return true
                State.ManyReady -> if ((nextValue as Iterator<*>).hasNext()) return true
                State.NotReady -> {}
            }
            tryToComputeNext()
        }
    }

    override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        when (state) {
            State.Ready -> {
                state = State.NotReady
                return nextValue as T
            }
            State.ManyReady -> {
                val iterator = nextValue as Iterator<T>
                return iterator.next()
            }
            else -> throw AssertionError()
        }
    }

    private fun tryToComputeNext() {
        state = State.Failed
        computeNext()
    }

    /**
     * Computes the next item in the iterator.
     *
     * This callback method should call one of these two methods:
     *
     * * [setNext] with the next value of the iteration
     * * [done] to indicate there are no more elements
     *
     * Failure to call either method will result in the iteration terminating with a failed state
     */
    abstract protected fun computeNext(): Unit

    /**
     * Sets the next value in the iteration, called from the [computeNext] function
     */
    protected fun setNext(value: T): Unit {
        nextValue = value
        state = State.Ready
    }

    /**
     * Sets the iterator to retrieve the next values from, called from the [computeNext] function
     */
    protected fun setNextMultiple(iterator: Iterator<T>): Unit {
        nextValue = iterator
        state = State.ManyReady
    }

    /**
     * Sets the state to done so that the iteration terminates.
     */
    protected fun done() {
        state = State.Done
    }
}
