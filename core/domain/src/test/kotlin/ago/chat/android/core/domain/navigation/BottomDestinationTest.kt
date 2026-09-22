package ago.chat.android.core.domain.navigation

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.Permission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-16`: the hide-when-empty computation itself — `docs/backlog/26-16-*.md`'s own Done-when,
 * "an operator without a calendar grant sees four destinations; one with it sees five", made a fact
 * about a pure function rather than an assertion against a running app.
 */
class BottomDestinationTest {
    @Test
    fun `an operator with none of the four gates sees exactly the four-destination floor`() {
        val destinations = visibleBottomDestinations(OperatorPermissions.Known(emptySet()))

        assertEquals(
            listOf(
                BottomDestination.Conversations,
                BottomDestination.Team,
                BottomDestination.Analytics,
                BottomDestination.More,
            ),
            destinations,
        )
    }

    @Test
    fun `holding calendar configure earns Записи its place, in navigation md's own order`() {
        val destinations = visibleBottomDestinations(OperatorPermissions.Known(setOf(Permission.CALENDAR_CONFIGURE)))

        assertEquals(
            listOf(
                BottomDestination.Conversations,
                BottomDestination.Bookings,
                BottomDestination.Team,
                BottomDestination.Analytics,
                BottomDestination.More,
            ),
            destinations,
        )
    }

    @Test
    fun `each of the three booking-action permissions alone is also enough, independently`() {
        for (permission in listOf(Permission.BOOKING_CONFIRM, Permission.BOOKING_REJECT, Permission.BOOKING_CANCEL)) {
            assertTrue(
                "$permission alone must earn Записи its place",
                canSeeBookings(OperatorPermissions.Known(setOf(permission))),
            )
        }
    }

    @Test
    fun `customer read alone is also enough, independently of every calendar permission`() {
        assertTrue(canSeeBookings(OperatorPermissions.Known(setOf(Permission.CUSTOMER_READ))))
    }

    @Test
    fun `site configure alone - an unrelated permission - never earns Записи its place`() {
        assertFalse(canSeeBookings(OperatorPermissions.Known(setOf(Permission.SITE_CONFIGURE))))
    }

    /**
     * The other property this item's own Done-when names: "permissions still loading does not render
     * a wrong bar that then changes under the operator" — proven here as "the not-yet-loaded state
     * computes the identical, safe four-destination answer a genuinely permission-less operator
     * gets", which is what makes it *safe* for a caller to fall back on this function's own answer
     * rather than inventing a second one, even though the real app never actually calls this function
     * while [OperatorPermissions.Unknown] (`AppShellViewModel`'s own doc comment has the full
     * reasoning for why the loading window renders no bar at all rather than this one).
     */
    @Test
    fun `permissions not yet loaded computes the identical four-destination floor as none held`() {
        val whileLoading = visibleBottomDestinations(OperatorPermissions.Unknown)
        val genuinelyNone = visibleBottomDestinations(OperatorPermissions.Known(emptySet()))

        assertEquals(genuinelyNone, whileLoading)
        assertFalse(BottomDestination.Bookings in whileLoading)
    }

    @Test
    fun `the floor never collapses below four - Team, Analytics and More are never gated`() {
        val destinations = visibleBottomDestinations(OperatorPermissions.Known(emptySet()))

        assertTrue(BottomDestination.Team in destinations)
        assertTrue(BottomDestination.Analytics in destinations)
        assertTrue(BottomDestination.More in destinations)
        assertTrue(BottomDestination.Conversations in destinations)
    }
}
