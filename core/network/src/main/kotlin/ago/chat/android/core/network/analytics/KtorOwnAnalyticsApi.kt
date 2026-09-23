package ago.chat.android.core.network.analytics

import ago.chat.android.core.domain.analytics.AnalyticsBucket
import ago.chat.android.core.domain.analytics.ConversionBucket
import ago.chat.android.core.domain.analytics.OperatorLoadSummary
import ago.chat.android.core.domain.analytics.OwnAnalytics
import ago.chat.android.core.domain.analytics.OwnAnalyticsApi
import ago.chat.android.core.domain.analytics.OwnAnalyticsFailure
import ago.chat.android.core.domain.analytics.OwnAnalyticsResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `26-57`: the adapter behind [OwnAnalyticsApi] — the same "the whole status-code-to-meaning mapping
 * lives here, and only here" shape `KtorConversationsApi`/`KtorBookingsApi` already establish.
 *
 * `X-Ago-Active-Site` and the bearer token are attached by client plugins (`installAgoRestDefaults`),
 * not threaded through this method either — the identical reason those two classes give.
 *
 * **Never a raw exception class name or a hostname on screen** — the same `26-59`-shaped discipline
 * [ago.chat.android.core.network.bookings.KtorBookingsApi]'s own doc comment states, written into this
 * adapter from birth rather than added as a later `describe()` copy: [classify] answers only "no
 * network" or "something else", by [Exception] type, never by printing `this::class.simpleName` or the
 * exception's own `message`.
 */
public class KtorOwnAnalyticsApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : OwnAnalyticsApi {
    /** `GET /api/v1/conversations/analytics/me` — `from`/`to` appended only when present, the same
     * "let the server default the window" shape `ago-console`'s own `fetchOwnAnalytics` establishes. */
    override suspend fun fetchOwnAnalytics(
        from: String?,
        to: String?,
    ): OwnAnalyticsResult {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/conversations/analytics/me") {
                    url {
                        from?.let { parameters.append("from", it) }
                        to?.let { parameters.append("to", it) }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return OwnAnalyticsResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            val type =
                try {
                    response.body<ProblemDetailsWireDto>().type
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    null
                }

            return if (type == "Analytics.InvalidRange") {
                OwnAnalyticsResult.InvalidRange
            } else {
                OwnAnalyticsResult.Failed(OwnAnalyticsFailure.Unexpected)
            }
        }

        return try {
            OwnAnalyticsResult.Loaded(response.body<OwnOperatorAnalyticsResponseWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty report" - the identical
            // `KtorConversationsApi`/`KtorBookingsApi` lesson, read onto this endpoint.
            OwnAnalyticsResult.Failed(classify(failure))
        }
    }
}

/** See this file's own class-level doc comment for why this exists instead of a `describe()` copy. An
 * [IOException] (no route, DNS failure, a dropped socket, a timeout) is the one thing worth calling
 * "no network"; a shape mismatch on an otherwise-successful response is genuinely a different problem
 * and falls into [OwnAnalyticsFailure.Unexpected] alongside every non-2xx status this endpoint did not
 * mark as `Analytics.InvalidRange`. */
private fun classify(failure: Exception): OwnAnalyticsFailure =
    if (failure is IOException) OwnAnalyticsFailure.Transport else OwnAnalyticsFailure.Unexpected

/** RFC 7807, read for exactly the one field this call needs — the server's stable `type`, never
 * `detail` (`ago-console`'s own `problemDetailsFrom`: `type` is the code a caller branches on, `detail`
 * is prose meant for direct display, and this screen never displays the server's own English/Russian
 * mix verbatim for a failure other than the one it already has its own wording for). */
@Serializable
private data class ProblemDetailsWireDto(
    val type: String? = null,
)

/** `Ago.Chat.Contracts.OperatorAnalyticsBucketDto`. */
@Serializable
private data class AnalyticsBucketWireDto(
    val conversationCount: Int,
    val averageFirstResponseSeconds: Double? = null,
    val averageDurationSeconds: Double? = null,
    val missedCount: Int,
)

/**
 * `Ago.Chat.Contracts.OperatorLoadSummaryDto`, reduced to the fields [OperatorLoadSummary] carries —
 * `byLoad` is deliberately absent; see that class's own doc comment for why this wave has no card for
 * it, the same `ignoreUnknownKeys` (`AgoHttpClient.kt`'s own `agoJson`) shape
 * [ago.chat.android.core.network.bookings.KtorBookingsApi]'s own `PendingBookingWireDto` already relies
 * on to drop fields this app has no screen for yet.
 */
@Serializable
private data class OperatorLoadSummaryWireDto(
    val conversationsHeld: Int,
    val intervalsHeld: Int,
    val standardIntervals: Int,
    val additionalIntervals: Int,
)

/** `Ago.Chat.Contracts.ConversionBucketDto`. */
@Serializable
private data class ConversionBucketWireDto(
    val convertedCount: Int,
    val notConvertedCount: Int,
    val followUpNeededCount: Int,
    val unsetCount: Int,
    val recordedCount: Int,
    val conversionRate: Double? = null,
)

/** `Ago.Chat.Contracts.OwnOperatorAnalyticsResponse`. */
@Serializable
private data class OwnOperatorAnalyticsResponseWireDto(
    val from: String,
    val to: String,
    val bucket: AnalyticsBucketWireDto,
    val load: OperatorLoadSummaryWireDto? = null,
    val conversion: ConversionBucketWireDto? = null,
)

private fun AnalyticsBucketWireDto.toDomain() =
    AnalyticsBucket(
        conversationCount = conversationCount,
        averageFirstResponseSeconds = averageFirstResponseSeconds,
        averageDurationSeconds = averageDurationSeconds,
        missedCount = missedCount,
    )

private fun OperatorLoadSummaryWireDto.toDomain() =
    OperatorLoadSummary(
        conversationsHeld = conversationsHeld,
        intervalsHeld = intervalsHeld,
        standardIntervals = standardIntervals,
        additionalIntervals = additionalIntervals,
    )

private fun ConversionBucketWireDto.toDomain() =
    ConversionBucket(
        convertedCount = convertedCount,
        notConvertedCount = notConvertedCount,
        followUpNeededCount = followUpNeededCount,
        unsetCount = unsetCount,
        recordedCount = recordedCount,
        conversionRate = conversionRate,
    )

private fun OwnOperatorAnalyticsResponseWireDto.toDomain() =
    OwnAnalytics(
        from = from,
        to = to,
        bucket = bucket.toDomain(),
        load = load?.toDomain(),
        conversion = conversion?.toDomain(),
    )
