package ago.chat.android.core.domain.analytics

/**
 * `26-57`: the port [ago.chat.android.analytics.AnalyticsViewModel] (`:app`) reads through — declared
 * here and implemented in `:core:network` (`KtorOwnAnalyticsApi`), the identical split
 * [ago.chat.android.core.domain.conversations.ConversationsApi]/
 * [ago.chat.android.core.domain.bookings.BookingsApi] already establish. The dependency rule is what
 * puts it here: a view model holding an `HttpClient` directly could not be tested without one, and
 * every HTTP-shaped decision (which status means what) belongs on the far side of this interface, in
 * the adapter — never spread across the screen that renders the answer.
 *
 * One call, matching this item's own one promise — Аналитика shows this operator their own numbers for
 * a chosen window (`docs/backlog/26-57-*.md`'s own Scope). [from]/[to] are both optional: omitting
 * either or both lets the server apply its own thirty-day default
 * (`GetOwnAnalyticsForOperatorHandler`'s own default window, `ago-chat`) — never inferred or pre-filled
 * on this side, since [OwnAnalytics.from]/[OwnAnalytics.to] on the *response* are the only honest source
 * for what range this actually reports on (`ago-console`'s own `OperatorAnalyticsParams`'s doc comment
 * states the identical reasoning for the sibling tenant-wide report).
 */
public interface OwnAnalyticsApi {
    /**
     * `GET /api/v1/conversations/analytics/me`, gated only by a real operator identity server-side —
     * no `site:configure`, unlike every other analytics endpoint this app's own `:core:network` will
     * eventually call (`26-70`..`26-74`). Both bounds are ISO-8601 with an explicit offset, the same
     * `date-and-time.md` shape every timestamp crossing this app's own wire already uses.
     */
    public suspend fun fetchOwnAnalytics(
        from: String?,
        to: String?,
    ): OwnAnalyticsResult
}

/**
 * What answering "what are this operator's own numbers" came back with. Three arms, not two — the
 * identical shape [ago.chat.android.core.domain.bookings.PendingBookingsResult] already establishes for
 * a call with one distinguished failure worth its own branch, here `Analytics.InvalidRange`
 * (`docs/backlog/26-57-*.md`'s own Scope item 5: "keeps its own message; every other failure renders as
 * a refusal with a retry").
 */
public sealed interface OwnAnalyticsResult {
    public data class Loaded(
        val analytics: OwnAnalytics,
    ) : OwnAnalyticsResult

    /**
     * `Analytics.InvalidRange` — the caller's own `from`/`to` did not make sense (`from` on or after
     * `to`). A real, distinct client mistake, not a server failure — the one refusal this screen tells
     * apart from the rest, the same "one deliberate branch, not laziness" reading
     * `docs/backlog/26-57-*.md`'s own Found section gives `MyNumbersPage.tsx`'s identical check.
     */
    public data object InvalidRange : OwnAnalyticsResult

    /** Every other non-2xx, or the call never reaching a response at all — see [OwnAnalyticsFailure]
     * for the one distinction still worth keeping among those. */
    public data class Failed(
        val reason: OwnAnalyticsFailure,
    ) : OwnAnalyticsResult
}

/**
 * `26-57`'s own reading of `26-59` (not landed at the time this port was written, per this item's own
 * brief): a network failure never reaches the operator as a raw exception class name or a hostname —
 * the identical [ago.chat.android.core.domain.bookings.BookingsQueueFailure] shape, restated here
 * rather than shared, since the two ports have no other reason to depend on one another and a shared
 * failure enum would be a coupling neither call site asked for.
 */
public enum class OwnAnalyticsFailure {
    /** No route to the server reached at all — offline, a DNS failure, a dropped connection, a
     * timeout. Worth retrying once the network itself is back. */
    Transport,

    /** The server answered, but not usefully — any non-2xx status other than `Analytics.InvalidRange`,
     * or a `2xx` whose body was not the shape this adapter promised. */
    Unexpected,
}
