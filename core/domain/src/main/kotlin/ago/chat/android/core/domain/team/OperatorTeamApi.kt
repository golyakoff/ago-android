package ago.chat.android.core.domain.team

/**
 * `26-55`: the port [ago.chat.android.team.PeopleViewModel] (`:app`) reads through — declared here and
 * implemented in `:core:network` (`KtorOperatorTeamApi`), the identical split
 * [ago.chat.android.core.domain.bookings.BookingsApi] already establishes for Записи. The dependency
 * rule is what puts it here: a view model holding an `HttpClient` directly could not be tested without
 * one, and every HTTP-shaped decision belongs on the far side of this interface, in the adapter.
 *
 * Two reads, matching this item's own two server calls (`docs/backlog/26-55-*.md`'s own Found section,
 * against `ago-console/src/api/operatorTeamApi.ts`'s real, verified shape). Every write — inviting
 * (`26-56`), the seat toggle, the role change, the removal — is each its own confirmation-bearing
 * change against somebody's access, and each is a separate future item's own port, never this one's
 * (this item's own Out of scope: "a read-only roster is complete").
 */
public interface OperatorTeamApi {
    /** `GET /api/v1/sites/{siteId}/operators` — every operator on the current active site, each with
     * every role they hold and that specific `(operator, role)` pairing's own seat status
     * ([OperatorRoleSeat]). */
    public suspend fun fetchTeam(): OperatorTeamResult

    /** `GET /api/v1/sites/{siteId}/operators/seat-assignment-summary` — one row per seeded role
     * ([RoleSeatSummary]), read straight off the response: [RoleSeatSummary.overLimit] is never
     * recomputed on this side (`docs/backlog/26-55-*.md`'s own Done-when: "no client-side seat
     * arithmetic exists anywhere in the change"). */
    public suspend fun fetchSeatSummary(): SeatSummaryResult
}

/**
 * `25-170`: one role an operator holds, and whether that specific `(operator, role)` pairing itself
 * currently holds a seat — mirrors `ago-console`'s own `OperatorRoleSeatDto` field for field. A seat
 * belongs to the pairing, not to the operator account, which is why [OperatorTeamMember.roles] is a
 * list rather than a single flag: the flat `holdsSeat`/`roleNames` pair this replaces is gone
 * server-side, and rendering "holds a seat: yes/no" per person is exactly the thing this item exists to
 * stop the app from doing.
 */
public data class OperatorRoleSeat(
    val roleName: String,
    val holdsSeat: Boolean,
)

/**
 * One row of the roster. [displayName]/[email] are `null` exactly when the server has neither on file
 * for this operator — rendered through `ui/components/IdentifierText.kt` at that point
 * (`OperatorsTeamPage.tsx`'s own identical fallback, `operatorId.slice(0, 8)`), never invented here.
 */
public data class OperatorTeamMember(
    val operatorId: String,
    val displayName: String?,
    val email: String?,
    /** Plural for the identical `25-170` reason [OperatorRoleSeat] itself states — a founder holding
     * both seeded roles shows two seat facts, never one. */
    val roles: List<OperatorRoleSeat>,
)

/** What answering "who is on this site, and what are they holding" came back with. */
public sealed interface OperatorTeamResult {
    public data class Loaded(
        val members: List<OperatorTeamMember>,
    ) : OperatorTeamResult

    /** The call did not answer usefully — see [OperatorTeamFailure] for the two things that can mean. */
    public data class Failed(
        val reason: OperatorTeamFailure,
    ) : OperatorTeamResult
}

/**
 * One seeded role's own seat summary. [overLimit] is `heldSeats > limit`, a **read-time** fact
 * computed server-side and read verbatim — deliberately distinct from the *invite-time* refusal
 * predicate (`heldSeats >= limit`), which `ago-console`'s own `OperatorsTeamPage` computes itself for
 * its own invite dialog, a write this item does not build at all (`docs/backlog/26-55-*.md`'s own Out
 * of scope). The two thresholds answer different questions and this port must never conflate them.
 */
public data class RoleSeatSummary(
    val roleName: String,
    val heldSeats: Int,
    val limit: Int,
    val overLimit: Boolean,
)

public sealed interface SeatSummaryResult {
    public data class Loaded(
        val roles: List<RoleSeatSummary>,
    ) : SeatSummaryResult

    public data class Failed(
        val reason: OperatorTeamFailure,
    ) : SeatSummaryResult
}

/**
 * `26-59`'s own shared classification, read onto this port before that item lands —
 * [ago.chat.android.core.domain.bookings.BookingsQueueFailure]'s own doc comment states the full
 * reasoning for why this is two buckets rather than a copy of the status code or a message string; this
 * is the identical shape for the roster's own two reads.
 */
public enum class OperatorTeamFailure {
    /** No route to the server reached at all — offline, a DNS failure, a dropped connection, a
     * timeout. Worth retrying once the network itself is back. */
    Transport,

    /** The server answered, but not usefully — any non-2xx status, a `2xx` whose body was not the
     * shape this adapter promised, or no active site selected at all (which should not happen once this
     * screen is reachable, but is answered the same honest way as any other "not usefully" case rather
     * than crashing). */
    Unexpected,
}

/** `RegisterSiteHandler`'s own two seeded role names — named once here, the same
 * `ROLE_OPERATOR`/`ROLE_ADMIN` precedent `ago-console`'s own `operatorTeamApi.ts` already establishes,
 * so [ago.chat.android.team.PeopleScreen] has no reason to spell either literal a second time. Not an
 * enum: a role is still a name resolved server-side, never a typed value this app owns
 * (`Permission`'s own doc comment states the identical reasoning for permission strings). */
public const val ROLE_OPERATOR: String = "Operator"
public const val ROLE_ADMIN: String = "Admin"
