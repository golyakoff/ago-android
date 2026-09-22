package ago.chat.android.core.domain.identity

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

    /** The question was not answered at all. Never either terminal arm. */
    public data class Unanswered(
        val reason: ProbeFailure,
    ) : ProbeOutcome
}

/** Why a probe could not answer. Rendered to the operator, so each case says something different. */
public sealed interface ProbeFailure {
    /**
     * A status the caller has no reading for — a `401` (the bearer token was rejected *after* the
     * client's own refresh-and-retry already had its turn), a `5xx`, anything else.
     */
    public data class UnexpectedStatus(
        val status: Int,
    ) : ProbeFailure

    /** The request never reached a server, or its answer never came back. */
    public data class Transport(
        val message: String,
    ) : ProbeFailure

    /** A `2xx` whose body was not the shape this contract promises. */
    public data class Malformed(
        val message: String,
    ) : ProbeFailure
}
