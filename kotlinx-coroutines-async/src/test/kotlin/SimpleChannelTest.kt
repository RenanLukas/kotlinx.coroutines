package kotlinx.coroutines

import kotlinx.channels.*
import org.junit.Test
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ForkJoinPool
import kotlin.test.assertEquals

class SimpleChannelTest {
    val runner = ForkJoinPool.commonPool().asRunner()

    private fun doTestSimple(runner: Runner) {
        val c = SimpleChannel<String>(runner)

        fun sender(c: OutputChannel<String>) = async<Unit> {
            c.send("hi")
            c.send("bye")
        }

        fun receiver(c: InputChannel<String>) = async<Unit> {
            assertEquals("hi", c.receive())
            assertEquals("bye", c.receive())
        }

        CompletableFuture.allOf(sender(c), receiver(c)).get()
    }

    @Test
    fun testSimpleSynchronous() {
        doTestSimple(SynchronousRunner)
    }

    @Test
    fun testSimpleRunner() {
        doTestSimple(runner)
    }

    @Test
    fun testSelect() {
        val maxTimeout = 100
        val repeatSend = 100
        fun sender(c: OutputChannel<String>, name: String) = runAsync<Unit> {
            val random = Random()
            for (i in 1..repeatSend) {
                Thread.sleep(random.nextInt(maxTimeout).toLong())
                c.send(name)
            }
        }

        val alice = SimpleChannel<String>(runner)
        sender(alice, "Alice")
        val bob = SimpleChannel<String>(runner)
        sender(bob, "Bob")


        runAsync<Unit> {
            val received = Collections.synchronizedList(arrayListOf<String>())
            while (received.size < repeatSend * 2) {
                select {
                    on(alice) {
                        assertEquals("Alice", it)
                        received += it
                    }
                    on(bob) {
                        assertEquals("Bob", it)
                        received += it
                    }
                }
            }
            assertEquals(setOf("Alice", "Bob"), received.toSet())
        }.get()
    }
}

fun <T> runAsync(coroutine c: FutureController<T>.() -> Continuation<Unit>): CompletableFuture<T> {
    val controller = FutureController<T>(null)
    val continuation = c(controller)
    ForkJoinPool.commonPool().submit {
        continuation.resume(Unit)
    }
    return controller.future
}