package ago.chat.android.core.domain.identity

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * Where a freshly authenticated identity belongs. Five cases, and the fifth is the one this type
 * exists for.
 *
 * `navigation.md`'s own diagram draws four; [Unavailable] is the arm it labels *"anything else —
 * could not sign in, retry"*, and `11-17` is the record of what folding it into one of the other
 * two costs. It is kept as a first-class case, not an exception, so that a `when` over this type is
 * exhaustive and a later reader cannot quietly default it into [Registration].
 */
public sealed interface SignInDestination {
    /**
     * An operator seat exists and resolves. [activeSiteId] is the tenancy every subsequent request
     * will name; `null` only for the (impossible-by-construction, tolerated anyway) case of a seat
     * that resolved with no tenancy row behind it.
     */
    public data class Operator(
        val activeSiteId: String?,
    ) : SignInDestination

    /**
     * More than one tenancy and none chosen yet. Nothing is probed in this state: with several
     * eligible rows and no `X-Ago-Active-Site` header, `ResolveOperatorIdentityHandler` resolves
     * *nothing* and `GET /api/v1/operators/me` answers `403` — see [PostSignInRouter].
     */
    public data class ChooseSite(
        val tenancies: List<Tenancy>,
    ) : SignInDestination

    /** No operator seat, and `GET /api/v1/owner/sites` accepted. `scope-inventory.md` §2. */
    public data object PlatformOwnerTerminal : SignInDestination

    /** No operator seat and the owner probe positively refused. The only arm that offers the form. */
    public data object Registration : SignInDestination

    /** Nothing was established. Renders a retry, and is never either terminal arm. */
    public data class Unavailable(
        val failure: RoutingFailure,
    ) : SignInDestination
}

/** Which question went unanswered, so the retry screen can say something truer than "error". */
public sealed interface RoutingFailure {
    public data class ProbeDidNotAnswer(
        val step: RoutingStep,
        val reason: NetworkFailure,
    ) : RoutingFailure

    /**
     * `GET /api/v1/me/tenancies` listed at least one tenancy this identity may sign into, and
     * `GET /api/v1/operators/me` then refused with the active site already named. The two reads
     * contradict each other, so neither is trustworthy — and the one thing that must not happen is
     * treating the refusal as "therefore a new registrant" and offering an operator with a live seat
     * a form that registers a second shop. This is `12-04`'s defect class, reached from a different
     * direction than `12-04` itself came from.
     */
    public data object OperatorSeatRefusedDespiteTenancies : RoutingFailure
}

public enum class RoutingStep {
    TENANCIES,
    OPERATOR_SEAT,
    OWNER_ELIGIBILITY,
}
