package ago.chat.android.core.domain.analytics

/**
 * `26-70`: `GET /api/v1/conversations/analytics`'s whole answer
 * (`Ago.Chat.Contracts.OperatorAnalyticsResponse`, `ago-chat`) — the **site's** numbers, not one
 * operator's own ([OwnAnalytics] is that, and is a different endpoint with a different gate).
 *
 * [from]/[to] are the response's own bound, always present even when the caller sent neither and the
 * handler defaulted them — never the values [ago.chat.android.analytics.SiteAnalyticsViewModel] sent,
 * which may be blank (`docs/backlog/26-70-*.md`'s own Done-when: "the range it displays is the
 * response's own `from`/`to`, never the inputs").
 *
 * **[AnalyticsBucket] and [OperatorLoadSummary] are reused from [OwnAnalytics], not restated.** That is
 * not premature sharing: they are literally the same two server-side DTOs
 * (`OperatorAnalyticsBucketDto`, `OperatorLoadSummaryDto`) reaching this app through a second endpoint,
 * so a second Kotlin declaration of them would be two names for one wire contract — the thing most
 * likely to drift when the contract next changes. The four breakdown row types below are genuinely new,
 * because the site report is the only place a *breakdown* exists at all.
 *
 * ## Why four arrays rather than one
 *
 * [byChannel], [byOperator], [byReferrer] and [byCampaign] are four independent groupings of the same
 * window, not one table with four key columns (`IOperatorAnalyticsReadStore`'s own remarks, `ago-chat`).
 * A conversation appears in all four; the counts do not sum across them. Each therefore says for itself
 * whether it has rows — there is no single page-wide empty state standing in for four independent ones
 * (`docs/backlog/26-70-*.md`'s own Done-when).
 *
 * ## Why only [overall] carries a comparison
 *
 * [previousOverall] is the immediately preceding window of equal length
 * (`Ago.Chat.Application.Abstractions.PrecedingPeriod`, `ago-chat`). The server deliberately computes no
 * previous-period breakdown per channel/operator/referrer/campaign, so neither does this model —
 * inventing one on this side would be a number nobody measured.
 */
public data class SiteAnalytics(
    public val from: String,
    public val to: String,
    public val overall: AnalyticsBucket,
    public val previousFrom: String,
    public val previousTo: String,
    public val previousOverall: AnalyticsBucket,
    public val byChannel: List<ChannelBreakdownRow>,
    public val byOperator: List<OperatorBreakdownRow>,
    public val byReferrer: List<ReferrerBreakdownRow>,
    public val byCampaign: List<CampaignBreakdownRow>,
)

/**
 * `Ago.Chat.Contracts.OperatorAnalyticsChannelBucketDto` — one channel's bucket. [channel] is
 * `Ago.Chat.Domain.ChannelKind`'s own member name (`"Max"`/`"Sms"`/`"Telegram"`/`"WhatsApp"`) or the
 * literal `"Widget"` for a visitor with no external channel identity at all.
 *
 * **Kept as the raw wire value here, never a translated label.** The mapping to a word an operator
 * reads is a rendering decision and lives in `:app`
 * ([ago.chat.android.analytics.SiteAnalyticsScreen]'s own `channelLabel`), which also means a channel
 * this app has not been taught about yet renders as itself rather than disappearing — the identical
 * fallback `ago-console`'s own `channelLabel` takes.
 */
public data class ChannelBreakdownRow(
    public val channel: String,
    public val bucket: AnalyticsBucket,
)

/**
 * `Ago.Chat.Contracts.OperatorAnalyticsOperatorBucketDto` — one operator's bucket. [operatorId] is
 * whoever this window's numbers attribute to: whoever replied first, or (only for a conversation nobody
 * ever replied to) whoever was holding it when it closed unanswered — never whoever it was later
 * transferred to.
 *
 * [operatorName] is `null` for a row that predates the server-side column; the screen falls back to the
 * truncated id ([ago.chat.android.core.domain.shortId]), never to a blank cell.
 *
 * **[load] is `null` as a real fact, and that fact must survive to the screen.** It is a second,
 * independent view of the same operator — "held", from `conversation_assignments`, rather than
 * "attributed to", from message authorship ([bucket]) — and is absent when this operator has no
 * assignment interval starting in the window at all. That is *not* a zero, and it is *not* the
 * "nothing to average" an [AnalyticsBucket]'s own `null` average means: three distinct renderings, one
 * of the two things `docs/backlog/26-70-*.md` calls out by name.
 */
public data class OperatorBreakdownRow(
    public val operatorId: String,
    public val operatorName: String?,
    public val bucket: AnalyticsBucket,
    public val load: OperatorLoadSummary?,
)

/**
 * `Ago.Chat.Contracts.OperatorAnalyticsReferrerBucketDto` — one referring host's bucket, or the literal
 * `"Direct"` for a visitor who carried no `document.referrer` at all.
 *
 * **This is what the browser reported, never a verified fact** — a client-supplied header, never
 * confirmed against a second source. The screen says so, above this table and
 * [CampaignBreakdownRow]'s, in its own words rather than leaving a reader to infer it
 * (`docs/backlog/26-70-*.md`'s own Scope item 5).
 */
public data class ReferrerBreakdownRow(
    public val referrerHost: String,
    public val bucket: AnalyticsBucket,
)

/**
 * `Ago.Chat.Contracts.OperatorAnalyticsCampaignBucketDto` — one `utm_campaign` value's bucket, exactly
 * as captured from the landing page's own URL. There is never a "no campaign" row: a conversation with
 * no campaign tag has no bucket here at all, rather than being folded into a manufactured one. Equally
 * unverified — see [ReferrerBreakdownRow].
 */
public data class CampaignBreakdownRow(
    public val utmCampaign: String,
    public val bucket: AnalyticsBucket,
)
