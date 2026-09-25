package ago.chat.android.bookings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-127`: the pure geometry behind the sticky, shrinking month header over the day strip
 * ([stickyMonthHeaderGeometry]) — the sticky/shrink/handoff logic, exercised with plain numbers so the
 * behaviour is pinned without a Compose UI test.
 *
 * The fixture is two months: September (7 chips) then October (4 chips), with a chip stride of `60f`
 * (a 52dp chip + 8dp gap at density 1, but the function is density-agnostic — any positive stride works).
 * September's own span therefore begins at `0f` and October's at `7 * 60 = 420f`.
 */
class StickyMonthHeaderGeometryTest {
    private val stride = 60f
    private val septThenOct = listOf(7, 4)
    private val octoberStartPx = 7 * 60f // 420f

    @Test
    fun `no months means no sticky slot and nothing to shrink it`() {
        val geometry = stickyMonthHeaderGeometry(monthDayCounts = emptyList(), chipStridePx = stride, scrollXPx = 0f)

        assertEquals(-1, geometry.currentIndex)
        assertEquals(Float.POSITIVE_INFINITY, geometry.nextMonthStartPx)
    }

    @Test
    fun `at rest the first month is sticky and its successor is a full span away`() {
        val geometry = stickyMonthHeaderGeometry(septThenOct, stride, scrollXPx = 0f)

        assertEquals(0, geometry.currentIndex)
        // October has not moved: its label is still its full span from the left edge, so nothing truncates.
        assertEquals(octoberStartPx, geometry.nextMonthStartPx)
    }

    @Test
    fun `a negative scroll (overscroll) is clamped, not turned into a wider gap`() {
        val geometry = stickyMonthHeaderGeometry(septThenOct, stride, scrollXPx = -50f)

        assertEquals(0, geometry.currentIndex)
        assertEquals(octoberStartPx, geometry.nextMonthStartPx)
    }

    @Test
    fun `scrolling into September's span keeps it sticky and closes the gap to October by exactly the scroll`() {
        val geometry = stickyMonthHeaderGeometry(septThenOct, stride, scrollXPx = 120f)

        // September still owns the left edge (October's span begins at 420f, not yet reached).
        assertEquals(0, geometry.currentIndex)
        // The cap that shrinks the sticky label is October's remaining distance: 420 - 120 = 300.
        assertEquals(300f, geometry.nextMonthStartPx)
    }

    @Test
    fun `just before the boundary September is nearly fully shrunk`() {
        val geometry = stickyMonthHeaderGeometry(septThenOct, stride, scrollXPx = octoberStartPx - 1f)

        assertEquals(0, geometry.currentIndex)
        assertEquals(1f, geometry.nextMonthStartPx)
    }

    @Test
    fun `at the boundary the handoff completes - October becomes sticky with nothing left to shrink it`() {
        val geometry = stickyMonthHeaderGeometry(septThenOct, stride, scrollXPx = octoberStartPx)

        assertEquals(1, geometry.currentIndex)
        // October is the last month: no successor, so the sticky label never shrinks past here.
        assertEquals(Float.POSITIVE_INFINITY, geometry.nextMonthStartPx)
    }

    @Test
    fun `past the boundary October stays sticky`() {
        val geometry = stickyMonthHeaderGeometry(septThenOct, stride, scrollXPx = octoberStartPx + 200f)

        assertEquals(1, geometry.currentIndex)
        assertEquals(Float.POSITIVE_INFINITY, geometry.nextMonthStartPx)
    }

    @Test
    fun `scrolling back reverses the handoff to the identical geometry - the function is stateless in scroll`() {
        // Forward past the boundary, then back to the same offset the forward pass measured: the result
        // must match frame-for-frame, since the geometry is a pure function of the scroll offset.
        val forward = stickyMonthHeaderGeometry(septThenOct, stride, scrollXPx = 120f)
        stickyMonthHeaderGeometry(septThenOct, stride, scrollXPx = octoberStartPx + 200f)
        val backAgain = stickyMonthHeaderGeometry(septThenOct, stride, scrollXPx = 120f)

        assertEquals(forward, backAgain)
        assertEquals(0, backAgain.currentIndex)
        assertEquals(300f, backAgain.nextMonthStartPx)
    }

    @Test
    fun `a third month hands off in turn - the middle month both shrinks and is shrunk`() {
        // September (7) · October (4) · November (3). October's span begins at 420f, November's at 660f.
        val threeMonths = listOf(7, 4, 3)
        val novemberStartPx = (7 + 4) * 60f // 660f

        // Deep in October's span: October is sticky, November is the one approaching.
        val inOctober = stickyMonthHeaderGeometry(threeMonths, stride, scrollXPx = 500f)
        assertEquals(1, inOctober.currentIndex)
        assertEquals(novemberStartPx - 500f, inOctober.nextMonthStartPx) // 160f

        // Into November's span: November is now sticky and, being last, cannot be shrunk further.
        val inNovember = stickyMonthHeaderGeometry(threeMonths, stride, scrollXPx = novemberStartPx + 10f)
        assertEquals(2, inNovember.currentIndex)
        assertEquals(Float.POSITIVE_INFINITY, inNovember.nextMonthStartPx)
    }

    @Test
    fun `a single month is sticky forever and never shrinks, however far it is scrolled`() {
        val oneMonth = listOf(5)

        val geometry = stickyMonthHeaderGeometry(oneMonth, stride, scrollXPx = 9_999f)

        assertEquals(0, geometry.currentIndex)
        assertEquals(Float.POSITIVE_INFINITY, geometry.nextMonthStartPx)
    }
}
