package ago.chat.android.core.domain.conversations

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class ElapsedLabelTest {
    private val now = OffsetDateTime.parse("2026-09-22T12:00:00Z")

    @Test
    fun `under an hour is minutes`() {
        assertEquals(ElapsedLabel.Minutes(14), elapsedSince("2026-09-22T11:46:00Z", now))
    }

    @Test
    fun `under a day is hours`() {
        assertEquals(ElapsedLabel.Hours(3), elapsedSince("2026-09-22T09:00:00Z", now))
    }

    @Test
    fun `a day or more is days`() {
        assertEquals(ElapsedLabel.Days(2), elapsedSince("2026-09-20T12:00:00Z", now))
    }

    @Test
    fun `a createdAt that does not parse is Unknown, never a fabricated zero`() {
        assertEquals(ElapsedLabel.Unknown, elapsedSince("not-a-timestamp", now))
    }

    @Test
    fun `a createdAt in the future - clock skew - clamps to zero rather than going negative`() {
        assertEquals(ElapsedLabel.Minutes(0), elapsedSince("2026-09-22T12:05:00Z", now))
    }
}
