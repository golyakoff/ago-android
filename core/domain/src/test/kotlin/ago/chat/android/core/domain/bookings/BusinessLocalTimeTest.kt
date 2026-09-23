package ago.chat.android.core.domain.bookings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/**
 * `26-51`'s own Done-when box: "a test proves a device set to a different zone still shows the
 * booking's own local time." [businessLocalTimeOrNull] never reads [java.time.ZoneId.systemDefault] —
 * it formats the offset already attached to the value — so flipping the JVM's own default time zone
 * around the read is the proof: if this function secretly converted onto the device zone the way
 * `ThreadScreen.clockTimeOrNull` deliberately does, changing the default here would change the output.
 */
class BusinessLocalTimeTest {
    @Test
    fun `the rendered time is unaffected by the device's own default zone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val underUtc = businessLocalTimeOrNull("2026-09-24T14:30:00+03:00")

            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val underLosAngeles = businessLocalTimeOrNull("2026-09-24T14:30:00+03:00")

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kamchatka"))
            val underKamchatka = businessLocalTimeOrNull("2026-09-24T14:30:00+03:00")

            assertEquals("14:30", underUtc)
            assertEquals("14:30", underLosAngeles)
            assertEquals("14:30", underKamchatka)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `a Zulu offset renders its own wall-clock time too`() {
        assertEquals("09:00", businessLocalTimeOrNull("2026-09-22T09:00:00Z"))
    }

    @Test
    fun `null for anything that fails to parse, never a crash`() {
        assertNull(businessLocalTimeOrNull("not-a-timestamp"))
    }
}
