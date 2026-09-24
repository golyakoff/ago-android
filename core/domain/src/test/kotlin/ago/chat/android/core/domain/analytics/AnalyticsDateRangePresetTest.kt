package ago.chat.android.core.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * `26-71`: the three presets against a fixed [ZonedDateTime] rather than the system clock — a pure
 * function of its argument, the same reason [AnalyticsDateRangePreset]'s own doc comment gives.
 */
class AnalyticsDateRangePresetTest {
    private val now = ZonedDateTime.of(2026, 9, 24, 15, 30, 0, 0, ZoneOffset.UTC)

    @Test
    fun `the current calendar month runs from its first instant to now`() {
        val preset = currentCalendarMonth(now)

        assertEquals("2026-09-01T00:00:00Z", preset.from)
        assertEquals("2026-09-24T15:30:00Z", preset.to)
    }

    @Test
    fun `the previous calendar month runs from its first instant to this month's first instant, exclusive`() {
        val preset = previousCalendarMonth(now)

        assertEquals("2026-08-01T00:00:00Z", preset.from)
        assertEquals("2026-09-01T00:00:00Z", preset.to)
    }

    @Test
    fun `a previous calendar month spanning a year boundary rolls the year back too`() {
        val january = ZonedDateTime.of(2026, 1, 15, 9, 0, 0, 0, ZoneOffset.UTC)

        val preset = previousCalendarMonth(january)

        assertEquals("2025-12-01T00:00:00Z", preset.from)
        assertEquals("2026-01-01T00:00:00Z", preset.to)
    }

    @Test
    fun `the last 30 days runs from thirty days before now to now`() {
        val preset = last30Days(now)

        assertEquals("2026-08-25T15:30:00Z", preset.from)
        assertEquals("2026-09-24T15:30:00Z", preset.to)
    }

    @Test
    fun `every preset carries an explicit offset, never a bare local timestamp`() {
        val zoned = ZonedDateTime.of(2026, 9, 24, 15, 30, 0, 0, ZoneOffset.ofHours(3))

        val preset = currentCalendarMonth(zoned)

        assertEquals("2026-09-01T00:00:00+03:00", preset.from)
        assertEquals("2026-09-24T15:30:00+03:00", preset.to)
    }
}
