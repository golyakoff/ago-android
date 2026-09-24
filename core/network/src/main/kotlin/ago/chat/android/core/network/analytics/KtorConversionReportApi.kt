package ago.chat.android.core.network.analytics

import ago.chat.android.core.domain.analytics.ConversionBucket
import ago.chat.android.core.domain.analytics.ConversionOperatorBreakdownRow
import ago.chat.android.core.domain.analytics.ConversionReport
import ago.chat.android.core.domain.analytics.ConversionReportApi
import ago.chat.android.core.domain.analytics.ConversionReportFailure
import ago.chat.android.core.domain.analytics.ConversionReportResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `26-71`: the adapter behind [ConversionReportApi] — the same "the whole status-code-to-meaning
 * mapping lives here, and only here" shape [KtorSiteAnalyticsApi]/[KtorOwnAnalyticsApi] already
 * establish. No Ktor type crosses back over the port: every `@Serializable` class below is `private`
 * to this file, and the only things it hands out are `:core:domain` values.
 *
 * `X-Ago-Active-Site` and the bearer token are attached by client plugins (`installAgoRestDefaults`),
 * not threaded through this method — the identical reason [KtorSiteAnalyticsApi]'s own doc comment
 * gives.
 *
 * **Never a raw exception class name or a hostname on screen** (`26-59`): [classify] answers only "no
 * network" or "something else", by [Exception] type, never by printing `this::class.simpleName` or the
 * exception's own `message`.
 */
public class KtorConversionReportApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : ConversionReportApi {
    /** `GET /api/v1/conversations/conversion-report` — `from`/`to` appended only when present, the
     * same "let the server default the window" shape `ago-console`'s own `fetchConversionReport`
     * establishes. */
    override suspend fun fetchConversionReport(
        from: String?,
        to: String?,
    ): ConversionReportResult {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/conversations/conversion-report") {
                    url {
                        from?.let { parameters.append("from", it) }
                        to?.let { parameters.append("to", it) }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ConversionReportResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            val type =
                try {
                    response.body<ConversionProblemDetailsWireDto>().type
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    null
                }

            // `Conversation.Forbidden` deliberately does *not* get its own arm here, unlike in the
            // console — the identical reasoning [KtorSiteAnalyticsApi]'s own comment gives: this menu
            // entry is only drawn for an operator the app already believes holds `site:configure`, so a
            // refusal is either a genuinely stale permission set or a server-side disagreement, and "we
            // could not load this, try again" is the honest thing to say in both cases.
            return if (type == INVALID_RANGE_PROBLEM_TYPE) {
                ConversionReportResult.InvalidRange
            } else {
                ConversionReportResult.Failed(ConversionReportFailure.Unexpected)
            }
        }

        return try {
            ConversionReportResult.Loaded(response.body<ConversionReportResponseWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty report" - the identical
            // [KtorSiteAnalyticsApi] lesson, read onto this endpoint.
            ConversionReportResult.Failed(classify(failure))
        }
    }
}

/** The server's own stable problem `type` for a range whose start is not before its end — the one
 * failure this report tells apart from the rest. Named rather than inlined so the adapter and its own
 * test cannot drift on the spelling. */
private const val INVALID_RANGE_PROBLEM_TYPE = "Analytics.InvalidRange"

/** See [KtorConversionReportApi]'s own class-level doc comment. An [IOException] (no route, DNS
 * failure, a dropped socket, a timeout) is the one thing worth calling "no network"; a shape mismatch
 * on an otherwise-successful response is genuinely a different problem and falls into
 * [ConversionReportFailure.Unexpected] alongside every non-2xx status this endpoint did not mark as
 * `Analytics.InvalidRange`. */
private fun classify(failure: Exception): ConversionReportFailure =
    if (failure is IOException) ConversionReportFailure.Transport else ConversionReportFailure.Unexpected

/** RFC 7807, read for exactly the one field this call needs — the server's stable `type`, never
 * `detail`. A second declaration from [KtorSiteAnalyticsApi]'s identical private one, because both are
 * `private` to their own file on purpose: a shared wire DTO between two adapters would be a coupling
 * that outlives whichever of them changes first. */
@Serializable
private data class ConversionProblemDetailsWireDto(
    val type: String? = null,
)

/** `Ago.Chat.Contracts.ConversionBucketDto`. */
@Serializable
private data class ConversionReportBucketWireDto(
    val convertedCount: Int,
    val notConvertedCount: Int,
    val followUpNeededCount: Int,
    val unsetCount: Int,
    val recordedCount: Int,
    val conversionRate: Double? = null,
)

/** `Ago.Chat.Contracts.ConversionOperatorBucketDto`. `operatorName` defaults to `null` rather than
 * being required — genuinely absent for a row that predates the server-side column, and that absence
 * is a fact the screen renders distinctly (the truncated id) rather than a parse failure. */
@Serializable
private data class ConversionOperatorBucketWireDto(
    val operatorId: String,
    val bucket: ConversionReportBucketWireDto,
    val operatorName: String? = null,
)

/**
 * `Ago.Chat.Contracts.ConversionReportResponse`. `byOperator` defaults to empty: a server with nothing
 * to report for this dimension may send `[]` or omit it, and neither is a failure. `from`, `to`,
 * `overall`, `previousFrom`, `previousTo` and `previousOverall` carry no default, because a response
 * missing any of them is not a thin report — it is not this endpoint's shape at all, and is caught as
 * [ConversionReportFailure.Unexpected] rather than rendered as zeroes.
 */
@Serializable
private data class ConversionReportResponseWireDto(
    val from: String,
    val to: String,
    val overall: ConversionReportBucketWireDto,
    val previousFrom: String,
    val previousTo: String,
    val previousOverall: ConversionReportBucketWireDto,
    val byOperator: List<ConversionOperatorBucketWireDto> = emptyList(),
)

private fun ConversionReportBucketWireDto.toDomain() =
    ConversionBucket(
        convertedCount = convertedCount,
        notConvertedCount = notConvertedCount,
        followUpNeededCount = followUpNeededCount,
        unsetCount = unsetCount,
        recordedCount = recordedCount,
        conversionRate = conversionRate,
    )

private fun ConversionReportResponseWireDto.toDomain() =
    ConversionReport(
        from = from,
        to = to,
        overall = overall.toDomain(),
        previousFrom = previousFrom,
        previousTo = previousTo,
        previousOverall = previousOverall.toDomain(),
        byOperator =
            byOperator.map {
                ConversionOperatorBreakdownRow(
                    operatorId = it.operatorId,
                    operatorName = it.operatorName,
                    bucket = it.bucket.toDomain(),
                )
            },
    )
