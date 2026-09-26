package ago.chat.android.core.domain.bookings

import org.junit.Assert.assertEquals
import org.junit.Test

class BookingsOrderingTest {
    @Test
    fun `the soonest deadline sorts first`() {
        val soon = booking(id = "soon", confirmationDeadline = "2026-09-22T10:00:00Z")
        val later = booking(id = "later", confirmationDeadline = "2026-09-22T12:00:00Z")

        assertEquals(listOf(soon, later), oldestDeadlineFirst(listOf(later, soon)))
    }

    @Test
    fun `a booking whose confirmationDeadline does not parse sorts last rather than throwing`() {
        val broken = booking(id = "broken", confirmationDeadline = "not-a-timestamp")
        val real = booking(id = "real", confirmationDeadline = "2026-09-22T10:00:00Z")

        assertEquals(listOf(real, broken), oldestDeadlineFirst(listOf(broken, real)))
    }

    @Test
    fun `an empty list sorts to an empty list`() {
        assertEquals(emptyList<PendingBooking>(), oldestDeadlineFirst(emptyList()))
    }

    private fun booking(
        id: String,
        confirmationDeadline: String,
    ) = PendingBooking(
        bookingId = id,
        calendarId = "calendar-$id",
        workerId = "worker-$id",
        workerDisplayName = "Мастер $id",
        serviceId = "service-$id",
        serviceName = "Услуга $id",
        customerId = "person-$id",
        startsAt = "2026-09-22T09:00:00Z",
        endsAt = "2026-09-22T09:30:00Z",
        localDate = "2026-09-22",
        confirmationDeadline = confirmationDeadline,
    )
}
