package ago.chat.android.core.domain.conversations

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-14`: the port `ConversationListViewModel` (`:app`) reads and writes through — declared here and
 * implemented in `:core:network` (`KtorConversationsApi`), the identical split `IdentityApi` already
 * establishes for the pre-session flow. The dependency rule is what puts it here: a view model that
 * held an `HttpClient` directly could not be tested without one, and every HTTP-shaped decision (which
 * status means what) belongs on the far side of this interface, in the adapter, not spread across a
 * screen's own reducer.
 *
 * Two calls, matching this item's own two real server interactions — `docs/backlog/26-14-*.md`'s Scope
 * names both: reading the queue, and claiming one waiting conversation. Nothing here is cached
 * decision-making: every call is a real network attempt, every time — [ConversationListCache] is the
 * separate port for the half that *is* allowed to answer from disk (rule 8, read from the client side:
 * the queue's own row data is fine to show stale; whether a claim succeeds is never one of those things).
 */
public interface ConversationsApi {
    /** `GET /api/v1/conversations/queue`, `RequireOperatorIdentity`-gated server-side. */
    public suspend fun fetchQueue(): QueueResult

    /**
     * `POST /api/v1/conversations/{id}/claim` — no request body, the same "the route already names the
     * conversation and the token already names the caller" shape `ago-console`'s own `claimConversation`
     * documents. `204` on success; a genuine server refusal (a non-2xx carrying an RFC 7807 `detail`) is
     * [ClaimResult.Refused], shown verbatim; everything else that kept this call from succeeding — a
     * dropped connection, a bare non-2xx with no `detail` — is [ClaimResult.Failed] (`26-59`: the two
     * used to share one field, which meant a transport failure rendered as though the server had spoken).
     * Both are shown once and never retried automatically, because from an operator's chair "that did not
     * happen, try again yourself if you still want it" is the honest thing to say either way — only the
     * exact words differ.
     */
    public suspend fun claim(conversationId: String): ClaimResult

    /**
     * `26-80`: `POST /api/v1/conversations/{id}/read` — the write that makes `operatorUnreadCount`
     * mean "unread by this operator", not "ever received" (`Conversation.MarkReadByOperator`'s own doc
     * comment, `ago-chat`). Nothing in this app called it before this item; [ThreadViewModel] is the
     * one caller, ported from `ago-console`'s own `ConversationPage.tsx`/`WorkspaceLayout.markRead`.
     *
     * [upToSequence] must be the newest message this screen can actually prove was rendered — never
     * the server's own already-known `lastSequence` for the row, which can already be ahead of what is
     * on screen; claiming it would mute a message the operator never saw. See [ThreadViewModel]'s own
     * doc comment for how this app computes that, which is not simply "the newest message loaded" the
     * way the console's version is (`ThreadScreen` does not always keep the newest message on screen
     * the way `ago-console`'s `Thread` does).
     *
     * A plain `Boolean` — `true` on a `2xx`, `false` for everything else, transport failure and a
     * genuine server refusal alike — rather than a sealed result the way [claim] gets one. [claim]
     * needs three arms because a refusal's own `detail` is shown to the operator verbatim; this call is
     * fire-and-forget by design (this item's own Scope: "a failed mark-read is logged/ignored, never
     * shown as an error"), so there is no caller left to read a reason apart from "did it land" — a
     * second sealed type nothing ever branches on would just be a shape this port invented for itself.
     */
    public suspend fun markRead(
        conversationId: String,
        upToSequence: Int,
    ): Boolean

    /**
     * `26-90`: `GET /api/v1/conversations/all` — the site administrator's own site-wide list, gated
     * server-side by `site:configure` (not `conversation:read`, which every operator holds and which
     * only ever unlocks their own queue — `GetAllConversationsForSiteHandler`'s own remarks). The
     * «Все» tab's only read.
     *
     * **Keyset-paginated, not offset-paginated**: [beforeId] is the id of the last row of the previous
     * page and `null` means "the newest page". That is also why [states] is a *request* parameter
     * rather than something the caller filters the answer with — a page of 50 narrowed on this side can
     * legitimately come back empty while more matching rows sit one page further down, with no way to
     * tell that apart from "there are no more" (that endpoint's own remarks state the same rule from
     * the server's side).
     *
     * [states] carries `Ago.Chat.Domain.ConversationState`'s own member names verbatim
     * (`"Waiting"`/`"Assigned"`/`"Closed"`) — the same unparsed wire spelling [ConversationSummary.state]
     * already travels as, so this app never owns a second vocabulary for the same set. Empty means
     * unfiltered, which the server treats identically to sending none at all.
     */
    public suspend fun fetchAllConversations(
        beforeId: String?,
        pageSize: Int,
        states: List<String>,
    ): AllConversationsResult

    /**
     * `26-90`: `POST /api/v1/conversations/{id}/erase` — no request body, the same "the route already
     * names the conversation and the token already names the caller" shape [claim] documents, gated
     * server-side by `conversation:erase`.
     *
     * **`202 Accepted`, and that is the whole point of this method having its own result type.** The
     * server stamps `erasure_requested_at` and returns immediately; the conversation is erased later, by
     * a separate job (`RequestConversationErasureHandler`). So [ErasureResult.Accepted] means "the
     * request was recorded", never "the conversation is gone" — a caller that removed the row on this
     * answer would watch it reappear on the next page and read that as a bug. What the caller does
     * instead is `26-90`'s own decision, written down in [ConversationListViewModel]: hold the row in a
     * visible "erasing" state and let it disappear only when the server stops returning it.
     */
    public suspend fun requestErasure(conversationId: String): ErasureResult
}

/** `26-90`: one page of `GET /api/v1/conversations/all`
 * (`Ago.Chat.Contracts.AllConversationsForSiteResponse`). [nextBeforeId] is `null` on the last page —
 * the server sends it only when the page it just cut was full, so "null" genuinely means "there is no
 * next page", not "ask again and find out". */
public data class AllConversationsPage(
    public val conversations: List<ConversationSummary>,
    public val nextBeforeId: String?,
)

/** What reading one page of the site-wide list came back with — the identical two arms, for the
 * identical reasons, [QueueResult] already has (that type's own doc comment: every cause of "the read
 * failed" renders as the same one banner, so the type splits no further than the value it carries). */
public sealed interface AllConversationsResult {
    public data class Loaded(
        val page: AllConversationsPage,
    ) : AllConversationsResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : AllConversationsResult
}

/** What asking for one conversation to be erased came back with — the same three arms [ClaimResult]
 * has, for the same reasons: a genuine server refusal carries its own `detail` and is shown verbatim,
 * anything that kept the call from being a genuine answer is a classification instead of a fabricated
 * sentence, and neither is ever retried automatically. */
public sealed interface ErasureResult {
    /** `202 Accepted` — recorded, **not** carried out. See [ConversationsApi.requestErasure]'s own doc
     * comment for why that distinction is the whole design of this tab's delete affordance. */
    public data object Accepted : ErasureResult

    public data class Refused(
        val detail: String,
    ) : ErasureResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : ErasureResult
}

/** What answering "what's waiting, what's mine" came back with. */
public sealed interface QueueResult {
    public data class Loaded(
        val queue: ConversationQueue,
    ) : QueueResult

    /** The call did not answer — a bad status, a dropped connection, or a `2xx` with the wrong shape.
     * `26-59`: [reason] carries [NetworkFailure]'s own three-way classification now rather than a
     * pre-rendered `String` — this port stopped choosing the operator's words the moment it stopped
     * being trustworthy enough to write an exception's own message into them. Still one arm on
     * [QueueResult] itself, not three: unlike [ago.chat.android.core.domain.identity.ProbeOutcome],
     * this screen renders every cause of "the queue read failed" the same one banner, so there is no
     * reason to split *this* type further, only the value it carries. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : QueueResult
}

/** What claiming one waiting conversation came back with. */
public sealed interface ClaimResult {
    /** `204 No Content`. The caller already knows what it asked for; a fresh [ConversationsApi.fetchQueue]
     * is how the row's own move into "Мои" is observed, the same "re-fetch rather than trust a partial
     * write result" choice this item's hub-push handling already makes. */
    public data object Claimed : ClaimResult

    /**
     * A non-2xx whose body carried a genuine RFC 7807 `detail`. Shown to the operator **as-is**, exactly
     * once, and never retried automatically — `docs/backlog/26-14-*.md`'s own Scope: "rendering the
     * server's refusal as a refusal... never retried into a success." `ago-console`'s
     * `ClaimConversationButton` makes the identical choice for the identical reason (that component's
     * own doc comment: "the loser of a race is told plainly, inline"). `26-59`: this is the *only* thing
     * `detail` is allowed to hold now — a transport failure or a bare non-2xx with no `detail` is
     * [Failed], never a `detail` this port made up itself.
     */
    public data class Refused(
        val detail: String,
    ) : ClaimResult

    /**
     * `26-59`: everything a claim attempt can fail with that is *not* a genuine server refusal — a
     * dropped connection, or a non-2xx whose body carried no `detail` to show verbatim. Rendered as a
     * generic, classification-driven sentence rather than a fabricated `"http.$status"` string, and —
     * unlike [Refused] — a failure an operator could reasonably expect to succeed on a retry, which is
     * `26-60`'s concern, not this port's.
     */
    public data class Failed(
        val reason: NetworkFailure,
    ) : ClaimResult
}
