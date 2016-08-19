import kotlinx.coroutines.generate
import org.junit.Assert
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenerateTest {
    @Test
    fun testSimple() {
        val result = generate<Int> {
            for (i in 1..3) {
                yield(2 * i)
            }
        }

        assertEquals(listOf(2, 4, 6), result.toList())
        // Repeated calls also work
        assertEquals(listOf(2, 4, 6), result.toList())
    }

    @Test
    fun testCallHasNextSeveralTimes() {
        val result = generate<Int> {
            yield(1)
        }

        val iterator = result.iterator()

        assertTrue(iterator.hasNext())
        assertTrue(iterator.hasNext())
        assertTrue(iterator.hasNext())

        assertEquals(1, iterator.next())

        assertFalse(iterator.hasNext())
        assertFalse(iterator.hasNext())
        assertFalse(iterator.hasNext())

        assertTrue(assertFails { iterator.next() } is NoSuchElementException)
    }

    @Test
    fun testManualIteration() {
        val result = generate<Int> {
            yield(1)
            yield(2)
            yield(3)
        }

        val iterator = result.iterator()

        assertTrue(iterator.hasNext())
        assertTrue(iterator.hasNext())
        assertEquals(1, iterator.next())

        assertTrue(iterator.hasNext())
        assertTrue(iterator.hasNext())
        assertEquals(2, iterator.next())

        assertEquals(3, iterator.next())

        assertFalse(iterator.hasNext())
        assertFalse(iterator.hasNext())

        assertTrue(assertFails { iterator.next() } is NoSuchElementException)

        assertEquals(1, result.iterator().next())
    }

    @Test
    fun testEmptySequence() {
        val result = generate<Int> {}
        val iterator = result.iterator()

        assertFalse(iterator.hasNext())
        assertFalse(iterator.hasNext())

        assertTrue(assertFails { iterator.next() } is NoSuchElementException)
    }

    @Test
    fun testLaziness() {
        var sharedVar = -2
        val result = generate<Int> {
            while (true) {
                when (sharedVar) {
                    -1 -> return@generate
                    -2 -> error("Invalid state: -2")
                    else -> yield(sharedVar)
                }
            }
        }

        val iterator = result.iterator()

        sharedVar = 1
        assertTrue(iterator.hasNext())
        assertEquals(1, iterator.next())

        sharedVar = 2
        assertTrue(iterator.hasNext())
        assertEquals(2, iterator.next())

        sharedVar = 3
        assertTrue(iterator.hasNext())
        assertEquals(3, iterator.next())

        sharedVar = -1
        assertFalse(iterator.hasNext())
        assertTrue(assertFails { iterator.next() } is NoSuchElementException)
    }

    @Test
    fun testExceptionInCoroutine() {
        var sharedVar = -2
        val result = generate<Int> {
            while (true) {
                when (sharedVar) {
                    -1 -> return@generate
                    -2 -> error("Invalid state: -2")
                    else -> yield(sharedVar)
                }
            }
        }

        val iterator = result.iterator()

        sharedVar = 1
        assertEquals(1, iterator.next())

        sharedVar = -2
        assertTrue(assertFails { iterator.hasNext() } is IllegalStateException)
    }

    @Test
    fun testParallelIteration() {
        var inc = 0
        val result = generate<Int> {
            for (i in 1..3) {
                inc++
                yield(inc * i)
            }
        }

        assertEquals(listOf(Pair(1, 2), Pair(6, 8), Pair(15, 18)), result.zip(result).toList())
    }

    @Test
    fun testYieldAllIterator() {
        val result = generate<Int> {
            yieldAll(listOf(1, 2, 3).iterator())
        }
        assertEquals(listOf(1, 2, 3), result.toList())
    }

    @Test
    fun testYieldAllSequence() {
        val result = generate<Int> {
            yieldAll(sequenceOf(1, 2, 3))
        }
        assertEquals(listOf(1, 2, 3), result.toList())
    }

    @Test
    fun testYieldAllCollection() {
        val result = generate<Int> {
            yieldAll(listOf(1, 2, 3))
        }
        assertEquals(listOf(1, 2, 3), result.toList())
    }

    @Test
    fun testYieldAllCollectionMixedFirst() {
        val result = generate<Int> {
            yield(0)
            yieldAll(listOf(1, 2, 3))
        }
        assertEquals(listOf(0, 1, 2, 3), result.toList())
    }

    @Test
    fun testYieldAllCollectionMixedLast() {
        val result = generate<Int> {
            yieldAll(listOf(1, 2, 3))
            yield(4)
        }
        assertEquals(listOf(1, 2, 3, 4), result.toList())
    }

    @Test
    fun testYieldAllCollectionMixedBoth() {
        val result = generate<Int> {
            yield(0)
            yieldAll(listOf(1, 2, 3))
            yield(4)
        }
        assertEquals(listOf(0, 1, 2, 3, 4), result.toList())
    }

    @Test
    fun testYieldAllCollectionMixedLong() {
        val result = generate<Int> {
            yield(0)
            yieldAll(listOf(1, 2, 3))
            yield(4)
            yield(5)
            yieldAll(listOf(6))
            yield(7)
            yieldAll(listOf())
            yield(8)
        }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8), result.toList())
    }

    @Test
    fun testYieldAllCollectionOneEmpty() {
        val result = generate<Int> {
            yieldAll(listOf())
        }
        assertEquals(listOf(), result.toList())
    }

    @Test
    fun testYieldAllCollectionManyEmpty() {
        val result = generate<Int> {
            yieldAll(listOf())
            yieldAll(listOf())
            yieldAll(listOf())
        }
        assertEquals(listOf(), result.toList())
    }

    @Test
    fun testYieldAllSideEffects() {
        val effects = arrayListOf<Any>()
        val result = generate<Int> {
            effects.add("a")
            yieldAll(listOf(1, 2))
            effects.add("b")
            yieldAll(listOf())
            effects.add("c")
            yieldAll(listOf(3))
            effects.add("d")
            yield(4)
            effects.add("e")
            yieldAll(listOf())
            effects.add("f")
            yield(5)
        }

        for (res in result) {
            effects.add("(") // marks step start
            effects.add(res)
            effects.add(")") // marks step end
        }
        assertEquals(
                listOf(
                    "a",
                    "(", 1, ")",
                    "(", 2, ")",
                    "b", "c",
                    "(", 3, ")",
                    "d",
                    "(", 4, ")",
                    "e", "f",
                    "(", 5, ")"
                ),
                effects.toList()
        )
    }

    @Test
    fun testDeeplyNestedYieldAll_has_to_be_fast() {
        val timestamp = System.nanoTime()

        var result = sequenceOf<Int>()

        // This creates a chain of iterators that's 1000 levels deep.
        // A naïve implementation of generate() makes iteration very slow in this case
        for (i in 1..1000) {
            val resultValue = result
            result = generate<Int> {
                yieldAll(resultValue)
                yieldAll((1..5).toList())
            }
        }
        val expected = generate<Int> {
            for (i in 1..1000) {
                yieldAll((1..5).toList())
            }
        }
        Assert.assertEquals(expected.toList(), result.toList())

        val timeDelta = System.nanoTime() - timestamp

        assertTrue(timeDelta < 1e9, "Test took too long: slow implementation of generate()?")
    }
}