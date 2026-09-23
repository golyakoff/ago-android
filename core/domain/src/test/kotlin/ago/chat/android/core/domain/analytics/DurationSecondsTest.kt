package ago.chat.android.core.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationSecondsTest {
    @Test
    fun `under a minute is bare seconds`() {
        assertEquals("42s", formatDurationSeconds(42.0))
    }

    @Test
    fun `an exact number of minutes drops the seconds`() {
        assertEquals("5m", formatDurationSeconds(300.0))
    }

    @Test
    fun `a partial minute keeps both`() {
        assertEquals("5m 4s", formatDurationSeconds(304.0))
    }

    @Test
    fun `an exact number of hours drops the minutes`() {
        assertEquals("2h", formatDurationSeconds(7200.0))
    }

    @Test
    fun `a partial hour keeps both`() {
        assertEquals("2h 5m", formatDurationSeconds(7500.0))
    }

    @Test
    fun `a fractional second rounds to the nearest whole second`() {
        assertEquals("1s", formatDurationSeconds(0.6))
    }

    @Test
    fun `never negative`() {
        assertEquals("0s", formatDurationSeconds(-5.0))
    }
}
