package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.PersonBooking
import ago.chat.android.core.domain.bookings.PersonBookingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-306` (author screenshot, СЕЙЧАС vs НАДО СДЕЛАТЬ): pins [clientBookingServiceAndMaster] — the
 * plain-Kotlin half of the client-detail booking row's own line-1 mapping — directly on a JVM, the way
 * [StickyMonthHeaderGeometryTest] already pins pure row/layout logic in this same package without a
 * Compose UI test. [ClientBookingRow] itself only joins this pair with a space and bolds the second half
 * ([clientBookingServiceAndMasterText]'s own doc comment), so this is the one place the actual field
 * mapping — which fact lands first, which one gets the bold half — is asserted.
 */
class ClientBookingRowTest {
    @Test
    fun `service name leads, master name is the bold half`() {
        val booking = booking(serviceName = "Примерка", workerDisplayName = "Алёна Матерн")

        val (service, master) = clientBookingServiceAndMaster(booking)

        assertEquals("Примерка", service)
        assertEquals("Алёна Матерн", master)
    }

    @Test
    fun `a missing service name falls back to the row's own dash, never a blank`() {
        val booking = booking(serviceName = null, workerDisplayName = "Ирина Соколова")

        val (service, master) = clientBookingServiceAndMaster(booking)

        assertEquals("—", service)
        assertEquals("Ирина Соколова", master)
    }

    private fun booking(
        serviceName: String?,
        workerDisplayName: String,
    ) = PersonBooking(
        bookingId = "b1",
        calendarId = "cal1",
        workerId = "w1",
        workerDisplayName = workerDisplayName,
        serviceId = "s1",
        serviceName = serviceName,
        startsAt = "2026-10-06T10:00:00+03:00",
        endsAt = "2026-10-06T11:30:00+03:00",
        localDate = "2026-10-06",
        weekday = 2,
        phone = "+7***5678",
        masked = true,
        originConversationId = null,
        status = PersonBookingStatus.Booked,
    )
}
