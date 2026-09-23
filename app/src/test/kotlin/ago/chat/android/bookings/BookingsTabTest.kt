package ago.chat.android.bookings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-51`/`26-52`'s own Done-when: "an operator holding only a booking-action permission does not see
 * either segment" — the pure function behind [BookingsScreen]'s segmented control, testable with no
 * Compose UI test and no Hilt component. The two booleans are independent
 * (`docs/backlog/26-52-*.md`'s own Scope item 1: Клиенты's gate is genuinely distinct from Утверждены's),
 * so every combination is exercised here, not only the two ends of one shared flag.
 */
class BookingsTabTest {
    @Test
    fun `only Pending is drawn with neither segment earned`() {
        assertEquals(
            listOf(BookingsTab.Pending),
            visibleBookingsTabs(showConfirmedSegment = false, showClientsSegment = false),
        )
    }

    @Test
    fun `Pending and Confirmed are drawn, Clients withheld`() {
        assertEquals(
            listOf(BookingsTab.Pending, BookingsTab.Confirmed),
            visibleBookingsTabs(showConfirmedSegment = true, showClientsSegment = false),
        )
    }

    @Test
    fun `Pending and Clients are drawn, Confirmed withheld`() {
        assertEquals(
            listOf(BookingsTab.Pending, BookingsTab.Clients),
            visibleBookingsTabs(showConfirmedSegment = false, showClientsSegment = true),
        )
    }

    @Test
    fun `all three segments are drawn, in Pending, Confirmed, Clients order`() {
        assertEquals(
            listOf(BookingsTab.Pending, BookingsTab.Confirmed, BookingsTab.Clients),
            visibleBookingsTabs(showConfirmedSegment = true, showClientsSegment = true),
        )
    }
}
