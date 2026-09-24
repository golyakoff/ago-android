package ago.chat.android.devices

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-19`: [QuietHoursSettings.suppressesAt] proven as a plain function over explicit minute-of-day
 * instants — never through a real clock or a coroutine scheduler advancing simulated time. This
 * session's own established lesson: `advanceUntilIdle()` against a periodic/polling coroutine can spin
 * forever when code re-schedules itself in a loop with no bound, where asserting a pure function directly
 * over chosen instants cannot.
 */
class QuietHoursTest {
    @Test
    fun `disabled never suppresses, regardless of the range or the time`() {
        val settings = QuietHoursSettings(enabled = false, startMinuteOfDay = 0, endMinuteOfDay = 1439)

        assertFalse(settings.suppressesAt(nowMinuteOfDay = 0))
        assertFalse(settings.suppressesAt(nowMinuteOfDay = 720))
        assertFalse(settings.suppressesAt(nowMinuteOfDay = 1439))
    }

    @Test
    fun `an ordinary same-day range - 13-00 to 14-00 - suppresses strictly inside it`() {
        val settings = QuietHoursSettings(enabled = true, startMinuteOfDay = 13 * 60, endMinuteOfDay = 14 * 60)

        assertTrue(settings.suppressesAt(nowMinuteOfDay = 13 * 60))
        assertTrue(settings.suppressesAt(nowMinuteOfDay = 13 * 60 + 30))
        assertFalse("the end minute itself is exclusive - a half-open range", settings.suppressesAt(nowMinuteOfDay = 14 * 60))
        assertFalse(settings.suppressesAt(nowMinuteOfDay = 12 * 60 + 59))
    }

    @Test
    fun `a range crossing midnight - 22-00 to 07-00 - suppresses on both sides of midnight`() {
        val settings = QuietHoursSettings(enabled = true, startMinuteOfDay = 22 * 60, endMinuteOfDay = 7 * 60)

        assertTrue("well after start, before midnight", settings.suppressesAt(nowMinuteOfDay = 23 * 60))
        assertTrue("exactly at start", settings.suppressesAt(nowMinuteOfDay = 22 * 60))
        assertTrue("just after midnight", settings.suppressesAt(nowMinuteOfDay = 0))
        assertTrue("just before end", settings.suppressesAt(nowMinuteOfDay = 6 * 60 + 59))
        assertFalse("exactly at end - the half-open range's own exclusive edge", settings.suppressesAt(nowMinuteOfDay = 7 * 60))
        assertFalse("broad daylight, well outside either side", settings.suppressesAt(nowMinuteOfDay = 12 * 60))
    }

    @Test
    fun `a zero-length range - start equals end - suppresses nothing`() {
        val settings = QuietHoursSettings(enabled = true, startMinuteOfDay = 9 * 60, endMinuteOfDay = 9 * 60)

        assertFalse(settings.suppressesAt(nowMinuteOfDay = 9 * 60))
        assertFalse(settings.suppressesAt(nowMinuteOfDay = 0))
        assertFalse(settings.suppressesAt(nowMinuteOfDay = 1439))
    }

    @Test
    fun `the documented default - 22-00 to 08-00 - is a midnight-crossing range`() {
        val settings = QuietHoursSettings(enabled = true)

        assertTrue(settings.suppressesAt(nowMinuteOfDay = 23 * 60))
        assertTrue(settings.suppressesAt(nowMinuteOfDay = 0))
        assertFalse(settings.suppressesAt(nowMinuteOfDay = 8 * 60))
        assertFalse(settings.suppressesAt(nowMinuteOfDay = 12 * 60))
    }
}
