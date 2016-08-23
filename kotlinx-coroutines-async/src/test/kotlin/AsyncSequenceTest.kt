package kotlinx.coroutines

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsyncSequenceTest {
    @Test
    fun testMap() {
        val asyncSequence = asyncSequenceOf(1, 2, 3)
        val result = asyncSequence.map { "$it" }

        assertEquals(listOf("1", "2", "3"), result.toList().get())
    }

    @Test
    fun testFilter() {
        val asyncSequence = asyncSequenceOf(1, 2, 3)
        val result = asyncSequence.filter { it % 2 > 0 }

        assertEquals(listOf(1, 3), result.toList().get())
    }

    @Test
    fun testContains() {
        val asyncSequence = asyncSequenceOf(1, 2, 3)
        assertFalse(asyncSequence.contains(4).get())
        assertTrue(asyncSequence.contains(2).get())
    }
}