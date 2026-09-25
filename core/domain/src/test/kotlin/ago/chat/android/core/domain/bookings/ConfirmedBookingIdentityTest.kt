package ago.chat.android.core.domain.bookings

import org.junit.Assert.assertEquals
import org.junit.Test

/** `26-117`: `docs/backlog/26-117-*.md`'s own hard requirements 1-2 — a name wins outright, a masked
 * phone is the first fallback, and the hex customer id must never surface as an identity at all (there
 * is no arm of [ConfirmedBookingIdentity] that could even carry one).
 *
 * `26-125` bug 4: [ConfirmedBooking.masked] `true` is trusted verbatim (the value already looks masked,
 * as the wire is supposed to send it); `false` — `Ago.Calendar.Api`'s own confirmed-bookings gap,
 * `docs/backlog/26-125-*.md` — gets client-masked here instead of surfacing the customer's full number. */
class ConfirmedBookingIdentityTest {
    @Test
    fun `a real display name wins outright, phone or not`() {
        val booking = booking(customerDisplayName = "Анна", phone = "+7***5678", masked = true)

        assertEquals(ConfirmedBookingIdentity.Name("Анна"), confirmedBookingIdentity(booking))
    }

    @Test
    fun `no name falls back to the phone the wire already masked`() {
        val booking = booking(customerDisplayName = null, phone = "+7***5678", masked = true)

        assertEquals(ConfirmedBookingIdentity.MaskedPhone("+7***5678"), confirmedBookingIdentity(booking))
    }

    @Test
    fun `a blank display name is treated as no name, not as an empty label`() {
        val booking = booking(customerDisplayName = "   ", phone = "+7***5678", masked = true)

        assertEquals(ConfirmedBookingIdentity.MaskedPhone("+7***5678"), confirmedBookingIdentity(booking))
    }

    @Test
    fun `neither a name nor a phone falls back to NoName, never the customer id`() {
        val booking = booking(customerDisplayName = null, phone = "", masked = true)

        assertEquals(ConfirmedBookingIdentity.NoName, confirmedBookingIdentity(booking))
    }

    @Test
    fun `an unmasked wire phone is masked client-side, never shown in full`() {
        val booking = booking(customerDisplayName = null, phone = "+79162911129", masked = false)

        // "+79162911129" is 12 characters; the mask keeps the first two ("+7") and last two ("29") and
        // replaces the remaining 8 with bullets - built via `repeat` rather than a hand-counted literal so
        // the assertion cannot silently drift from the production algorithm by one bullet.
        assertEquals(ConfirmedBookingIdentity.MaskedPhone("+7${"•".repeat(8)}29"), confirmedBookingIdentity(booking))
    }

    @Test
    fun `a revealed real phone is still masked in this fallback label`() {
        // `ConfirmedBookingsViewModel.reveal` leaves `masked = false` with the real number once a reveal
        // succeeds - indistinguishable on the wire from a booking that was simply never masked. This
        // label stays masked either way; the detail sheet's own separate "Телефон" row is where a reveal
        // is supposed to show the real number.
        val booking = booking(customerDisplayName = null, phone = "+79162911129", masked = false)

        // "+79162911129" is 12 characters; the mask keeps the first two ("+7") and last two ("29") and
        // replaces the remaining 8 with bullets - built via `repeat` rather than a hand-counted literal so
        // the assertion cannot silently drift from the production algorithm by one bullet.
        assertEquals(ConfirmedBookingIdentity.MaskedPhone("+7${"•".repeat(8)}29"), confirmedBookingIdentity(booking))
    }

    @Test
    fun `a short unmasked phone is masked in full rather than partially exposed`() {
        val booking = booking(customerDisplayName = null, phone = "1234", masked = false)

        assertEquals(ConfirmedBookingIdentity.MaskedPhone("••••"), confirmedBookingIdentity(booking))
    }

    private fun booking(
        customerDisplayName: String?,
        phone: String,
        masked: Boolean,
    ) = ConfirmedBooking(
        bookingId = "b1",
        calendarId = "calendar-1",
        workerId = "w1",
        workerDisplayName = "Ирина Соколова",
        serviceId = "service-1",
        serviceName = "Стрижка",
        customerId = "7c4e18f0-aaaa-bbbb-cccc-000000000000",
        customerDisplayName = customerDisplayName,
        startsAt = "2026-09-24T09:00:00Z",
        endsAt = "2026-09-24T09:30:00Z",
        localDate = "2026-09-24",
        weekday = 4,
        phone = phone,
        masked = masked,
    )
}
