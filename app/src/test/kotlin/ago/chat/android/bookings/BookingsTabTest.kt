package ago.chat.android.bookings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-51`/`26-52`'s own Done-when: "an operator holding only a booking-action permission does not see
 * either segment" — the pure function behind [BookingsScreen]'s segmented control, testable with no
 * Compose UI test and no Hilt component. The booleans are independent
 * (`docs/backlog/26-52-*.md`'s own Scope item 1: Клиенты's gate is genuinely distinct from Утверждены's),
 * so the combinations that could collapse into one another are exercised here, not only the two ends
 * of one shared flag.
 *
 * `26-96` adds a fourth segment, Услуги, on `calendar:configure` alone — the case worth pinning is the
 * one where it and Клиенты disagree, since Клиенты is `calendar:configure` *or* `customer:read`: an
 * operator with only `customer:read` gets Клиенты and must not get Услуги.
 */
class BookingsTabTest {
    @Test
    fun `only Pending is drawn with no segment earned`() {
        assertEquals(
            listOf(BookingsTab.Pending),
            visibleBookingsTabs(showConfirmedSegment = false, showClientsSegment = false, showServicesSegment = false),
        )
    }

    @Test
    fun `Pending and Confirmed are drawn, the rest withheld`() {
        assertEquals(
            listOf(BookingsTab.Pending, BookingsTab.Confirmed),
            visibleBookingsTabs(showConfirmedSegment = true, showClientsSegment = false, showServicesSegment = false),
        )
    }

    @Test
    fun `Pending and Clients are drawn, the rest withheld`() {
        assertEquals(
            listOf(BookingsTab.Pending, BookingsTab.Clients),
            visibleBookingsTabs(showConfirmedSegment = false, showClientsSegment = true, showServicesSegment = false),
        )
    }

    /** `26-96`: the disagreement that matters. `customer:read` alone earns Утверждены and Клиенты; it
     * does not earn the right to rewrite the tenant's own service dictionary. */
    @Test
    fun `Clients without Services is a real combination, not a rounding of one flag`() {
        assertEquals(
            listOf(BookingsTab.Pending, BookingsTab.Confirmed, BookingsTab.Clients),
            visibleBookingsTabs(showConfirmedSegment = true, showClientsSegment = true, showServicesSegment = false),
        )
    }

    /** And the other direction: `calendar:configure` alone earns Клиенты and Услуги, never Утверждены. */
    @Test
    fun `Services without Confirmed is a real combination too`() {
        assertEquals(
            listOf(BookingsTab.Pending, BookingsTab.Clients, BookingsTab.Services),
            visibleBookingsTabs(showConfirmedSegment = false, showClientsSegment = true, showServicesSegment = true),
        )
    }

    @Test
    fun `all four segments are drawn, in Pending, Confirmed, Clients, Services order`() {
        assertEquals(
            listOf(BookingsTab.Pending, BookingsTab.Confirmed, BookingsTab.Clients, BookingsTab.Services),
            visibleBookingsTabs(showConfirmedSegment = true, showClientsSegment = true, showServicesSegment = true),
        )
    }
}
