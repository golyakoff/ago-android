package ago.chat.android.core.network.analytics

import ago.chat.android.core.domain.analytics.AnalyticsBucket
import ago.chat.android.core.domain.analytics.CampaignBreakdownRow
import ago.chat.android.core.domain.analytics.ChannelBreakdownRow
import ago.chat.android.core.domain.analytics.OperatorBreakdownRow
import ago.chat.android.core.domain.analytics.OperatorLoadSummary
import ago.chat.android.core.domain.analytics.ReferrerBreakdownRow
import ago.chat.android.core.domain.analytics.SiteAnalytics
import ago.chat.android.core.domain.analytics.SiteAnalyticsApi
import ago.chat.android.core.domain.analytics.SiteAnalyticsFailure
import ago.chat.android.core.domain.analytics.SiteAnalyticsResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `26-70`: the adapter behind [SiteAnalyticsApi] — the same "the whole status-code-to-meaning mapping
 * lives here, and only here" shape [KtorOwnAnalyticsApi] and
 * [ago.chat.android.core.network.conversations.KtorConversationsApi] already establish. No Ktor type
 * crosses back over the port: every `@Serializable` class below is `private` to this file, and the only
 * things it hands out are `:core:domain` values.
 *
 * `X-Ago-Active-Site` and the bearer token are attached by client plugins (`installAgoRestDefaults`),
 * not threaded through this method — the identical reason those two classes give. Which site's numbers
 * this returns is therefore the app's own active-site selection, never a parameter a screen could get
 * wrong.
 *
 * **Never a raw exception class name or a hostname on screen** (`26-59`): [classify] answers only "no
 * network" or "something else", by [Exception] type, never by printing `this::class.simpleName` or the
 * exception's own `message`.
 */
public class KtorSiteAnalyticsApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : SiteAnalyticsApi {
    /** `GET /api/v1/conversations/analytics` — `from`/`to` appended only when present, the same "let
     * the server default the window" shape `ago-console`'s own `fetchOperatorAnalytics` establishes. */
    override suspend fun fetchSiteAnalytics(
        from: String?,
        to: String?,
    ): SiteAnalyticsResult {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/conversations/analytics") {
                    url {
                        from?.let { parameters.append("from", it) }
                        to?.let { parameters.append("to", it) }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return SiteAnalyticsResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            val type =
                try {
                    response.body<SiteProblemDetailsWireDto>().type
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    null
                }

            // `Conversation.Forbidden` deliberately does *not* get its own arm here, unlike in the
            // console. This menu entry is only drawn for an operator the app already believes holds
            // `site:configure` (`visibleAnalyticsReports`), so a refusal is either a genuinely stale
            // permission set or a server-side disagreement - in both cases "we could not load this,
            // try again" is the honest thing to say, and a bespoke "you are not allowed" message on a
            // screen the operator was shown a route to would be worse, not better.
            return if (type == INVALID_RANGE_PROBLEM_TYPE) {
                SiteAnalyticsResult.InvalidRange
            } else {
                SiteAnalyticsResult.Failed(SiteAnalyticsFailure.Unexpected)
            }
        }

        return try {
            SiteAnalyticsResult.Loaded(response.body<SiteAnalyticsResponseWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty report" - the identical
            // [KtorOwnAnalyticsApi] lesson, read onto this endpoint.
            SiteAnalyticsResult.Failed(classify(failure))
        }
    }
}

/** The server's own stable problem `type` for a range whose start is not before its end — the one
 * failure this report tells apart from the rest. Named rather than inlined so the adapter and its own
 * test cannot drift on the spelling. */
private const val INVALID_RANGE_PROBLEM_TYPE = "Analytics.InvalidRange"

/** See [KtorSiteAnalyticsApi]'s own class-level doc comment. An [IOException] (no route, DNS failure, a
 * dropped socket, a timeout) is the one thing worth calling "no network"; a shape mismatch on an
 * otherwise-successful response is genuinely a different problem and falls into
 * [SiteAnalyticsFailure.Unexpected] alongside every non-2xx status this endpoint did not mark as
 * `Analytics.InvalidRange`. */
private fun classify(failure: Exception): SiteAnalyticsFailure =
    if (failure is IOException) SiteAnalyticsFailure.Transport else SiteAnalyticsFailure.Unexpected

/** RFC 7807, read for exactly the one field this call needs — the server's stable `type`, never
 * `detail`, which is prose meant for direct display and would put the server's own wording on a screen
 * that has its own. A second declaration from [KtorOwnAnalyticsApi]'s identical private one, because
 * both are `private` to their own file on purpose: a shared wire DTO between two adapters would be a
 * coupling that outlives whichever of them changes first. */
@Serializable
private data class SiteProblemDetailsWireDto(
    val type: String? = null,
)

/** `Ago.Chat.Contracts.OperatorAnalyticsBucketDto`. */
@Serializable
private data class SiteAnalyticsBucketWireDto(
    val conversationCount: Int,
    val averageFirstResponseSeconds: Double? = null,
    val averageDurationSeconds: Double? = null,
    val missedCount: Int,
)

/**
 * `Ago.Chat.Contracts.OperatorLoadSummaryDto`, reduced to the fields [OperatorLoadSummary] carries —
 * `byLoad` is deliberately absent. `docs/backlog/26-70-*.md`'s own Out of scope rules the operator ×
 * load-bucket cross-tab out for this wave ("a different pair of dimensions that nobody has asked to
 * read on a phone"), and `agoJson`'s own `ignoreUnknownKeys` drops it silently rather than failing — the
 * same "no name invented for a screen with no use for it yet" discipline
 * [ago.chat.android.core.network.bookings.KtorBookingsApi]'s own wire DTOs already follow.
 */
@Serializable
private data class SiteOperatorLoadSummaryWireDto(
    val conversationsHeld: Int,
    val intervalsHeld: Int,
    val standardIntervals: Int,
    val additionalIntervals: Int,
)

/** `Ago.Chat.Contracts.OperatorAnalyticsChannelBucketDto`. */
@Serializable
private data class ChannelBucketWireDto(
    val channel: String,
    val bucket: SiteAnalyticsBucketWireDto,
)

/** `Ago.Chat.Contracts.OperatorAnalyticsOperatorBucketDto`. Both `operatorName` and `load` default to
 * `null` rather than being required — each is genuinely absent for a real row (one predating the
 * server-side column; one for an operator with no assignment interval in the window), and that absence
 * is a fact the screen renders distinctly rather than a parse failure. */
@Serializable
private data class OperatorBucketWireDto(
    val operatorId: String,
    val bucket: SiteAnalyticsBucketWireDto,
    val operatorName: String? = null,
    val load: SiteOperatorLoadSummaryWireDto? = null,
)

/** `Ago.Chat.Contracts.OperatorAnalyticsReferrerBucketDto`. */
@Serializable
private data class ReferrerBucketWireDto(
    val referrerHost: String,
    val bucket: SiteAnalyticsBucketWireDto,
)

/** `Ago.Chat.Contracts.OperatorAnalyticsCampaignBucketDto`. */
@Serializable
private data class CampaignBucketWireDto(
    val utmCampaign: String,
    val bucket: SiteAnalyticsBucketWireDto,
)

/**
 * `Ago.Chat.Contracts.OperatorAnalyticsResponse`. The four breakdown arrays each default to empty:
 * a server that has nothing to report for one dimension may send `[]` or omit it, and neither is a
 * failure — each table says for itself that it is empty (`SiteAnalytics`'s own doc comment). `from`,
 * `to`, `overall`, `previousFrom`, `previousTo` and `previousOverall` carry no default, because a
 * response missing any of them is not a thin report — it is not this endpoint's shape at all, and is
 * caught as [SiteAnalyticsFailure.Unexpected] rather than rendered as zeroes.
 */
@Serializable
private data class SiteAnalyticsResponseWireDto(
    val from: String,
    val to: String,
    val overall: SiteAnalyticsBucketWireDto,
    val previousFrom: String,
    val previousTo: String,
    val previousOverall: SiteAnalyticsBucketWireDto,
    val byChannel: List<ChannelBucketWireDto> = emptyList(),
    val byOperator: List<OperatorBucketWireDto> = emptyList(),
    val byReferrer: List<ReferrerBucketWireDto> = emptyList(),
    val byCampaign: List<CampaignBucketWireDto> = emptyList(),
)

private fun SiteAnalyticsBucketWireDto.toDomain() =
    AnalyticsBucket(
        conversationCount = conversationCount,
        averageFirstResponseSeconds = averageFirstResponseSeconds,
        averageDurationSeconds = averageDurationSeconds,
        missedCount = missedCount,
    )

private fun SiteOperatorLoadSummaryWireDto.toDomain() =
    OperatorLoadSummary(
        conversationsHeld = conversationsHeld,
        intervalsHeld = intervalsHeld,
        standardIntervals = standardIntervals,
        additionalIntervals = additionalIntervals,
    )

private fun SiteAnalyticsResponseWireDto.toDomain() =
    SiteAnalytics(
        from = from,
        to = to,
        overall = overall.toDomain(),
        previousFrom = previousFrom,
        previousTo = previousTo,
        previousOverall = previousOverall.toDomain(),
        byChannel = byChannel.map { ChannelBreakdownRow(channel = it.channel, bucket = it.bucket.toDomain()) },
        byOperator =
            byOperator.map {
                OperatorBreakdownRow(
                    operatorId = it.operatorId,
                    operatorName = it.operatorName,
                    bucket = it.bucket.toDomain(),
                    load = it.load?.toDomain(),
                )
            },
        byReferrer = byReferrer.map { ReferrerBreakdownRow(referrerHost = it.referrerHost, bucket = it.bucket.toDomain()) },
        byCampaign = byCampaign.map { CampaignBreakdownRow(utmCampaign = it.utmCampaign, bucket = it.bucket.toDomain()) },
    )
