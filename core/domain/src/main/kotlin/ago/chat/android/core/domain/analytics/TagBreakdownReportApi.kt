package ago.chat.android.core.domain.analytics

/**
 * `26-72`: the port [ago.chat.android.analytics.TagBreakdownReportViewModel] (`:app`) reads through —
 * declared here and implemented in `:core:network` (`KtorTagBreakdownReportApi`), the identical split
 * [ConversionReportApi] already establishes for its sibling report. The dependency rule is what puts it
 * here — see [SiteAnalyticsApi]'s own doc comment for why a view model never holds an `HttpClient`
 * directly.
 *
 * A fourth port rather than a fourth method on [SiteAnalyticsApi] or [ConversionReportApi] — this
 * endpoint answers a third, structurally different question (what these conversations are about, not
 * their volume or their recorded outcome), built from data ([Ago.Chat.Domain] tag assignments) neither
 * of the other two touches. [ConversionReportApi]'s own doc comment already makes this argument for its
 * own pair, and it applies here unchanged.
 */
public interface TagBreakdownReportApi {
    /**
     * `GET /api/v1/conversations/tag-breakdown-report`. Both bounds are optional: omitting either or
     * both lets the server apply its own thirty-day default (`GetTagBreakdownReportForSiteHandler`'s own
     * default window, `ago-chat`) — never inferred or pre-filled on this side, since
     * [TagBreakdownReport.from]/[TagBreakdownReport.to] on the *response* are the only honest source for
     * what range this actually reports on. Both, when sent, are ISO-8601 with an explicit offset, the
     * same `date-and-time.md` shape every timestamp crossing this app's own wire already uses.
     */
    public suspend fun fetchTagBreakdownReport(
        from: String?,
        to: String?,
    ): TagBreakdownReportResult
}

/**
 * What answering "what are these conversations actually about, by tag" came back with. Three arms, the
 * identical shape [ConversionReportResult] already establishes for a call with one distinguished failure
 * worth its own branch, here `Analytics.InvalidRange`.
 */
public sealed interface TagBreakdownReportResult {
    public data class Loaded(
        val report: TagBreakdownReport,
    ) : TagBreakdownReportResult

    /**
     * `Analytics.InvalidRange` — the caller's own `from`/`to` did not make sense (`from` on or after
     * `to`). Its own arm rather than folded into [Failed], the same reason [ConversionReportResult]'s
     * own doc comment gives.
     */
    public data object InvalidRange : TagBreakdownReportResult

    /** Every other non-2xx — see [TagBreakdownReportFailure] for the one distinction still worth keeping
     * among those. */
    public data class Failed(
        val reason: TagBreakdownReportFailure,
    ) : TagBreakdownReportResult
}

/**
 * `26-59`'s rule, restated for this port: a network failure never reaches the operator as a raw
 * exception class name or a hostname. A separate enum from [ConversionReportFailure]/[SiteAnalyticsFailure]
 * for the same reason [ConversionReportApi] is a separate interface from [SiteAnalyticsApi] — the three
 * calls have no other reason to depend on one another.
 */
public enum class TagBreakdownReportFailure {
    /** No route to the server reached at all — offline, a DNS failure, a dropped connection, a
     * timeout. Worth retrying once the network itself is back. */
    Transport,

    /** The server answered, but not usefully — any non-2xx status other than `Analytics.InvalidRange`,
     * or a `2xx` whose body was not the shape this adapter promised. */
    Unexpected,
}
