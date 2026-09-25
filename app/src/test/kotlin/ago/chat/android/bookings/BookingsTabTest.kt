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
 * `26-103`: [visibleBookingsSegments] used to also take `showServicesSegment`/`showHoursSegment` and
 * could draw up to five segments — that is what wrapped on a real device. It now only ever computes the
 * three operational segments (Ожидают/Утверждены/Клиенты); the configuration pair (Услуги/Часы) moved to
 * [visibleBookingsConfigMenuEntries], covered separately below.
 */
class BookingsTabTest {
    @Test
    fun `only Pending is drawn with no segment earned`() {
        assertEquals(
            listOf(BookingsTab.Pending),
            visibleBookingsSegments(showConfirmedSegment = false, showClientsSegment = false),
        )
    }

    @Test
    fun `Pending and Confirmed are drawn, Clients withheld`() {
        assertEquals(
            listOf(BookingsTab.Pending, BookingsTab.Confirmed),
            visibleBookingsSegments(showConfirmedSegment = true, showClientsSegment = false),
        )
    }

    @Test
    fun `Pending and Clients are drawn, Confirmed withheld`() {
        assertEquals(
            listOf(BookingsTab.Pending, BookingsTab.Clients),
            visibleBookingsSegments(showConfirmedSegment = false, showClientsSegment = true),
        )
    }

    @Test
    fun `all three operational segments are drawn, in Pending, Confirmed, Clients order`() {
        assertEquals(
            listOf(BookingsTab.Pending, BookingsTab.Confirmed, BookingsTab.Clients),
            visibleBookingsSegments(showConfirmedSegment = true, showClientsSegment = true),
        )
    }

    @Test
    fun `visibleBookingsSegments never returns more than three entries`() {
        // `26-103`'s own point: whatever the two booleans are, the result can never wrap a
        // three-segment row, because there is no fourth or fifth entry left for this function to add.
        assertEquals(3, visibleBookingsSegments(showConfirmedSegment = true, showClientsSegment = true).size)
    }
}

/**
 * `26-103`: the `⋮` menu's own entries — the fourth and fifth segment `26-96`/`26-97` used to add,
 * reached from [ago.chat.android.bookings.BookingsScreen]'s new `BookingsConfigMenu` instead. The case
 * worth pinning is the one `BookingsTabTest` above already pinned for the segmented row: the two gates
 * disagree in both directions, since Клиенты (a segment, `calendar:configure` *or* `customer:read`) is
 * not either of these (`calendar:configure` alone).
 */
class BookingsConfigMenuEntriesTest {
    @Test
    fun `no entry earned means an empty list - the menu control itself must not be drawn`() {
        assertEquals(
            emptyList<BookingsTab>(),
            visibleBookingsConfigMenuEntries(
                showSetupSegment = false,
                showMastersSegment = false,
                showServicesSegment = false,
                showHoursSegment = false,
            ),
        )
    }

    /** `26-142`'s own Done-when: the Настройка (Календари) entry appears exactly on `calendar:configure`.
     * An operator without it earns no config entry at all - the whole `⋮` stays hidden (the case above). */
    @Test
    fun `Calendars alone is a real combination, gated on calendar-configure`() {
        assertEquals(
            listOf(BookingsTab.Calendars),
            visibleBookingsConfigMenuEntries(
                showSetupSegment = true,
                showMastersSegment = false,
                showServicesSegment = false,
                showHoursSegment = false,
            ),
        )
    }

    /** `26-140`'s own Done-when: the Мастера entry appears exactly on `calendar:configure`. An operator
     * without it earns no config entry at all - the whole `⋮` stays hidden (the case above). */
    @Test
    fun `Masters alone is a real combination, gated on calendar-configure`() {
        assertEquals(
            listOf(BookingsTab.Masters),
            visibleBookingsConfigMenuEntries(
                showSetupSegment = false,
                showMastersSegment = true,
                showServicesSegment = false,
                showHoursSegment = false,
            ),
        )
    }

    @Test
    fun `Services without the others is a real combination`() {
        assertEquals(
            listOf(BookingsTab.Services),
            visibleBookingsConfigMenuEntries(
                showSetupSegment = false,
                showMastersSegment = false,
                showServicesSegment = true,
                showHoursSegment = false,
            ),
        )
    }

    @Test
    fun `Hours alone is a real combination too`() {
        assertEquals(
            listOf(BookingsTab.Hours),
            visibleBookingsConfigMenuEntries(
                showSetupSegment = false,
                showMastersSegment = false,
                showServicesSegment = false,
                showHoursSegment = true,
            ),
        )
    }

    /** `26-142`: the fill/readiness order is Настройка (Календари) before Мастера before Услуги before
     * Часы - Календари leads, because a calendar is the thing a worker, a service and a working-hours rule
     * all hang off. */
    @Test
    fun `all entries are drawn, Calendars before Masters before Services before Hours`() {
        assertEquals(
            listOf(BookingsTab.Calendars, BookingsTab.Masters, BookingsTab.Services, BookingsTab.Hours),
            visibleBookingsConfigMenuEntries(
                showSetupSegment = true,
                showMastersSegment = true,
                showServicesSegment = true,
                showHoursSegment = true,
            ),
        )
    }
}
