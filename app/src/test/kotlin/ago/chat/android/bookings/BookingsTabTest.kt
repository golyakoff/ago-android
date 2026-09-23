package ago.chat.android.bookings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-51`'s own Done-when: "an operator holding only a booking-action permission does not see this
 * segment at all" — the pure function behind [BookingsScreen]'s segmented control, testable with no
 * Compose UI test and no Hilt component.
 */
class BookingsTabTest {
    @Test
    fun `only Pending is drawn without customer read`() {
        assertEquals(listOf(BookingsTab.Pending), visibleBookingsTabs(showConfirmedSegment = false))
    }

    @Test
    fun `both segments are drawn, Pending first, with customer read`() {
        assertEquals(listOf(BookingsTab.Pending, BookingsTab.Confirmed), visibleBookingsTabs(showConfirmedSegment = true))
    }
}
