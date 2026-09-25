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

    @Test
    fun `a strip entirely inside one month produces exactly one label spanning every day`() {
        // 2026-09-23 .. 2026-09-29 - the whole default range, one month, one label.
        val range = defaultConfirmedBookingsRange(LocalDate.of(2026, 9, 23))
        val strip = confirmedBookingsStrip(range, emptyList())

        val labels = confirmedBookingsMonthLabels(strip)

        assertEquals(listOf(ConfirmedBookingsMonthLabel(monthValue = 9, year = 2026, dayCount = 7)), labels)
    }

    @Test
    fun `a strip crossing a month boundary produces one label per month, each with its own day count and year`() {
        // 2026-09-25 .. 2026-10-01 - six September days (25-30), one October day.
        val range = defaultConfirmedBookingsRange(LocalDate.of(2026, 9, 25))
        val strip = confirmedBookingsStrip(range, emptyList())

        val labels = confirmedBookingsMonthLabels(strip)

        assertEquals(
            listOf(
                ConfirmedBookingsMonthLabel(monthValue = 9, year = 2026, dayCount = 6),
                ConfirmedBookingsMonthLabel(monthValue = 10, year = 2026, dayCount = 1),
            ),
            labels,
        )
        // The two counts still add up to the whole strip - no day silently dropped or double-counted at
        // the boundary.
        assertEquals(strip.size, labels.sumOf { it.dayCount })
    }

    @Test
    fun `a strip crossing a year boundary keeps December and January as two labels with two different years`() {
        // 2026-12-29 .. 2027-01-04 - three December days, four January days, two different years.
        val range = defaultConfirmedBookingsRange(LocalDate.of(2026, 12, 29))
        val strip = confirmedBookingsStrip(range, emptyList())

        val labels = confirmedBookingsMonthLabels(strip)

        assertEquals(
            listOf(
                ConfirmedBookingsMonthLabel(monthValue = 12, year = 2026, dayCount = 3),
                ConfirmedBookingsMonthLabel(monthValue = 1, year = 2027, dayCount = 4),
            ),
            labels,
        )
    }
}
