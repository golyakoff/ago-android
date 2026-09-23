package ago.chat.android.core.domain.bookings

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmedBookingGroupingTest {
    @Test
    fun `an empty list groups to an empty list`() {
        assertEquals(emptyList<DayGroup>(), groupByDayThenWorker(emptyList()))
    }

    @Test
    fun `rows are grouped day then worker, in the order the server sent them`() {
        val mondayIrina1 = booking(id = "b1", localDate = "2026-09-28", weekday = 1, workerId = "w-irina", workerName = "Ирина Соколова")
        val mondayIrina2 = booking(id = "b2", localDate = "2026-09-28", weekday = 1, workerId = "w-irina", workerName = "Ирина Соколова")
        val mondayPetr = booking(id = "b3", localDate = "2026-09-28", weekday = 1, workerId = "w-petr", workerName = "Пётр Иванов")
        val tuesday = booking(id = "b4", localDate = "2026-09-29", weekday = 2, workerId = "w-irina", workerName = "Ирина Соколова")

        val days = groupByDayThenWorker(listOf(mondayIrina1, mondayPetr, mondayIrina2, tuesday))

        assertEquals(2, days.size)
        val monday = days[0]
        assertEquals("2026-09-28", monday.localDate)
        assertEquals(1, monday.weekday)
        assertEquals(3, monday.count)
        assertEquals(2, monday.workers.size)
        assertEquals("w-irina", monday.workers[0].workerId)
        assertEquals(listOf(mondayIrina1, mondayIrina2), monday.workers[0].rows)
        assertEquals("w-petr", monday.workers[1].workerId)
        assertEquals(listOf(mondayPetr), monday.workers[1].rows)

        val tuesdayGroup = days[1]
        assertEquals("2026-09-29", tuesdayGroup.localDate)
        assertEquals(1, tuesdayGroup.count)
        assertEquals(listOf(tuesday), tuesdayGroup.workers.single().rows)
    }

    private fun booking(
        id: String,
        localDate: String,
        weekday: Int,
        workerId: String,
        workerName: String,
    ) = ConfirmedBooking(
        bookingId = id,
        calendarId = "calendar-1",
        workerId = workerId,
        workerDisplayName = workerName,
        serviceId = "service-1",
        serviceName = "Стрижка",
        customerId = "customer-$id",
        customerDisplayName = null,
        startsAt = "${localDate}T09:00:00Z",
        endsAt = "${localDate}T09:30:00Z",
        localDate = localDate,
        weekday = weekday,
    )
}
