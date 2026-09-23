package ago.chat.android.core.domain.bookings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class ConfirmationCountdownTest {
    private val now = OffsetDateTime.parse("2026-09-22T12:00:00Z")

    @Test
    fun `an exact number of hours away rounds to that many hours`() {
        assertEquals(ConfirmationCountdown.HoursRemaining(3), confirmationCountdown("2026-09-22T15:00:00Z", now))
    }

    @Test
    fun `a partial hour rounds up, never down`() {
        assertEquals(ConfirmationCountdown.HoursRemaining(1), confirmationCountdown("2026-09-22T12:20:00Z", now))
    }

    @Test
    fun `a deadline already passed clamps to zero rather than going negative`() {
        assertEquals(ConfirmationCountdown.HoursRemaining(0), confirmationCountdown("2026-09-22T11:00:00Z", now))
    }

    @Test
    fun `a deadline that does not parse is Unknown, never a fabricated zero`() {
        assertEquals(ConfirmationCountdown.Unknown, confirmationCountdown("not-a-timestamp", now))
    }
}
