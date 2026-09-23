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
