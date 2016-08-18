package kotlinx.coroutines

import asyncGenerate
import org.junit.Test
import toList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class AsyncGenerateTest {
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
}