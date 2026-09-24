package ago.chat.android.core.domain.analytics

/**
 * `26-73`: `GET /api/v1/conversations/module-flow-report`'s whole answer
 * (`Ago.Chat.Contracts.BookingFlowReportResponse`, `ago-chat`) — how many conversations started the
 * calendar module's own booking flow, and how many of those flows closed, in a chosen window. The
 * smallest of the five reports `26-58` decided to port: two counts and nothing to break down by
 * (`docs/backlog/26-73-*.md`'s own Found section), the identical "a fourth port for a fourth question,
 * not a fourth method on an existing one" shape [TagBreakdownReportApi]'s own doc comment already
 * argues for its own sibling.
 *
 * [from]/[to] are the response's own bound, always present even when the caller sent neither — the
 * same "never the request's, always the response's" rule [ConversionReport.from]/`to` already states.
 *
 * **[flowsStarted]/[flowsClosed] are deliberately not `bookingsStarted`/`bookingsConfirmed`.** A closed
 * module task is not a confirmed booking — a visitor can abandon the flow, an operator can close the
 * conversation mid-step, or the flow can finish with every offered time declined
 * (`Ago.Chat.Application.Abstractions.IModuleFlowReadStore`'s own remarks, `ago-chat`, restated here
 * rather than left implicit). [BookingFunnelReportScreen][ago.chat.android.analytics.BookingFunnelReportScreen]
 * carries this caveat beside the numbers rather than renaming either field to anything booking-shaped.
 */
public data class BookingFunnelReport(
    public val from: String,
    public val to: String,
    public val flowsStarted: Int,
    public val flowsClosed: Int,
    public val previousFrom: String,
    public val previousTo: String,
    public val previousFlowsStarted: Int,
    public val previousFlowsClosed: Int,
)
