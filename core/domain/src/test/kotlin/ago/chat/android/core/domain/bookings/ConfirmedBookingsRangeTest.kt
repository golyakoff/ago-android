package ago.chat.android.core.domain.bookings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class ConfirmedBookingsRangeTest {
    @Test
    fun `the range spans today plus a six-day horizon, both bounds inclusive`() {
        val range = defaultConfirmedBookingsRange(LocalDate.of(2026, 9, 23))

        assertEquals("2026-09-23", range.from)
        assertEquals("2026-09-29", range.to)
        assertEquals(
            listOf("2026-09-23", "2026-09-24", "2026-09-25", "2026-09-26", "2026-09-27", "2026-09-28", "2026-09-29"),
            range.dates,
        )
    }

    @Test
    fun `every strip day carries the 0-is-Sunday weekday convention`() {
        // 2026-09-27 is a Sunday.
        val range = defaultConfirmedBookingsRange(LocalDate.of(2026, 9, 27))

        val strip = confirmedBookingsStrip(range, emptyList())

        assertEquals(0, strip[0].weekday)
        assertEquals(1, strip[1].weekday)
    }

    @Test
    fun `a day with a DayGroup gets a dot, a day with none does not`() {
        val range = defaultConfirmedBookingsRange(LocalDate.of(2026, 9, 23))
        val dayWithBookings = DayGroup(localDate = "2026-09-24", weekday = 4, count = 1, workers = emptyList())

        val strip = confirmedBookingsStrip(range, listOf(dayWithBookings))

        assertFalse(strip.first { it.date == "2026-09-23" }.hasBookings)
        assertEquals(true, strip.first { it.date == "2026-09-24" }.hasBookings)
    }
}
