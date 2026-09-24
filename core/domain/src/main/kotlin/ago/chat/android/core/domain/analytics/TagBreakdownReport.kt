package ago.chat.android.core.domain.analytics

/**
 * `26-72`: `GET /api/v1/conversations/tag-breakdown-report`'s whole answer
 * (`Ago.Chat.Contracts.TagBreakdownReportResponse`, `ago-chat`) — what these conversations are actually
 * about, by tag, and how much of the window carries a tag at all. The identical "a third port for a
 * third question, not a third method on an existing one" shape [ConversionReportApi]'s own doc comment
 * already argues for its own sibling.
 *
 * [from]/[to] are the response's own bound, always present even when the caller sent neither — the same
 * "never the request's, always the response's" rule [ConversionReport.from]/`to` already states.
 *
 * **[percentageTagged] is not decoration.** It is `null` only when [totalConversationCount] is zero —
 * never a real `0` — the identical honesty-figure convention [ConversionBucket.conversionRate] already
 * holds itself to, and the one [TagBreakdownReportScreen][ago.chat.android.analytics.TagBreakdownReportScreen]
 * renders above [byTag] every time, per `docs/backlog/26-72-*.md`'s own Scope item 4.
 *
 * **[byTag]'s own [TagBreakdownBucketRow.conversationCount] values do not sum to [totalConversationCount].**
 * A conversation holding more than one tag counts once per tag — `ITagBreakdownReadStore`'s own remarks
 * (`ago-chat`), restated here rather than left implicit.
 */
public data class TagBreakdownReport(
    public val from: String,
    public val to: String,
    public val totalConversationCount: Int,
    public val taggedConversationCount: Int,
    public val percentageTagged: Double?,
    public val previousFrom: String,
    public val previousTo: String,
    public val previousTotalConversationCount: Int,
    public val previousTaggedConversationCount: Int,
    public val previousPercentageTagged: Double?,
    public val byTag: List<TagBreakdownBucketRow>,
)

/**
 * `Ago.Chat.Contracts.TagBreakdownBucketDto` — one tag's own bucket: how many conversations it was
 * applied to in the window (not deduplicated against any other tag the same conversation might also
 * hold, [TagBreakdownReport]'s own doc comment), plus the same [ConversionBucket]-shaped conversion
 * figures computed only over this tag's own conversations. [conversionRate] is `null` when
 * [recordedCount] is zero — never `0` itself, the identical convention every rate in this app already
 * follows.
 *
 * A dedicated row type rather than reusing [ConversionOperatorBreakdownRow] or [ConversionBucket]
 * directly — the wire shape genuinely differs (a tag's own name and id, and no `followUpNeededCount`/
 * `unsetCount`, since a tag either applies or does not; there is no third "unset" state for a tag the
 * way there is for a recorded outcome).
 */
public data class TagBreakdownBucketRow(
    public val tagId: String,
    public val tagName: String,
    public val conversationCount: Int,
    public val convertedCount: Int,
    public val notConvertedCount: Int,
    public val recordedCount: Int,
    public val conversionRate: Double?,
)
