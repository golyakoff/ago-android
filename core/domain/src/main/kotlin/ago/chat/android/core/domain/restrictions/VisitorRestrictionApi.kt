package ago.chat.android.core.domain.restrictions

import ago.chat.android.core.domain.net.NetworkFailure
import java.time.Instant

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

    /**
     * `26-227`: `GET /api/v1/visitor-restrictions?before=&limit=` — the tenant's own oversight list
     * (`docs/design/tenant-modules-restrictions-android.md` §1), keyset-paged by [before]/[limit] the
     * identical way [ago.chat.android.core.domain.conversations.ConversationsApi.fetchAllConversations]
     * already pages its own site-wide list. Gated `site:configure` server-side
     * (`GetVisitorRestrictionsForSiteHandler`) — the *stronger* gate [isRestricted]'s own doc comment
     * already names, since this is the same cross-operator, whole-site read that method's `GET` call
     * happens to reuse for a narrower membership question.
     *
     * Lives on this port, beside [block]/[lift]/[isRestricted], rather than a second port of its own:
     * all four are the one `visitor-restrictions` server resource — the design doc's own reasoning for
     * why a second port here would split one server noun across two adapters for no gain.
     */
    public suspend fun list(
        before: String?,
        limit: Int?,
    ): VisitorRestrictionPageResult
}

/**
 * `26-227`: one keyset page of `GET /api/v1/visitor-restrictions` — the tenant oversight screen's own
 * read, the identical two-arm shape [VisitorRestrictionStatusResult] already establishes for the port's
 * other read: every cause of "the read failed" renders as the same one banner.
 */
public sealed interface VisitorRestrictionPageResult {
    public data class Loaded(
        val items: List<VisitorRestriction>,
        val nextBeforeId: String?,
    ) : VisitorRestrictionPageResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : VisitorRestrictionPageResult
}

/**
 * `26-227`: one `visitor_restrictions` row, read back for the tenant's own oversight screen — every
 * field `VisitorRestrictionsEndpoints.VisitorRestrictionListItemDto` carries. [restrictedAt] is
 * non-nullable on the wire and stays so here; a `200` body whose row is missing it (or any other
 * required field) fails to parse and the whole page classifies [NetworkFailure.Unexpected] rather than
 * silently dropping the row — the identical shape-guard discipline
 * [ago.chat.android.core.network.visitorhistory.KtorVisitorHistoryApi]'s own wire DTO already applies to
 * its required fields.
 *
 * [emojiCreature]/[emojiFood] are `26-202`'s own additive pair — `null` for a visitor row that predates
 * it, the identical absent-means-absent contract
 * [ago.chat.android.core.domain.conversations.ConversationSummary.emojiCreature] already carries. This
 * screen's own Done-when needs a visitor rendered as "Сова · Клубника (a0f3c952)", never a raw id alone
 * or a client-derived guess (`reference_visitor_emoji_pair_is_stored`: the pair is *stored*, read here,
 * never hashed from [visitorId]) — these two fields are what make that possible without a second round
 * trip per row.
 */
public data class VisitorRestriction(
    public val id: String,
    public val visitorId: String,
    public val kind: RestrictionKind,
    public val restrictedAt: Instant,
    public val restrictedBy: String,
    public val expiresAt: Instant?,
    public val sourceConversationId: String,
    public val liftedAt: Instant?,
    public val liftedBy: String?,
    public val emojiCreature: String? = null,
    public val emojiFood: String? = null,
)

/**
 * `26-227`: `VisitorRestrictionKind`'s own two wire spellings (`"Spam"`/`"Block"`), parsed in the
 * adapter rather than carried as a raw string — unlike [kind] on the server's own domain enum, this
 * screen draws a differently-tinted badge per kind (design doc §1.4: "Spam = accent/brand tint, Block =
 * danger tint"), which needs a closed, exhaustive vocabulary to switch over rather than an open string
 * `:app` would have to re-validate.
 *
 * An unrecognised wire value parses as [Block] — fail-safe, per the design doc's own instruction: never
 * under-report a restriction as the less dangerous kind. This can only happen if the server ever adds a
 * third kind before this app's own vocabulary catches up; it must never render as "not restricted at
 * all" in that case.
 */
public enum class RestrictionKind {
    Spam,
    Block,
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
