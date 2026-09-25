package ago.chat.android.core.domain.restrictions

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-145` (S-C of the `26-111` contact-detail panel): the port the panel's «Ограничить» /
 * «Снять ограничение» action (`26-153`) reads and writes through — declared here and implemented in
 * `:core:network` (`KtorVisitorRestrictionApi`), the identical split
 * [ago.chat.android.core.domain.contactdetails.ContactDetailsApi] already establishes for its own
 * sibling slice. The dependency rule is what puts it here: a view model holding an `HttpClient`
 * directly could not be tested without one, and every HTTP-shaped decision (which status means what,
 * what "currently restricted" reduces to on the wire) belongs on the far side of this interface, in
 * the adapter.
 *
 * Confirmed against the real `ago-chat` code (`VisitorRestrictionsEndpoints.cs`,
 * `ConversationsEndpoints.MapBlockVisitorEndpoint`, `BlockVisitorHandler`, `LiftVisitorRestrictionHandler`,
 * `VisitorRestrictionRepository`) rather than assumed from the `26-111` design doc's own element map, per
 * this item's own Scope. **Q2 is decided: blocking is reversible** — a block and a lift are the same
 * capability exercised in either direction (`BlockVisitorHandler`'s own remarks), so this port carries
 * both plus the read that tells the two apart.
 *
 * **Two distinct server-side gates, named here because a caller cannot see them.** [block] and [lift]
 * are gated `conversation:block` server-side (`BlockVisitorHandler`/`LiftVisitorRestrictionHandler`,
 * Admin-only); [isRestricted] reads `GET /visitor-restrictions`, which `GetVisitorRestrictionsForSiteHandler`
 * gates on the *stronger* `site:configure` — it is a cross-operator, whole-site oversight read, not a
 * per-conversation one. An operator who may block may therefore still be refused the status read; the
 * panel gates its own affordance on the permission it holds, and a [VisitorRestrictionStatusResult.Failed]
 * carrying a `403` is that refusal surfaced, never a claim the visitor is unrestricted.
 */
public interface VisitorRestrictionApi {
    /**
     * `POST /api/v1/conversations/{conversationId}/block-visitor` — no request body, the identical "the
     * route already names the resource" shape
     * [ago.chat.android.core.domain.conversations.ConversationsApi.claim] documents. Blocks the *visitor*
     * behind this conversation indefinitely on this site (`expires_at = null`), never the conversation
     * itself (`BlockVisitorHandler`'s own remarks: blocking is independent of closing). `200` on success;
     * its body confirms which visitor and when, and this port discards it — the caller already holds the
     * visitor's id from the [ConversationSummary][ago.chat.android.core.domain.conversations.ConversationSummary]
     * on screen, and [VisitorRestrictionActionResult] carries only whether the act landed.
     */
    public suspend fun block(conversationId: String): VisitorRestrictionActionResult

    /**
     * `POST /api/v1/visitor-restrictions/{visitorId}/lift` — no request body. Addressed by *visitor*, not
     * by conversation (`LiftVisitorRestriction`'s own remarks), which is why [block] takes a conversation
     * id and this takes a visitor id: they are the two ends of one visitor-scoped restriction, reached
     * from the two facts the panel has (the conversation it opened from, and the `visitorId` already on
     * its own `ConversationSummary`). Lifts *every* currently-active row for this visitor in one statement,
     * so [isRestricted] can never see a stale second row afterward (`VisitorRestrictionRepository.LiftAsync`).
     * `204` on success.
     */
    public suspend fun lift(visitorId: String): VisitorRestrictionActionResult

    /**
     * Whether this visitor is *currently* restricted on this site, read as membership over
     * `GET /api/v1/visitor-restrictions` — the one read the server exposes (`IsActiveAsync` is internal,
     * with no HTTP surface of its own). A row counts as a live restriction when its `visitorId` matches
     * and it has **not been lifted** (`liftedAt == null`) — the same "not lifted" half
     * `VisitorRestrictionRepository.IsActiveAsync` tests.
     *
     * **What this membership read cannot evaluate, stated rather than hidden:** the server's own
     * `IsActiveAsync` also excludes a row whose `expires_at` has passed, which needs a trusted `now` this
     * clock-free adapter deliberately does not hold (no `:core:network` adapter injects `IClock`). This is
     * exact for the panel's own action — a [block] writes an indefinite restriction (`expires_at = null`),
     * which never expires — and can only over-report for a *time-windowed mute* (`23-69`, a console-side
     * feature) whose window has closed but whose row was never lifted. Named here per this codebase's own
     * instruction-source-boundary discipline (`ContactDetailsApi`'s own §on the un-honorable `surface`
     * argument), not silently papered over.
     *
     * The list is keyset-paged; the adapter follows the cursor to the end rather than reading only the
     * first page, so a genuinely-blocked visitor is never reported unrestricted merely because newer
     * restrictions pushed the row off page one.
     */
    public suspend fun isRestricted(visitorId: String): VisitorRestrictionStatusResult
}

/** What blocking or lifting one visitor came back with — the identical three-arm shape
 * [ago.chat.android.core.domain.conversations.ClaimResult] establishes for a write that can be genuinely
 * refused (a missing permission, a conversation that is not this site's), and that
 * [ago.chat.android.core.domain.tags.TagActionResult] already reuses for its own pair. [block] and [lift]
 * share this one type rather than each inventing its own: both reduce to the same
 * `2xx`-or-refusal-or-failure question with nothing further to distinguish. */
public sealed interface VisitorRestrictionActionResult {
    public data object Succeeded : VisitorRestrictionActionResult

    /** A non-`2xx` whose body carried a genuine RFC 7807 `detail` — shown to the operator verbatim; the
     * `:app` layer turns [detail] into what the user sees, never this port ([NetworkFailure]'s own §on
     * where a Russian sentence is rendered). */
    public data class Refused(
        val detail: String,
    ) : VisitorRestrictionActionResult

    /** Everything that is not a genuine server refusal — a dropped connection, or a non-`2xx` whose body
     * carried no `detail` to show. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : VisitorRestrictionActionResult
}

/** What asking whether a visitor is currently restricted came back with — the identical two-arm shape
 * [ago.chat.android.core.domain.contactdetails.ContactDetailsResult] establishes: every cause of "the
 * read failed" renders as the same one banner, so there is no reason to split [Failed] further than the
 * value it carries. [Loaded] holds a plain `Boolean` rather than the restriction rows themselves,
 * because that boolean is the whole question `26-153` asks — which of «Ограничить» / «Снять
 * ограничение» to draw. */
public sealed interface VisitorRestrictionStatusResult {
    public data class Loaded(
        val restricted: Boolean,
    ) : VisitorRestrictionStatusResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : VisitorRestrictionStatusResult
}
