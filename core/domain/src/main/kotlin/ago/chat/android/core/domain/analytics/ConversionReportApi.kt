package ago.chat.android.core.domain.analytics

/**
 * `26-71`: the port [ago.chat.android.analytics.ConversionReportViewModel] (`:app`) reads through —
 * declared here and implemented in `:core:network` (`KtorConversionReportApi`), the identical split
 * [SiteAnalyticsApi] already establishes for its sibling report. The dependency rule is what puts it
 * here — see [SiteAnalyticsApi]'s own doc comment for why a view model never holds an `HttpClient`
 * directly.
 *
 * **A third port rather than a third method on [SiteAnalyticsApi].** The two endpoints answer
 * different questions — volume/response-time/miss-rate versus recorded outcomes — built from data the
 * other never touches (`conversations.outcome`, not message authorship), the identical reasoning
 * `ConversionReportPage`'s own doc comment gives for why the console keeps this on a separate page
 * rather than a third table. Both happen to gate on `site:configure` today, which is not a reason to
 * fold them into one interface — [SiteAnalyticsApi]'s own doc comment already makes that argument for
 * its own pair, and it applies here unchanged.
 */
public interface ConversionReportApi {
    /**
     * `GET /api/v1/conversations/conversion-report`. Both bounds are optional: omitting either or
     * both lets the server apply its own thirty-day default (`GetConversionReportForSiteHandler`'s own
     * default window, `ago-chat`) — never inferred or pre-filled on this side, since
     * [ConversionReport.from]/[ConversionReport.to] on the *response* are the only honest source for
     * what range this actually reports on. Both, when sent, are ISO-8601 with an explicit offset, the
     * same `date-and-time.md` shape every timestamp crossing this app's own wire already uses.
     */
    public suspend fun fetchConversionReport(
        from: String?,
        to: String?,
    ): ConversionReportResult
}

/**
 * What answering "how did the site's recorded conversations turn out" came back with. Three arms, the
 * identical shape [SiteAnalyticsResult] already establishes for a call with one distinguished failure
 * worth its own branch, here `Analytics.InvalidRange`.
 */
public sealed interface ConversionReportResult {
    public data class Loaded(
        val report: ConversionReport,
    ) : ConversionReportResult

    /**
     * `Analytics.InvalidRange` — the caller's own `from`/`to` did not make sense (`from` on or after
     * `to`). Its own arm rather than folded into [Failed], the same reason [SiteAnalyticsResult]'s own
     * doc comment gives.
     */
    public data object InvalidRange : ConversionReportResult

    /** Every other non-2xx — see [ConversionReportFailure] for the one distinction still worth keeping
     * among those. */
    public data class Failed(
        val reason: ConversionReportFailure,
    ) : ConversionReportResult
}

/**
 * `26-59`'s rule, restated for this port: a network failure never reaches the operator as a raw
 * exception class name or a hostname. A separate enum from [SiteAnalyticsFailure] for the same reason
 * [SiteAnalyticsApi] is a separate interface from [OwnAnalyticsApi] — the three calls have no other
 * reason to depend on one another.
 */
public enum class ConversionReportFailure {
    /** No route to the server reached at all — offline, a DNS failure, a dropped connection, a
     * timeout. Worth retrying once the network itself is back. */
    Transport,

    /** The server answered, but not usefully — any non-2xx status other than `Analytics.InvalidRange`,
     * or a `2xx` whose body was not the shape this adapter promised. */
    Unexpected,
}
