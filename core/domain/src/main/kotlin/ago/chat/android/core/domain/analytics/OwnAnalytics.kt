package ago.chat.android.core.domain.analytics

/**
 * `26-57`: `GET /api/v1/conversations/analytics/me`'s whole answer
 * (`Ago.Chat.Contracts.OwnOperatorAnalyticsResponse`, `ago-chat`) — this operator's own row, and nothing
 * else, for the window the server actually reported on.
 *
 * [from]/[to] are that response's own bound, always present even when the caller sent neither and the
 * handler defaulted them — never the values [ago.chat.android.analytics.AnalyticsViewModel] sent, which
 * may be blank (`docs/backlog/26-57-*.md`'s own Scope item 2: "renders the range the response reports,
 * not the one the inputs hold").
 *
 * [bucket] is zero-filled, never absent, even when this operator did nothing in the window — the screen
 * this response feeds must not look broken on a slow day. [load]/[conversion] are each independently
 * `null` when this operator held no assignment interval / recorded no outcome in the window — a real
 * "no data", not a zero (`OwnOperatorAnalyticsResponse`'s own remarks, `ago-chat`) — and
 * [ago.chat.android.analytics.AnalyticsScreen] says so separately for each, never folding the two into
 * one page-wide empty state (`docs/backlog/26-57-*.md`'s own Done-when).
 */
public data class OwnAnalytics(
    public val from: String,
    public val to: String,
    public val bucket: AnalyticsBucket,
    public val load: OperatorLoadSummary?,
    public val conversion: ConversionBucket?,
)

/**
 * `Ago.Chat.Contracts.OperatorAnalyticsBucketDto` — one bucket's worth of the numbers this report
 * exists to show. [averageFirstResponseSeconds] is `null` when nothing in this bucket ever received an
 * operator reply — never `0` and never inflated by [missedCount]'s own conversations, which are
 * excluded from the average entirely. [averageDurationSeconds] is the same idea for how long a
 * conversation takes to resolve — `null` when nothing in this bucket has closed yet.
 */
public data class AnalyticsBucket(
    public val conversationCount: Int,
    public val averageFirstResponseSeconds: Double?,
    public val averageDurationSeconds: Double?,
    public val missedCount: Int,
)

/**
 * `Ago.Chat.Contracts.OperatorLoadSummaryDto`, reduced to the fields this screen's own stat card
 * renders — `byLoad` (response time grouped by this operator's own concurrent-load bucket) has no card
 * here this wave: it is a second breakdown table on the console's own equivalent screen
 * (`myNumbersByLoadHeading`), and a phone-width card for a variable-length bucket list is exactly the
 * kind of scope this item's own Out of scope section rules out ("any chart... inventing a visual
 * encoding... is not this item's decision") — the identical reasoning, read onto a table instead of a
 * chart. [ago.chat.android.core.network.analytics.KtorOwnAnalyticsApi]'s own wire DTO simply omits the
 * field, the same "no name invented for a screen with no use for it yet" discipline
 * [ago.chat.android.core.domain.bookings.PendingBooking]'s own doc comment states for its own dropped
 * fields.
 *
 * [standardIntervals]/[additionalIntervals] stay two counts, never combined into one score
 * (`docs/design/decisions.md` §2's naming amendment, `ago-chat`) — "additional" is an interval where
 * this operator's own concurrent load, counting the interval itself, exceeded their capacity when it
 * started, and a `0` in either is a real fact, not a criticism.
 */
public data class OperatorLoadSummary(
    public val conversationsHeld: Int,
    public val intervalsHeld: Int,
    public val standardIntervals: Int,
    public val additionalIntervals: Int,
)

/**
 * `Ago.Chat.Contracts.ConversionBucketDto` — one bucket's worth of outcome counts and the rate computed
 * from them. [conversionRate] is `null` when [recordedCount] is zero — never `0` itself. [unsetCount]
 * is the load-bearing number this report exists to keep visible next to the rate: how much of this
 * bucket has no operator-recorded opinion at all, excluded from the fraction entirely rather than
 * folded into either side of it.
 *
 * <b>This is not a verified-sale count.</b> Every number here comes from what an operator chose to
 * record (`Ago.Chat.Domain.ConversionOutcome`'s own remarks, `ago-chat`) — wherever this bucket is
 * rendered, the screen must say so, not just report the rate
 * (`ago-console`'s own `conversionReportNotAVerifiedSaleBanner`, restated on this screen).
 */
public data class ConversionBucket(
    public val convertedCount: Int,
    public val notConvertedCount: Int,
    public val followUpNeededCount: Int,
    public val unsetCount: Int,
    public val recordedCount: Int,
    public val conversionRate: Double?,
)
