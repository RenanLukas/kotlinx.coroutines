package kotlinx.coroutines

import org.junit.Test
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class AsyncGenerateTest {
    @Test
    fun testEmpty() {
        val asyncSequence = asyncGenerate<String> {}

        assertEquals(listOf(), asyncSequence.toList().get())
    }

    @Test
    fun testSimpleYield() {
        val asyncSequence = asyncGenerate<String> {
            yield("OK")
        }

        assertEquals(listOf("OK"), asyncSequence.toList().get())
    }

    @Test
    fun testSimpleAwait() {
        var effect = false
        fun future() = CompletableFuture.supplyAsync {
            effect = true
            true
        }
        val asyncSequence = asyncGenerate<String> {
            await(future())
            Unit // TODO: workaround for KT-13531
        }

        assertEquals(listOf(), asyncSequence.toList().get())
        assertTrue(effect)
    }

    @Test
    fun testAsyncAndYield() {
        val asyncSequence = asyncGenerate<String> {
            val awaited = await(CompletableFuture.supplyAsync {
                Thread.sleep(100)
                "O"
            }) + "K"
            yield(awaited)
        }

        assertEquals(listOf("OK"), asyncSequence.toList().get())
    }

    @Test
    fun testYieldAndAsync() {
        var effect = false
        val asyncSequence = asyncGenerate<String> {
            yield("OK")
            await(CompletableFuture.supplyAsync {
                effect = true
                ""
            })
            Unit // TODO: workaround for KT-13531
        }

        assertEquals(listOf("OK"), asyncSequence.toList().get())
        assertTrue(effect)
    }

    @Test
    fun testWaitForCompletion() {
        val toAwait = CompletableFuture<String>()
        val asyncSequence = asyncGenerate<String> {
            yield(await(toAwait) + "K")
        }

        assertFalse(asyncSequence.iterator().hasNext().isDone)
        toAwait.complete("O")

        assertEquals(listOf("OK"), asyncSequence.toList().get())
    }

    @Test
    fun testAwaitedFutureCompletedExceptionally() {
        val toAwait = CompletableFuture<String>()
        val aseq = asyncGenerate<String> {
            yield(try {
                await(toAwait)
            } catch (e: RuntimeException) {
                e.message!!
            } + "K")
        }

        assertFalse(aseq.iterator().hasNext().isDone)
        toAwait.completeExceptionally(RuntimeException("O"))

        assertEquals(listOf("OK"), aseq.toList().get())
    }

    @Test
    fun testExceptionInsideCoroutine() {
        val aseq = asyncGenerate<String> {
            val await = await(CompletableFuture.supplyAsync { true })
            if (await) {
                throw IllegalStateException("OK")
            }
            yield(await(CompletableFuture.supplyAsync { "fail" }))
        }

        try {
            aseq.toList().get()
            fail("'get' should've thrown an exception")
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause !is IllegalStateException) {
                throw cause ?: fail("Cause is null")
            }
            assertEquals("OK", cause.message)
        }
    }

    @Test
    fun testComplexAsyncYield() {
        // This test tries all permutations of up to a given number of the following operations:
        //   y - yield
        //   a - await
        //   ya - yield(await(...))

        val options = listOf("y", "ya", "a")
        val maxLength = 6
        for (i in 0..Math.pow(options.size.toDouble(), maxLength.toDouble()).toInt() - 1) {
            doComplexTest(i, options, maxLength)
        }

        // When this test fails, it prints a "number" and a "length" in the assertion message
        // To run that particular failed case, call
        //     doComplexTest(number, options, maxLength, length)
    }

    private fun doComplexTest(number: Int, options: List<String>, maxLength: Int, singleLength: Int = -1) {
        // Form a permutation of [options] with the given [number], where number=0 means "all first items"
        val commands = ArrayList<String>()
        var pow = 1
        for (i in 1..maxLength) {
            commands.add(options[number / pow % options.size])
            pow *= options.size
        }

        fun runCommandsAndCheckResult(commands: List<String>, number: Int) {
            val expected = ArrayList<String>()
            for ((i, c) in commands.withIndex()) {
                when (c) {
                    "y", "ya" -> expected.add("$i")
                    "a" -> { /*skip*/ }
                    else -> fail("Unrecognized instruction: $c")
                }
            }

            fun <T> work(t: T) = async<T> { t }
            val aseq = asyncGenerate<String> {
                for ((i, command) in commands.withIndex()) {
                    when (command) {
                        "y" -> yield("$i")
                        "a" -> await(work(i))
                        "ya" -> yield(await(work("$i")))
                        else -> fail("Unrecognized instruction: $command")
                    }
                }
            }

            assertEquals(
                    expected,
                    aseq.toList().get(),
                    "Test number $number failed. Commands: $commands, length: ${commands.size}"
            )
        }

        if (singleLength <= 0) {
            // Normal operation
            for (i in 1..commands.size) {
                runCommandsAndCheckResult(commands.take(i), number)
            }
        } else {
            // Run single test, for debugging
            runCommandsAndCheckResult(commands.take(singleLength), number)
        }
    }

}