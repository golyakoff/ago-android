package ago.chat.android.core.domain.workerslots

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WorkerSlotsRangeTest {
    @Test
    fun `the range spans today plus a fourteen-day horizon, both bounds inclusive`() {
        val range = defaultWorkerSlotsRange(LocalDate.of(2026, 9, 26))

        assertEquals("2026-09-26", range.from)
        assertEquals("2026-10-10", range.to)
    }
}
