
import java.util.concurrent.Executor

interface Runner {
    fun run(f: () -> Unit)
    fun <T> run(f: (T) -> Unit, arg: T)
}

object SynchronousRunner : Runner {
    override fun run(f: () -> Unit) = f()
    override fun <T> run(f: (T) -> Unit, arg: T) = f(arg)
}

fun Executor.asRunner() = object : Runner {
    override fun run(f: () -> Unit) = this@asRunner.execute(f)
    override fun <T> run(f: (T) -> Unit, arg: T) = this@asRunner.execute {
        f(arg)
    }
}