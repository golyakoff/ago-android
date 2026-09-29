package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.core.domain.bookings.PersonBooking
import ago.chat.android.core.domain.bookings.PersonBookingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // `26-269` polish: [ClientDetailUiState.Loaded]'s own new pill/metadata derivations - plain JVM tests
    // over a hand-built [ClientDetailUiState.Loaded], the identical "assert the rule directly, no
    // `ClientDetailViewModel`" restraint every other test in this file already applies.
    @Test
    fun `total bookings count is upcoming plus past`() {
        val loaded =
            loaded(
                upcoming = listOf(booking(id = "u1", startsAt = "2026-10-01T10:00:00Z")),
                past =
                    listOf(
                        booking(id = "p1", startsAt = "2026-01-01T10:00:00Z"),
                        booking(id = "p2", startsAt = "2026-02-01T10:00:00Z"),
                    ),
            )

        assertEquals(3, loaded.totalBookingsCount)
    }

    @Test
    fun `a client with one booking total is not yet returning`() {
        val loaded = loaded(upcoming = listOf(booking(id = "u1", startsAt = "2026-10-01T10:00:00Z")), past = emptyList())

        assertFalse(loaded.isReturningClient)
    }

    @Test
    fun `a client with more than one booking total is returning`() {
        val loaded =
            loaded(
                upcoming = emptyList(),
                past = listOf(booking(id = "p1", startsAt = "2026-01-01T10:00:00Z"), booking(id = "p2", startsAt = "2026-02-01T10:00:00Z")),
            )

        assertTrue(loaded.isReturningClient)
    }

    @Test
    fun `a client with no bookings at all is not returning`() {
        assertFalse(loaded(upcoming = emptyList(), past = emptyList()).isReturningClient)
    }

    @Test
    fun `first and last visit are the earliest and latest past bookings, never an upcoming one`() {
        val earliest = booking(id = "earliest", startsAt = "2026-03-14T10:00:00Z", localDate = "2026-03-14")
        val latest = booking(id = "latest", startsAt = "2026-09-21T10:00:00Z", localDate = "2026-09-21")
        val loaded =
            loaded(
                upcoming = listOf(booking(id = "u1", startsAt = "2026-10-05T10:00:00Z", localDate = "2026-10-05")),
                past = listOf(latest, earliest),
            )

        assertEquals("2026-03-14", loaded.firstVisitLocalDate)
        assertEquals("2026-09-21", loaded.lastVisitLocalDate)
    }

    @Test
    fun `first and last visit are null when no visit has happened yet`() {
        val loaded = loaded(upcoming = listOf(booking(id = "u1", startsAt = "2026-10-01T10:00:00Z")), past = emptyList())

        assertNull(loaded.firstVisitLocalDate)
        assertNull(loaded.lastVisitLocalDate)
    }

    // `26-269` polish (B9), renamed `26-279` (B8): [PersonBooking.asConfirmedBooking] - the booking-detail
    // card's own mapping onto [ago.chat.android.core.domain.bookings.ConfirmedBooking] (past and, since
    // `26-279`, upcoming alike), asserted field-for-field so a future edit cannot silently swap which side
    // (the booking vs. the contact) a field comes from.
    @Test
    fun `a booking maps onto a confirmed booking, with the contact's own live phone`() {
        val booking =
            booking(id = "b1", startsAt = "2026-09-01T10:00:00Z", localDate = "2026-09-01").copy(
                originConversationId = "conv-1",
            )
        val client =
            contact(name = "Анна", phone = "+7 9•• ••• •• 08", masked = true).copy(customerId = "person-1")

        val mapped = booking.asConfirmedBooking(client)

        assertEquals(booking.bookingId, mapped.bookingId)
        assertEquals(booking.calendarId, mapped.calendarId)
        assertEquals(booking.workerId, mapped.workerId)
        assertEquals(booking.workerDisplayName, mapped.workerDisplayName)
        assertEquals(booking.serviceId, mapped.serviceId)
        assertEquals(booking.serviceName, mapped.serviceName)
        assertEquals(booking.startsAt, mapped.startsAt)
        assertEquals(booking.endsAt, mapped.endsAt)
        assertEquals(booking.localDate, mapped.localDate)
        assertEquals(booking.weekday, mapped.weekday)
        assertEquals(booking.originConversationId, mapped.originConversationId)
        // The identity/phone side comes from the *contact*, not the booking's own snapshot - see that
        // function's own doc comment on why a reveal must be reflected here too.
        assertEquals(client.customerId, mapped.customerId)
        assertEquals(client.displayName, mapped.customerDisplayName)
        assertEquals(client.phone, mapped.phone)
        assertEquals(client.masked, mapped.masked)
    }

    // `26-279` (B8): [clientDetailCardTarget] - which navigation slot a booking row's own tap writes to,
    // and whether the card it then opens is `readOnly`. Past keeps `26-269` (B9)'s own read-only card
    // unchanged; upcoming is the new behaviour this item adds in place of the row jumping straight into
    // `RescheduleBookingSheet`.
    @Test
    fun `an upcoming segment targets the upcoming card, not read-only`() {
        val target = clientDetailCardTarget(ClientDetailSegment.Upcoming)

        assertEquals(ClientDetailCardTarget.Upcoming, target)
        assertFalse(target.readOnly)
    }

    @Test
    fun `a past segment targets the past card, read-only`() {
        val target = clientDetailCardTarget(ClientDetailSegment.Past)

        assertEquals(ClientDetailCardTarget.Past, target)
        assertTrue(target.readOnly)
    }

    private fun loaded(
        upcoming: List<PersonBooking>,
        past: List<PersonBooking>,
    ) = ClientDetailUiState.Loaded(
        contact = contact(),
        upcoming = upcoming,
        past = past,
        dialogConversationId = null,
    )

    private fun contact(
        name: String? = "Анна",
        phone: String = "+7***5678",
        masked: Boolean = true,
    ) = Contact(
        customerId = "c1",
        phone = phone,
        masked = masked,
        displayName = name,
        noShowCount = 0,
        phoneVerifiedAt = null,
        phoneConfirmedByOperatorAt = null,
    )

    private fun booking(
        id: String,
        startsAt: String,
        status: PersonBookingStatus = PersonBookingStatus.Booked,
        localDate: String = "2026-09-29",
    ) = PersonBooking(
        bookingId = id,
        calendarId = "cal1",
        workerId = "w1",
        workerDisplayName = "Ирина",
        serviceId = "s1",
        serviceName = "Стрижка",
        startsAt = startsAt,
        endsAt = startsAt,
        localDate = localDate,
        weekday = 2,
        phone = "+7***5678",
        masked = true,
        originConversationId = null,
        status = status,
    )
}
