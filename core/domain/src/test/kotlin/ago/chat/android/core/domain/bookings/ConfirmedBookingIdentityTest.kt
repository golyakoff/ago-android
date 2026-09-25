package ago.chat.android.core.domain.bookings

import org.junit.Assert.assertEquals
import org.junit.Test

/** `26-117`: `docs/backlog/26-117-*.md`'s own hard requirements 1-2 — a name wins outright, a masked
 * phone is the first fallback, and the hex customer id must never surface as an identity at all (there
 * is no arm of [ConfirmedBookingIdentity] that could even carry one). */
class ConfirmedBookingIdentityTest {
    @Test
    fun `a real display name wins outright, phone or not`() {
        val booking = booking(customerDisplayName = "Анна", phone = "+7***5678")

        assertEquals(ConfirmedBookingIdentity.Name("Анна"), confirmedBookingIdentity(booking))
    }

    @Test
    fun `no name falls back to the masked phone`() {
        val booking = booking(customerDisplayName = null, phone = "+7***5678")

        assertEquals(ConfirmedBookingIdentity.MaskedPhone("+7***5678"), confirmedBookingIdentity(booking))
    }

    @Test
    fun `a blank display name is treated as no name, not as an empty label`() {
        val booking = booking(customerDisplayName = "   ", phone = "+7***5678")

        assertEquals(ConfirmedBookingIdentity.MaskedPhone("+7***5678"), confirmedBookingIdentity(booking))
    }

    @Test
    fun `neither a name nor a phone falls back to NoName, never the customer id`() {
        val booking = booking(customerDisplayName = null, phone = "")

        assertEquals(ConfirmedBookingIdentity.NoName, confirmedBookingIdentity(booking))
    }

    private fun booking(
        customerDisplayName: String?,
        phone: String,
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
        masked = true,
    )
}
