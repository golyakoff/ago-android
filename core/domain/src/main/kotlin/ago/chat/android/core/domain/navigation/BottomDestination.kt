package ago.chat.android.core.domain.navigation

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.bookingsGate
import ago.chat.android.core.domain.permissions.holdsAny

/**
 * The five bottom-navigation destinations, in `navigation.md`'s own order — Диалоги, Записи, Команда,
 * Аналитика, Ещё. **This order is not the console's own rail order and was corrected once**: an
 * earlier draft of `navigation.md` had [Bookings] and [Team] swapped, and the author's own correction
 * is what every mockup frame states now (`docs/backlog/26-16-*.md`'s own Scope repeats this
 * explicitly, which is the size of mistake this enum's declaration order exists to make impossible to
 * reintroduce silently).
 */
public enum class BottomDestination {
    Conversations,
    Bookings,
    Team,
    Analytics,
    More,
}

/**
 * Which destinations this operator's own permission set draws, in [BottomDestination]'s own order —
 * `navigation.md` §"The top-level shape": **a destination with nothing inside it is not drawn**, the
 * identical rule `ago-console/src/shell/consoleNav.ts`'s `buildSection` applies (return nothing rather
 * than an empty section, filter at the call site — there is no call site to filter at here, since
 * [Bookings] is the only destination this function ever omits).
 *
 * Pure logic over [OperatorPermissions], with no Android or Compose dependency crossing this
 * function — the identical reason [ago.chat.android.core.domain.identity.PostSignInRouter] is a
 * plain class in this same module: the property this item's Done-when needs proven ("an operator
 * without a calendar grant sees four destinations; one with it sees five") is exactly the kind of
 * thing worth being reachable from a plain JVM unit test with a fake, never only from an instrumented
 * one.
 *
 * **[OperatorPermissions.Unknown] draws the four-destination floor, identically to a genuinely
 * permission-less operator.** This is the fail-closed direction `consoleNav.ts`'s own
 * `permissionsKnown` combinator names as "the safe direction to guess wrong in for the second or so
 * this answer is in flight" — but this app takes it one step further than the console does: the
 * caller (`ago.chat.android.shell.AppShellScreen`/its own view model, see that file's doc comment) does
 * not actually call this function at all while [OperatorPermissions.Unknown] — it renders a full-screen
 * loading state instead, so the bar an operator first sees is *never wrong and then corrected*, only
 * ever drawn once, already right. This function still answers safely for [OperatorPermissions.Unknown]
 * regardless, so a future caller that does not have that luxury (e.g. a background refresh) inherits
 * the same fail-closed shape for free rather than a `null`/exception it would have to invent its own
 * answer for.
 */
public fun visibleBottomDestinations(permissions: OperatorPermissions): List<BottomDestination> =
    buildList {
        add(BottomDestination.Conversations)
        if (canSeeBookings(permissions)) add(BottomDestination.Bookings)
        add(BottomDestination.Team)
        add(BottomDestination.Analytics)
        add(BottomDestination.More)
    }

/**
 * `navigation.md`'s own top-level table, row 2: "`calendar:configure`, or any booking action
 * permission, or `customer:read`" — four independent ways to earn this one destination, matching
 * `consoleNav.ts`'s `buildCalendarItems`' own operator branch (`hasAnyBookingActionPermission` plus
 * `customer:read`, folded with the tenant's own `calendar:configure` here into one gate because this
 * item, unlike the console, does not yet distinguish "the full seven items" from "the operator's
 * narrower few" — that distinction is Записи's own future content, not this item's hide-when-empty
 * decision).
 */
public fun canSeeBookings(permissions: OperatorPermissions): Boolean = permissions.holdsAny(bookingsGate)
