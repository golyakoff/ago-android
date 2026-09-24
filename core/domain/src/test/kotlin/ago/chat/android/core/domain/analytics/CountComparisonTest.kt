package ago.chat.android.core.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-70`: the branch that matters is "a zero previous window has no percentage" — asserted here rather
 * than by reading a rendered sentence, which is the whole reason this ported as a computation instead
 * of as the console's own string-returning `formatCountComparison` ([CountComparison]'s doc comment).
 */
class CountComparisonTest {
    @Test
    fun `a rise carries a positive delta and a positive percentage`() {
        val comparison = compareCounts(current = 15, previous = 12)

        assertEquals(12, comparison.previous)
        assertEquals(3, comparison.delta)
        assertEquals(25.0, comparison.relativePercent!!, 0.0001)
        assertFalse(comparison.isUnchanged)
    }

    @Test
    fun `a fall carries a negative delta and a negative percentage, an ordinary outcome`() {
        val comparison = compareCounts(current = 6, previous = 12)

        assertEquals(-6, comparison.delta)
        assertEquals(-50.0, comparison.relativePercent!!, 0.0001)
    }

    @Test
    fun `a zero previous window has no percentage at all, never zero and never infinity`() {
        val comparison = compareCounts(current = 4, previous = 0)

        assertEquals(0, comparison.previous)
        assertEquals(4, comparison.delta)
        assertNull(comparison.relativePercent)
    }

    @Test
    fun `two empty windows are unchanged, not a hundred-percent anything`() {
        val comparison = compareCounts(current = 0, previous = 0)

        assertEquals(0, comparison.delta)
        assertNull(comparison.relativePercent)
        assertTrue(comparison.isUnchanged)
    }

    @Test
    fun `an unchanged non-empty window reports zero change against a real previous count`() {
        val comparison = compareCounts(current = 9, previous = 9)

        assertEquals(9, comparison.previous)
        assertTrue(comparison.isUnchanged)
        assertEquals(0.0, comparison.relativePercent!!, 0.0001)
    }
}
