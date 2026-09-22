package ago.chat.android.core.domain.conversations

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
     * documents. `204` on success; every other status is a [ClaimResult.Refused] carrying whatever detail
     * the server's own RFC 7807 body gave, or a synthesised one when the response carried none — a
     * transport failure and a genuine contention loss are rendered identically to the caller (a message,
     * never retried), because from an operator's chair both mean the same thing: "that did not happen,
     * try again yourself if you still want it."
     */
    public suspend fun claim(conversationId: String): ClaimResult
}

/** What answering "what's waiting, what's mine" came back with. */
public sealed interface QueueResult {
    public data class Loaded(
        val queue: ConversationQueue,
    ) : QueueResult

    /** The call did not answer — a bad status, a dropped connection, or a `2xx` with the wrong shape.
     * One arm rather than three: unlike [ago.chat.android.core.domain.identity.ProbeOutcome], nothing
     * here is a policy decision to read differently by cause, so there is no reason to split it further. */
    public data class Failed(
        val message: String,
    ) : QueueResult
}

/** What claiming one waiting conversation came back with. */
public sealed interface ClaimResult {
    /** `204 No Content`. The caller already knows what it asked for; a fresh [ConversationsApi.fetchQueue]
     * is how the row's own move into "Мои" is observed, the same "re-fetch rather than trust a partial
     * write result" choice this item's hub-push handling already makes. */
    public data object Claimed : ClaimResult

    /**
     * Any non-2xx. `detail` is shown to the operator **as-is**, exactly once, and is never retried
     * automatically — `docs/backlog/26-14-*.md`'s own Scope: "rendering the server's refusal as a
     * refusal... never retried into a success." `ago-console`'s `ClaimConversationButton` makes the
     * identical choice for the identical reason (that component's own doc comment: "the loser of a race
     * is told plainly, inline").
     */
    public data class Refused(
        val detail: String,
    ) : ClaimResult
}
