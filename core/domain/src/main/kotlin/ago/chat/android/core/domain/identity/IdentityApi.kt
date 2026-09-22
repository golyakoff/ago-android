package ago.chat.android.core.domain.identity

/**
 * The port `PostSignInRouter` asks its three questions through — declared in `:core:domain` and
 * implemented in `:core:network` (`KtorIdentityApi`).
 *
 * The dependency rule is what puts it here: the routing tree is the piece most likely to be got
 * wrong (`navigation.md`, "the wrong version looks correct"), so it must be testable with no HTTP
 * stack at all — which it cannot be if it holds an `HttpClient`. The alternative, a router that
 * takes a Ktor client directly, would make every one of its arms a `MockEngine` fixture and would
 * put a transport type in the one module that has no Android or IO dependency by construction.
 *
 * Each method answers in this module's own vocabulary ([ProbeOutcome], [TenancyListing]), never in
 * HTTP status codes: mapping `403` to [ProbeOutcome.Refused] is the adapter's job, and keeping that
 * mapping on the far side of this interface is what makes the "anything else is neither answer" arm
 * a single, total `when` rather than a chain of integer comparisons spread across the tree.
 */
public interface IdentityApi {
    /**
     * `GET /api/v1/me/tenancies` — gated by `RequireKeycloakIdentity`, so it answers for an identity
     * that has zero tenancies *and* for one that has several. Both of those fail
     * `RequireOperatorIdentity`, which is precisely why this call has to come first.
     */
    public suspend fun listMyTenancies(): TenancyListing

    /** `GET /api/v1/operators/me` — `RequireOperatorIdentity`'s own decision, read back. */
    public suspend fun probeOperatorSeat(): ProbeOutcome

    /** `GET /api/v1/owner/sites?limit=1` — `RequirePlatformOwner`'s own decision, read back. */
    public suspend fun probeOwnerEligibility(): ProbeOutcome
}
