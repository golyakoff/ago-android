package ago.chat.android.core.domain.analytics

/**
 * `26-71`: `GET /api/v1/conversations/conversion-report`'s whole answer
 * (`Ago.Chat.Contracts.ConversionReportResponse`, `ago-chat`) — the site's own recorded outcomes, not
 * one operator's own ([OwnAnalytics.conversion] is that, on a different endpoint with a different
 * gate).
 *
 * [from]/[to] are the response's own bound, always present even when the caller sent neither and the
 * handler defaulted them — never the values [ago.chat.android.analytics.ConversionReportViewModel]
 * sent, the identical "the response's own range, never the request's" rule [SiteAnalytics.from]/`to`
 * already states.
 *
 * **[ConversionBucket] is reused from [OwnAnalytics], not restated.** It is the same server-side DTO
 * (`ConversionBucketDto`) reaching this app through a second endpoint, so a second Kotlin declaration
 * of it here would be two names for one wire contract.
 *
 * **[byOperator] carries no [OperatorLoadSummary].** An outcome is not a "who replied first, how long
 * did it take" fact the way [AnalyticsBucket] is — it is one attribution (`operatorId`, the
 * conversation's currently/last-assigned operator) and one bucket of counts, which is why
 * [ConversionOperatorBreakdownRow] is a genuinely smaller row than [OperatorBreakdownRow], not the
 * same row with a field dropped.
 */
public data class ConversionReport(
    public val from: String,
    public val to: String,
    public val overall: ConversionBucket,
    public val previousFrom: String,
    public val previousTo: String,
    public val previousOverall: ConversionBucket,
    public val byOperator: List<ConversionOperatorBreakdownRow>,
)

/**
 * `Ago.Chat.Contracts.ConversionOperatorBucketDto` — one operator's own recorded outcomes.
 * [operatorId] is the conversation's own `operator_id` column (currently/last-assigned) — a simpler
 * attribution than [OperatorBreakdownRow.operatorId]'s first-reply rule, since an outcome is not a
 * "who replied first" fact (`IConversionReportReadStore`'s own remarks, `ago-chat`).
 *
 * [operatorName] is `null` for a row that predates the server-side column; the screen falls back to
 * the truncated id ([ago.chat.android.core.domain.shortId]), never to a blank cell — the identical
 * fallback [OperatorBreakdownRow.operatorName] already establishes.
 */
public data class ConversionOperatorBreakdownRow(
    public val operatorId: String,
    public val operatorName: String?,
    public val bucket: ConversionBucket,
)
