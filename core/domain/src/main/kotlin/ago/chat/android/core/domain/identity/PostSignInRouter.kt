package ago.chat.android.core.domain.identity

/**
 * The post-authentication routing tree — `navigation.md` §"Sign-in, and the three-way routing that
 * must not be simplified", `adr/0063`, `12-04`, `11-17`.
 *
 * Pure logic over a port. No HTTP type crosses this file, so every arm below is reachable in a plain
 * JVM unit test by handing [IdentityApi] a fake — which is what `PostSignInRouterTest` does, one
 * test per arm, including the arm that "looks correct" when it is wrong.
 *
 * ## The four arms, unchanged
 *
 * - `operators/me` `200` → [SignInDestination.Operator].
 * - `operators/me` `403`, `owner/sites` accepted → [SignInDestination.PlatformOwnerTerminal].
 * - `operators/me` `403`, `owner/sites` refused → [SignInDestination.Registration].
 * - **anything else — a `401`, a `5xx`, a dropped connection — → [SignInDestination.Unavailable].**
 *   Not folded into either terminal answer. That is `11-17`'s correction, and the reason it is a
 *   separate `ProbeOutcome` case rather than a `catch` is that a `catch` is easy to widen later.
 *
 * ## Why `GET /api/v1/me/tenancies` runs *first*, ahead of `operators/me`
 *
 * `navigation.md`'s diagram draws the tenancy question *after* a `200` from `operators/me`. Ported
 * literally, that is a defect, and it was found by reading the server rather than by guessing:
 * `ResolveOperatorIdentityHandler` (`ago-chat`, `13-07`/`adr/0068`) resolves an identity with **more
 * than one eligible tenancy and no requested site to `null`** — deliberately, since picking one
 * would be the cross-tenant misdirection that ADR exists to forbid. No resolution means no
 * `operator_id` claim, which means `RequireOperatorIdentity` refuses, which means
 * `GET /api/v1/operators/me` answers **`403` for a two-shop operator on a fresh device**. Down the
 * literal diagram, that `403` reaches the owner probe, is refused, and lands a working operator on
 * the *site-registration* arm — `12-04`'s exact defect, produced by a different identity.
 *
 * `/me/tenancies` is gated by the weaker `RequireKeycloakIdentity` precisely so it can answer for an
 * identity that has zero tenancies *or* several, which is why asking it first is not a workaround
 * but the order the server's own contract implies. `ago-console`'s `PermissionsProvider` already
 * sequences its two calls this way for the identical reason ("set before the next fetch is built");
 * what the console does *not* do is apply that ordering to `CallbackPage`'s routing, which is where
 * this reading suggests the console has the same latent bug on a first sign-in.
 *
 * The cost is one extra request for an identity that turns out to have no seat at all — the rarest
 * path — and zero extra for everybody else, since the `200` path had to read tenancies anyway.
 *
 * ## Two arms that are narrower here than in `ago-console`, deliberately
 *
 * 1. **An unanswerable *owner* probe renders a retry, not the registration form.** `CallbackPage`
 *    sends it to `/onboarding` and says why: that was where the state already went, and the server
 *    refused the submission independently. `12-05` removed that server-side refusal (`adr/0063`'s
 *    amendment), so the argument's second half no longer holds — and its first half is exactly the
 *    inference `adr/0063` names as the defect class: acting on "therefore a registrant" when
 *    nothing authoritative said so. The retry arm already exists here and costs nothing to reuse.
 * 2. **A `403` from `operators/me` while tenancies are listed is a contradiction, not an answer** —
 *    [RoutingFailure.OperatorSeatRefusedDespiteTenancies]. The owner and registration arms are
 *    reachable only from "this identity holds no tenancy at all", which is what both of them
 *    actually describe.
 *
 * Both are one-line changes if the author wants the console's behaviour instead; both are called out
 * here rather than left for a reader to discover.
 */
public class PostSignInRouter(
    private val identity: IdentityApi,
    private val activeSite: ActiveSiteSelection,
) {
    /**
     * Runs the tree once and returns where this identity belongs.
     *
     * Call it again after the operator picks a site (having called [ActiveSiteSelection.select]
     * first): [SignInDestination.ChooseSite] is a question, and answering it re-enters here with the
     * header source already written.
     */
    public suspend fun route(): SignInDestination {
        val tenancies =
            when (val listing = identity.listMyTenancies()) {
                is TenancyListing.Unanswered ->
                    return SignInDestination.Unavailable(
                        RoutingFailure.ProbeDidNotAnswer(RoutingStep.TENANCIES, listing.reason),
                    )

                is TenancyListing.Known -> listing.tenancies
            }

        // The one ordering this whole flow turns on: the active site is published *before* the probe
        // that depends on it is built, never after. `X-Ago-Active-Site` is attached by a client
        // plugin reading `activeSite`, so writing it here is what puts it on the very next request.
        when (tenancies.size) {
            0 -> activeSite.select(null)
            1 -> activeSite.select(tenancies.single().siteId)
            else -> {
                val chosen = activeSite.currentSiteId()
                if (chosen == null || tenancies.none { it.siteId == chosen }) {
                    // A selection that is not in this identity's own list is somebody else's, or a
                    // seat that has since been removed. Cleared rather than sent: the header can only
                    // narrow what a request resolves to, so a stale one costs a `403` and a confused
                    // operator, and there is no reason to spend either.
                    activeSite.select(null)
                    return SignInDestination.ChooseSite(tenancies)
                }
            }
        }

        return when (val seat = identity.probeOperatorSeat()) {
            ProbeOutcome.Accepted -> SignInDestination.Operator(activeSite.currentSiteId())

            is ProbeOutcome.Unanswered ->
                SignInDestination.Unavailable(
                    RoutingFailure.ProbeDidNotAnswer(RoutingStep.OPERATOR_SEAT, seat.reason),
                )

            ProbeOutcome.Refused ->
                if (tenancies.isEmpty()) {
                    askTheOwnerQuestion()
                } else {
                    SignInDestination.Unavailable(RoutingFailure.OperatorSeatRefusedDespiteTenancies)
                }
        }
    }

    /**
     * The second question, asked only once the first has answered "no operator seat" — which, since
     * `adr/0032`, is a state two different people are in (`12-04`).
     *
     * It is the server's own answer (`RequirePlatformOwner`'s decision, read back through
     * `GET /api/v1/owner/sites?limit=1`), never an inspection of `realm_access.roles` in the client.
     * `adr/0063`'s second clause: recognising a given kind has one implementation, server-side, and
     * a client asks it rather than making a second copy.
     */
    private suspend fun askTheOwnerQuestion(): SignInDestination =
        when (val owner = identity.probeOwnerEligibility()) {
            ProbeOutcome.Accepted -> SignInDestination.PlatformOwnerTerminal
            ProbeOutcome.Refused -> SignInDestination.Registration
            is ProbeOutcome.Unanswered ->
                SignInDestination.Unavailable(
                    RoutingFailure.ProbeDidNotAnswer(RoutingStep.OWNER_ELIGIBILITY, owner.reason),
                )
        }
}
