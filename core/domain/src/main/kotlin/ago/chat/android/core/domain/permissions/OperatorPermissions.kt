package ago.chat.android.core.domain.permissions

/**
 * The signed-in operator's own permission set, read once per session (`navigation.md`'s own Scope,
 * `26-16`) — and, deliberately, a **three-state** answer rather than a `Set<String>` with an implicit
 * "empty means none yet" reading.
 *
 * [Unknown] and a genuinely permission-less [Known] both compute the same *answer* through [holds]
 * (`false`), which is intentional — the safe direction to guess wrong in is "hide", the identical
 * fail-closed shape `ago-console/src/shell/consoleNav.ts`'s own `permissionsKnown` combinator takes
 * (`isAdmin = permissionsKnown && hasPermission(...)`). What the two states are *not* allowed to share
 * is which screen renders while each holds: the app renders a loading screen for [Unknown], never a
 * navigation bar computed from it — see [ago.chat.android.core.domain.navigation.visibleBottomDestinations]'s
 * own doc comment for why collapsing them into one `Boolean` would make "no bar has rendered yet" and
 * "this operator holds nothing" indistinguishable to a caller that only has `false` to read.
 */
public sealed interface OperatorPermissions {
    /** Not read yet this session — before the one `GET /api/v1/operators/me` this state exists to
     * gate a UI decision on has answered at all. */
    public data object Unknown : OperatorPermissions

    /** The server's own answer, verbatim — every string in [granted] is a permission name from
     * `ago-chat`'s own vocabulary ([Permission]), never one this app invented. */
    public data class Known(
        val granted: Set<String>,
    ) : OperatorPermissions
}

/** `true` only when [OperatorPermissions] is genuinely [OperatorPermissions.Known] to hold
 * [permission] — [OperatorPermissions.Unknown] answers `false` here on purpose (this function's own
 * doc comment on the class explains why that collapse is safe for *this* question specifically). */
public fun OperatorPermissions.holds(permission: String): Boolean = this is OperatorPermissions.Known && permission in granted

/** `true` when at least one of [permissions] is held — the shape
 * [ago.chat.android.core.domain.navigation.canSeeBookings] needs for Записи's own four-way gate. */
public fun OperatorPermissions.holdsAny(permissions: List<String>): Boolean = permissions.any { holds(it) }
