package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.PersonBooking
import ago.chat.android.core.domain.bookings.PersonBookingStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

/**
 * `26-269`: plain JVM tests for [splitPersonBookings] - the client-detail hub's own Предстоящие/Прошедшие
 * rule, pulled out of [ClientDetailViewModel] so it can be asserted with a fixed [OffsetDateTime] rather
 * than the wall clock, the identical restraint [ContactsUiStateTest] already applies to
 * [filterContacts]/[ago.chat.android.core.domain.bookings.Contact.phoneNeedsAttention].
 */
class ClientDetailUiStateTest {
    private val now = OffsetDateTime.parse("2026-09-29T12:00:00Z")

    @Test
    fun `a booking after now is upcoming`() {
        val booking = booking(id = "b1", startsAt = "2026-10-01T10:00:00Z")

        val (upcoming, past) = splitPersonBookings(listOf(booking), now)

        assertEquals(listOf(booking), upcoming)
        assertEquals(emptyList<PersonBooking>(), past)
    }

    @Test
    fun `a booking before now is past`() {
        val booking = booking(id = "b1", startsAt = "2026-09-01T10:00:00Z")

        val (upcoming, past) = splitPersonBookings(listOf(booking), now)

        assertEquals(emptyList<PersonBooking>(), upcoming)
        assertEquals(listOf(booking), past)
    }

    @Test
    fun `a booking with an unparsable start time sorts into past, never crashing the split`() {
        val booking = booking(id = "b1", startsAt = "not a timestamp")

        val (upcoming, past) = splitPersonBookings(listOf(booking), now)

        assertEquals(emptyList<PersonBooking>(), upcoming)
        assertEquals(listOf(booking), past)
    }

    @Test
    fun `upcoming bookings sort soonest-first`() {
        val soon = booking(id = "soon", startsAt = "2026-10-01T10:00:00Z")
        val later = booking(id = "later", startsAt = "2026-11-01T10:00:00Z")

        val (upcoming, _) = splitPersonBookings(listOf(later, soon), now)

        assertEquals(listOf(soon, later), upcoming)
    }

    @Test
    fun `past bookings sort most-recent-first`() {
        val older = booking(id = "older", startsAt = "2026-01-01T10:00:00Z")
        val recent = booking(id = "recent", startsAt = "2026-09-01T10:00:00Z")

        val (_, past) = splitPersonBookings(listOf(older, recent), now)

        assertEquals(listOf(recent, older), past)
    }

    @Test
    fun `a no-show naturally sorts into past, since it cannot precede its own start time`() {
        val noShow = booking(id = "b1", startsAt = "2026-09-01T10:00:00Z", status = PersonBookingStatus.NoShow)

        val (upcoming, past) = splitPersonBookings(listOf(noShow), now)

        assertEquals(emptyList<PersonBooking>(), upcoming)
        assertEquals(listOf(noShow), past)
    }

    private fun booking(
        id: String,
        startsAt: String,
        status: PersonBookingStatus = PersonBookingStatus.Booked,
    ) = PersonBooking(
        bookingId = id,
        calendarId = "cal1",
        workerId = "w1",
        workerDisplayName = "Ирина",
        serviceId = "s1",
        serviceName = "Стрижка",
        startsAt = startsAt,
        endsAt = startsAt,
        localDate = "2026-09-29",
        weekday = 2,
        phone = "+7***5678",
        masked = true,
        originConversationId = null,
        status = status,
    )
}
