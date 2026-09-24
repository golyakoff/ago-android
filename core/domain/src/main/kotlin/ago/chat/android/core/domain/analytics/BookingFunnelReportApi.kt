package ago.chat.android.core.domain.analytics

/**
 * `26-73`: the port [ago.chat.android.analytics.BookingFunnelReportViewModel] (`:app`) reads through —
 * declared here and implemented in `:core:network` (`KtorBookingFunnelReportApi`), the identical split
 * [TagBreakdownReportApi] already establishes for its sibling report. The dependency rule is what puts
 * it here — see [SiteAnalyticsApi]'s own doc comment for why a view model never holds an `HttpClient`
 * directly.
 *
 * A fifth port rather than a fifth method on any of [SiteAnalyticsApi]/[ConversionReportApi]/
 * [TagBreakdownReportApi] — this endpoint answers a fourth, structurally different question (how many
 * conversations started the calendar module's own flow, and how many of those closed), built from
 * `module_tasks`, data none of the other three touches. [TagBreakdownReportApi]'s own doc comment
 * already makes this argument for its own pair, and it applies here unchanged.
 */
public interface BookingFunnelReportApi {
    /**
     * `GET /api/v1/conversations/module-flow-report`. Both bounds are optional: omitting either or
     * both lets the server apply its own default window (`GetModuleFlowReportForSiteHandler`'s own
     * default, `ago-chat`) — never inferred or pre-filled on this side, since
     * [BookingFunnelReport.from]/[BookingFunnelReport.to] on the *response* are the only honest source
     * for what range this actually reports on. Both, when sent, are ISO-8601 with an explicit offset,
     * the same `date-and-time.md` shape every timestamp crossing this app's own wire already uses.
     */
    public suspend fun fetchBookingFunnelReport(
        from: String?,
        to: String?,
    ): BookingFunnelReportResult
}

/**
 * What answering "how many conversations started the booking flow, and how many of those closed" came
 * back with. Three arms, the identical shape [TagBreakdownReportResult] already establishes for a call
 * with one distinguished failure worth its own branch — here `ModuleFlow.InvalidRange`, **its own
 * problem type, not `Analytics.InvalidRange`** (`docs/backlog/26-73-*.md`'s own Found section): reusing
 * the other reports' code here would swallow the branch this report's own endpoint actually returns.
 */
public sealed interface BookingFunnelReportResult {
    public data class Loaded(
        val report: BookingFunnelReport,
    ) : BookingFunnelReportResult

    /**
     * `ModuleFlow.InvalidRange` — the caller's own `from`/`to` did not make sense (`from` on or after
     * `to`). Its own arm rather than folded into [Failed], the same reason [TagBreakdownReportResult]'s
     * own doc comment gives for `Analytics.InvalidRange` — and its own problem type, since this
     * endpoint's invalid-range code is not that one.
     */
    public data object InvalidRange : BookingFunnelReportResult

    /** Every other non-2xx — see [BookingFunnelReportFailure] for the one distinction still worth
     * keeping among those. */
    public data class Failed(
        val reason: BookingFunnelReportFailure,
    ) : BookingFunnelReportResult
}

/**
 * `26-59`'s rule, restated for this port: a network failure never reaches the operator as a raw
 * exception class name or a hostname. A separate enum from [TagBreakdownReportFailure]/
 * [ConversionReportFailure]/[SiteAnalyticsFailure] for the same reason [TagBreakdownReportApi] is a
 * separate interface from the others — the four calls have no other reason to depend on one another.
 */
public enum class BookingFunnelReportFailure {
    /** No route to the server reached at all — offline, a DNS failure, a dropped connection, a
     * timeout. Worth retrying once the network itself is back. */
    Transport,

    /** The server answered, but not usefully — any non-2xx status other than `ModuleFlow.InvalidRange`,
     * or a `2xx` whose body was not the shape this adapter promised. */
    Unexpected,
}
