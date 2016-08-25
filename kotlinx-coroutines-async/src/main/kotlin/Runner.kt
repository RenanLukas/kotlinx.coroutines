package kotlinx.channels

import java.util.concurrent.Executor

interface Runner {
    fun run(f: SendHandler, exception: Throwable?)
    fun <T> run(f: ReceiveHandler<T>, arg: T, exception: Throwable?)
}

object SynchronousRunner : Runner {
    override fun run(f: SendHandler, exception: Throwable?) = f(exception)

    override fun <T> run(f: ReceiveHandler<T>, arg: T, exception: Throwable?) = f(arg, exception)
}

fun Executor.asRunner() = object : Runner {
    override fun run(f: SendHandler, exception: Throwable?) = this@asRunner.execute { f(exception) }
    override fun <T> run(f: ReceiveHandler<T>, arg: T, exception: Throwable?) = this@asRunner.execute { f(arg, exception) }
}