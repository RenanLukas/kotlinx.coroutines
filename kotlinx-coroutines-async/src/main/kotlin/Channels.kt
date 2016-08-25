package kotlinx.channels

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

// if [exception] is null, then [data] is a value, otherwise [data] is null
typealias ReceiveHandler<T> = (data: T?, exception: Throwable?) -> Unit
typealias SendHandler = (Throwable?) -> Unit

interface InputChannel<out T> {
    fun receive(handler: ReceiveHandler<T>, token: SubscriptionToken)
}

interface OutputChannel<in T> {
    // This function is needed to support the case when the sender doesn't want to start computing anything before some
    // receiver wants the first computed value
    fun registerSender(handler: SendHandler, token: SubscriptionToken)

    fun send(data: T, handler: SendHandler, token: SubscriptionToken)
}

class SimpleChannel<T>(val runner: Runner = SynchronousRunner) : InputChannel<T>, OutputChannel<T> {
    companion object {
        private val STATE_UPDATER = AtomicReferenceFieldUpdater.newUpdater(SimpleChannel::class.java, Any::class.java, "state")
    }

    private sealed class State {
        // No sender has sent anything and there's no receiver waiting
        object NoValue : State()
        // A sender is waiting for a receiver to come to start computing the first value
        class SenderRegistered(val handler: SendHandler): State()
        // A sender has sent a value and is waiting for a receiver to take it
        class SenderWaiting<out T>(val valueSent: T, val handler: SendHandler): State()
        // Receiver is waiting for a sender to send something
        class ReceiverWaiting<in T>(val handler: ReceiveHandler<T>): State()
    }

    @Volatile
    private var state: Any = State.NoValue

    override fun registerSender(handler: SendHandler, token: SubscriptionToken) {
        val oldState = STATE_UPDATER.getAndAccumulate(this, handler) {
            _state, _continuation ->
            when (_state) {
                State.NoValue -> @Suppress("UNCHECKED_CAST") State.SenderRegistered(_continuation as SendHandler)
                else -> _state
            }
        }

        when (oldState) {
            State.NoValue -> {}
            is State.SenderRegistered -> runner.run(
                    handler,
                    IllegalStateException("Illegal attempt by \"$handler\" to send while another sender \"${oldState.handler}\" is waiting")
            )
            is State.SenderWaiting<*> -> runner.run(
                    handler,
                    IllegalStateException("Illegal attempt by \"$handler\" to send while another sender \"${oldState.handler}\" is waiting")
            )
            is State.ReceiverWaiting<*> -> {
                runner.run(handler, null)
            }
        }
    }

    override fun send(data: T, handler: SendHandler, token: SubscriptionToken) {
        val oldState = STATE_UPDATER.getAndAccumulate(this, State.SenderWaiting(data, handler)) {
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
            is State.SenderWaiting<*> -> runner.run(
                handler,
                IllegalStateException("Illegal attempt by \"$handler\" to send while another sender \"${oldState.handler}\" is waiting")
            )
            is State.ReceiverWaiting<*> -> {
                @Suppress("UNCHECKED_CAST")
                runner.run((oldState as State.ReceiverWaiting<T>).handler, data, null)
                runner.run(handler, null)
            }
        }
    }

    override fun receive(handler: ReceiveHandler<T>, token: SubscriptionToken) {
        val oldState = STATE_UPDATER.getAndAccumulate(this, handler) {
            _state, _continuation ->
            when (_state) {
                State.NoValue,
                is State.SenderRegistered -> @Suppress("UNCHECKED_CAST") State.ReceiverWaiting(_continuation as ReceiveHandler<T>)
                else -> State.NoValue
            }
        }

        when (oldState) {
            State.NoValue -> {}
            is State.ReceiverWaiting<*> -> runner.run(
                    handler,
                    null,
                    IllegalStateException(
                        "Illegal attempt by \"$handler\" to receive when another reader \"${oldState.handler}\" is waiting"
                    )
            )
            is State.SenderRegistered -> runner.run(oldState.handler, null)
            is State.SenderWaiting<*> -> {
                @Suppress("UNCHECKED_CAST")
                runner.run(handler, oldState.valueSent as T, null)
                runner.run(oldState.handler, null)
            }
        }
    }
}

class SelectBuilder {
    private typealias OnHandler<T> = (T) -> Unit

    private val token = SimpleSubscriptionToken()

    private val handlers = hashMapOf<InputChannel<*>, OnHandler<*>>()

    fun <T> on(c: InputChannel<T>, handler: OnHandler<T>) {
        if (c in handlers) throw IllegalStateException("Two handlers registered for the same channel: $c")
        handlers[c] = handler
    }

    fun <T> on(vararg cs: InputChannel<T>, handler: OnHandler<T>) {
        for (c in cs) {
            on(c, handler)
        }
    }

    fun run(continuation: SendHandler) {
        val mappedHandlerCache = hashMapOf<OnHandler<*>, ReceiveHandler<Any?>>()
        for ((channel, handler) in handlers) {
            // This is needed to minimize the number of new lambdas created here,
            // sometimes the same handler is registered fro multiple channels,
            // and we dont' want it to be re-wrapped every time
            val wrapped: ReceiveHandler<Any?> = mappedHandlerCache.getOrPut(handler, {
                fun (data: Any?, exception: Throwable?) {
                    if (exception == null) {
                        @Suppress("UNCHECKED_CAST")
                        (handler as OnHandler<Any?>).invoke(data)
                    }
                    continuation(exception)
                }
            })
            channel.receive(wrapped, token)
        }
    }
}