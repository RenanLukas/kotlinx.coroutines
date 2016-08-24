package kotlinx.channels

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

interface InputChannel<out T> {
    fun receive(continuation: (T) -> Unit, exceptionalContinuation: (Throwable) -> Unit)
}

interface OutputChannel<in T> {
    // This function is needed to support the case when the sender doesn't want to start computing anything before some
    // receiver wants the first computed value
    fun registerSender(continuation: () -> Unit, exceptionalContinuation: (Throwable) -> Unit)

    fun send(data: T, continuation: () -> Unit, exceptionalContinuation: (Throwable) -> Unit)
}

class SimpleChannel<T>(val runner: Runner = SynchronousRunner) : InputChannel<T>, OutputChannel<T> {
    companion object {
        private val STATE_UPDATER = AtomicReferenceFieldUpdater.newUpdater(SimpleChannel::class.java, Any::class.java, "state")
    }

    private sealed class State {
        // No sender has sent anything and there's no receiver waiting
        object NoValue : State()
        // A sender is waiting for a receiver to come to start computing the first value
        class SenderRegistered(val senderContinuation: () -> Unit): State()
        // A sender has sent a value and is waiting for a receiver to take it
        class SenderWaiting<out T>(val valueSent: T, val senderContinuation: () -> Unit): State()
        // Receiver is waiting for a sender to send something
        class ReceiverWaiting<in T>(val receiverContinuation: (T) -> Unit): State()
    }

    @Volatile
    private var state: Any = State.NoValue

    override fun registerSender(continuation: () -> Unit, exceptionalContinuation: (Throwable) -> Unit) {
        val oldState = STATE_UPDATER.getAndAccumulate(this, continuation) {
            _state, _continuation ->
            when (_state) {
                State.NoValue -> @Suppress("UNCHECKED_CAST") State.SenderRegistered(_continuation as () -> Unit)
                else -> _state
            }
        }

        when (oldState) {
            State.NoValue -> {}
            is State.SenderRegistered -> runner.run(
                    exceptionalContinuation,
                    IllegalStateException("Illegal attempt by \"$continuation\" to send while another sender \"${oldState.senderContinuation}\" is waiting")
            )
            is State.SenderWaiting<*> -> runner.run(
                    exceptionalContinuation,
                    IllegalStateException("Illegal attempt by \"$continuation\" to send while another sender \"${oldState.senderContinuation}\" is waiting")
            )
            is State.ReceiverWaiting<*> -> {
                runner.run(continuation)
            }
        }
    }

    override fun send(data: T, continuation: () -> Unit, exceptionalContinuation: (Throwable) -> Unit) {
        val oldState = STATE_UPDATER.getAndAccumulate(this, State.SenderWaiting(data, continuation)) {
            _state, _senderWaiting ->
            when (_state as State) {
                is State.NoValue,
                is State.SenderRegistered -> _senderWaiting
                is State.SenderWaiting<*> -> _state
                is State.ReceiverWaiting<*> -> State.NoValue
            }
        }

        when (oldState) {
            State.NoValue,
            is State.SenderRegistered -> {}
            is State.SenderWaiting<*> -> exceptionalContinuation(
                IllegalStateException("Illegal attempt by \"$continuation\" to send while another sender \"${oldState.senderContinuation}\" is waiting")
            )
            is State.ReceiverWaiting<*> -> {
                @Suppress("UNCHECKED_CAST")
                runner.run((oldState as State.ReceiverWaiting<T>).receiverContinuation, data)
                runner.run(continuation)
            }
        }
    }

    override fun receive(continuation: (T) -> Unit, exceptionalContinuation: (Throwable) -> Unit) {
        val oldState = STATE_UPDATER.getAndAccumulate(this, continuation) {
            _state, _continuation ->
            when (_state) {
                State.NoValue,
                is State.SenderRegistered -> @Suppress("UNCHECKED_CAST") State.ReceiverWaiting(_continuation as (T) -> Unit)
                else -> State.NoValue
            }
        }

        when (oldState) {
            State.NoValue -> {}
            is State.ReceiverWaiting<*> -> runner.run(
                    exceptionalContinuation,
                    IllegalStateException(
                        "Illegal attempt by \"$continuation\" to receive when another reader \"${oldState.receiverContinuation}\" is waiting"
                    )
            )
            is State.SenderRegistered -> runner.run(oldState.senderContinuation)
            is State.SenderWaiting<*> -> {
                @Suppress("UNCHECKED_CAST")
                runner.run(continuation, oldState.valueSent as T)
                runner.run(oldState.senderContinuation)
            }
        }
    }
}

class SelectBuilder {
    private val handlers = hashMapOf<InputChannel<*>, Function1<*, Unit>>()

    fun <T> on(c: InputChannel<T>, handler: (T) -> Unit) {
        if (c in handlers) throw IllegalStateException("Two handlers registered for the same channel: $c")
        handlers[c] = handler
    }

    fun <T> on(vararg cs: InputChannel<T>, handler: (T) -> Unit) {
        for (c in cs) {
            on(c, handler)
        }
    }

    fun run(continuation: () -> Unit, exceptionalContinuation: (Throwable) -> Unit) {
        for ((channel, handler) in handlers) {
            channel.receive(
                    {
                        @Suppress("UNCHECKED_CAST")
                        (handler as (Any?) -> Unit).invoke(it)
                        continuation()
                    },
                    exceptionalContinuation
            )
        }
    }
}