package ago.chat.android.core.domain.identity

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * What a single post-authentication probe answered.
 *
 * Three values, not two, and that is the whole point of this type existing rather than a `Boolean`.
 * `adr/0063`'s rule, extracted from three surfaces that each got it wrong: **a surface may act on
 * "this principal is an X" only when something authoritative said X** — a missing claim, a refused
 * call or an unreachable server is evidence of nothing. A `Boolean` has nowhere to put
 * [Unanswered], so folding it into [Refused] is not a shortcut a reviewer would catch; it is the
 * shape the type invites. `11-17` is the console's own instance of exactly that, and this type is
 * what stops it being reproducible here.
 */
public sealed interface ProbeOutcome {
    /** The server positively answered "yes" — a `2xx`. */
    public data object Accepted : ProbeOutcome

    /**
     * The server positively answered "no" — a `403` from the policy that decides this question.
     * A refusal is an *answer*: it was computed server-side by the authoritative check
     * (`RequireOperatorIdentity` / `RequirePlatformOwner`) and read back, never inferred from the
     * token client-side.
     */
    public data object Refused : ProbeOutcome

    /** The question was not answered at all. Never either terminal arm.
     *
     * `26-59`: [reason] used to be this package's own [Unanswered]-only failure type
     * (`UnexpectedStatus`/`Transport`/`Malformed`, each of the latter two carrying a raw exception
     * description). It is [ago.chat.android.core.domain.net.NetworkFailure] now — the identical
     * three-way distinction, generalised once it became clear every adapter in this app needed the
     * same answer, not only this one's three probes.
     */
    public data class Unanswered(
        val reason: NetworkFailure,
    ) : ProbeOutcome
}
