package kotlinx.coroutines

import kotlinx.channels.InputChannel
import kotlinx.channels.OutputChannel
import kotlinx.channels.SimpleChannel
import kotlinx.channels.SynchronousRunner
import org.junit.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals

class SimpleChannelTest {
    val runner = SynchronousRunner

    @Test
    fun testSimpleSynchronous() {
        val c = SimpleChannel<String>(SynchronousRunner)

        fun sender(c: OutputChannel<String>) = async<Unit> {
            c.send("hi")
            println("sent hi")
            c.send("bye")
            println("sent bye")
        }

        fun receiver(c: InputChannel<String>) = async<Unit> {
            assertEquals("hi", c.receive())
            println("received hi")
            assertEquals("bye", c.receive())
            println("received bye")
        }

        CompletableFuture.allOf(sender(c), receiver(c)).get()
    }
}