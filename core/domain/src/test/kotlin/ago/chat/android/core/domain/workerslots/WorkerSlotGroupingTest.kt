package ago.chat.android.core.domain.workerslots

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkerSlotGroupingTest {
    @Test
    fun `slots are grouped by localDate, in first-seen order`() {
        val slots =
            listOf(
                slot(eventId = "e1", localDate = "2026-09-27", weekday = 0),
                slot(eventId = "e2", localDate = "2026-09-26", weekday = 6),
                slot(eventId = "e3", localDate = "2026-09-27", weekday = 0),
            )

        val days = groupSlotsByDay(slots)

        assertEquals(listOf("2026-09-27", "2026-09-26"), days.map { it.localDate })
        assertEquals(listOf("e1", "e3"), days[0].slots.map { it.eventId })
        assertEquals(listOf("e2"), days[1].slots.map { it.eventId })
    }

    @Test
    fun `two slots sharing a bookingId stay two separate rows, never merged`() {
        val slots =
            listOf(
                slot(eventId = "e1", localDate = "2026-09-26", weekday = 6, bookingId = "b1"),
                slot(eventId = "e2", localDate = "2026-09-26", weekday = 6, bookingId = "b1"),
            )

        val days = groupSlotsByDay(slots)

        assertEquals(2, days.single().slots.size)
    }

    @Test
    fun `an empty read groups to no days at all`() {
        assertEquals(emptyList<WorkerSlotDayGroup>(), groupSlotsByDay(emptyList()))
    }

    private fun slot(
        eventId: String,
        localDate: String,
        weekday: Int,
        bookingId: String? = null,
    ) = WorkerSlot(
        eventId = eventId,
        localDate = localDate,
        weekday = weekday,
        startsAt = "${localDate}T09:00:00Z",
        endsAt = "${localDate}T09:30:00Z",
        status = WorkerSlotStatus.Available,
        rawStatus = "Available",
        serviceId = null,
        serviceName = null,
        personId = null,
        phone = null,
        masked = false,
        bookingId = bookingId,
    )
}
