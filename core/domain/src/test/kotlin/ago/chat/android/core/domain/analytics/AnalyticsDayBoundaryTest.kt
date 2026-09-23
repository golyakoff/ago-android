package ago.chat.android.core.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class AnalyticsDayBoundaryTest {
    private val date = LocalDate.of(2026, 9, 22)

    @Test
    fun `start of day is local midnight with an explicit offset`() {
        assertEquals("2026-09-22T00:00:00Z", startOfDayIso(date, ZoneOffset.UTC))
    }

    @Test
    fun `end of day is the last millisecond of the local day`() {
        assertEquals("2026-09-22T23:59:59.999Z", endOfDayIso(date, ZoneOffset.UTC))
    }

    @Test
    fun `a non-UTC zone still carries its own explicit offset`() {
        val plusThree = ZoneOffset.ofHours(3)
        assertEquals("2026-09-22T00:00:00+03:00", startOfDayIso(date, plusThree))
    }
}
