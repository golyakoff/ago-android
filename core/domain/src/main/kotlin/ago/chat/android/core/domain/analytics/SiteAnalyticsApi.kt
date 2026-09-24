package ago.chat.android.core.domain.analytics

/**
 * `26-70`: the port [ago.chat.android.analytics.SiteAnalyticsViewModel] (`:app`) reads through —
 * declared here and implemented in `:core:network` (`KtorSiteAnalyticsApi`), the identical split
 * [OwnAnalyticsApi]/[ago.chat.android.core.domain.conversations.ConversationsApi] already establish.
 * The dependency rule is what puts it here: a view model holding an `HttpClient` could not be tested
 * without one, and every HTTP-shaped decision (which status means what) belongs on the far side of this
 * interface, in the adapter — never spread across the screen that renders the answer. The alternative,
 * injecting a Ktor `HttpClient` into the view model, would also drag `:core:network` into `:app`'s own
 * test classpath for a screen whose entire logic is "ask, then render what came back".
 *
 * **A second port rather than a second method on [OwnAnalyticsApi].** The two endpoints answer
 * different questions for different audiences and are gated differently server-side — `.../analytics/me`
 * needs only a real operator identity, this one needs `site:configure` — so an operator who may call one
 * may legitimately not be able to call the other. Folding them into one interface would make every
 * consumer of the personal report depend on a method it must never call, and would give the two no way
 * to diverge when one of them next grows a parameter.
 */
public interface SiteAnalyticsApi {
    /**
     * `GET /api/v1/conversations/analytics`. Both bounds are optional: omitting either or both lets the
     * server apply its own thirty-day default (`GetOperatorAnalyticsForSiteHandler`'s own default
     * window, `ago-chat`) — never inferred or pre-filled on this side, since [SiteAnalytics.from]/
     * [SiteAnalytics.to] on the *response* are the only honest source for what range this actually
     * reports on. Both, when sent, are ISO-8601 with an explicit offset, the same
     * `date-and-time.md` shape every timestamp crossing this app's own wire already uses.
     */
    public suspend fun fetchSiteAnalytics(
        from: String?,
        to: String?,
    ): SiteAnalyticsResult
}

/**
 * What answering "how is this site doing" came back with. Three arms, not two — the identical shape
 * [OwnAnalyticsResult] already establishes for a call with one distinguished failure worth its own
 * branch, here `Analytics.InvalidRange` (`docs/backlog/26-70-*.md`'s own Scope item 6).
 */
public sealed interface SiteAnalyticsResult {
    public data class Loaded(
        val analytics: SiteAnalytics,
    ) : SiteAnalyticsResult

    /**
     * `Analytics.InvalidRange` — the caller's own `from`/`to` did not make sense (`from` on or after
     * `to`). A real, distinct mistake the operator can fix from the very control still on screen, which
     * is precisely why it is told apart from every other refusal rather than folded into one message
     * that would leave them guessing which input to change.
     */
    public data object InvalidRange : SiteAnalyticsResult

    /** Every other non-2xx — `Conversation.Forbidden` for an identity the server does not accept for
     * this report included — or the call never reaching a response at all. See [SiteAnalyticsFailure]
     * for the one distinction still worth keeping among those. */
    public data class Failed(
        val reason: SiteAnalyticsFailure,
    ) : SiteAnalyticsResult
}

/**
 * `26-59`'s rule, restated for this port: a network failure never reaches the operator as a raw
 * exception class name or a hostname. A separate enum from [OwnAnalyticsFailure] for the same reason
 * [SiteAnalyticsApi] is a separate interface — the two calls have no other reason to depend on one
 * another, and a shared failure enum would be a coupling neither call site asked for.
 */
public enum class SiteAnalyticsFailure {
    /** No route to the server reached at all — offline, a DNS failure, a dropped connection, a
     * timeout. Worth retrying once the network itself is back. */
    Transport,

    /** The server answered, but not usefully — any non-2xx status other than `Analytics.InvalidRange`,
     * or a `2xx` whose body was not the shape this adapter promised. */
    Unexpected,
}
