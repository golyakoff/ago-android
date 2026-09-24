package ago.chat.android.core.network.analytics

import ago.chat.android.core.domain.analytics.TagBreakdownBucketRow
import ago.chat.android.core.domain.analytics.TagBreakdownReport
import ago.chat.android.core.domain.analytics.TagBreakdownReportApi
import ago.chat.android.core.domain.analytics.TagBreakdownReportFailure
import ago.chat.android.core.domain.analytics.TagBreakdownReportResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `26-72`: the adapter behind [TagBreakdownReportApi] — the same "the whole status-code-to-meaning
 * mapping lives here, and only here" shape [KtorConversionReportApi]/[KtorSiteAnalyticsApi] already
 * establish. No Ktor type crosses back over the port: every `@Serializable` class below is `private`
 * to this file, and the only things it hands out are `:core:domain` values.
 *
 * `X-Ago-Active-Site` and the bearer token are attached by client plugins (`installAgoRestDefaults`),
 * not threaded through this method — the identical reason [KtorConversionReportApi]'s own doc comment
 * gives.
 *
 * **Never a raw exception class name or a hostname on screen** (`26-59`): [classify] answers only "no
 * network" or "something else", by [Exception] type, never by printing `this::class.simpleName` or the
 * exception's own `message`.
 */
public class KtorTagBreakdownReportApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : TagBreakdownReportApi {
    /** `GET /api/v1/conversations/tag-breakdown-report` — `from`/`to` appended only when present, the
     * same "let the server default the window" shape `ago-console`'s own `fetchTagBreakdownReport`
     * establishes. */
    override suspend fun fetchTagBreakdownReport(
        from: String?,
        to: String?,
    ): TagBreakdownReportResult {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/conversations/tag-breakdown-report") {
                    url {
                        from?.let { parameters.append("from", it) }
                        to?.let { parameters.append("to", it) }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return TagBreakdownReportResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            val type =
                try {
                    response.body<TagBreakdownProblemDetailsWireDto>().type
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    null
                }

            // `Conversation.Forbidden` deliberately does *not* get its own arm here, unlike in the
            // console — the identical reasoning [KtorConversionReportApi]'s own comment gives: this menu
            // entry is only drawn for an operator the app already believes holds `site:configure`, so a
            // refusal is either a genuinely stale permission set or a server-side disagreement, and "we
            // could not load this, try again" is the honest thing to say in both cases.
            return if (type == INVALID_RANGE_PROBLEM_TYPE) {
                TagBreakdownReportResult.InvalidRange
            } else {
                TagBreakdownReportResult.Failed(TagBreakdownReportFailure.Unexpected)
            }
        }

        return try {
            TagBreakdownReportResult.Loaded(response.body<TagBreakdownReportResponseWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty report" - the identical
            // [KtorConversionReportApi] lesson, read onto this endpoint.
            TagBreakdownReportResult.Failed(classify(failure))
        }
    }
}

/** The server's own stable problem `type` for a range whose start is not before its end — the one
 * failure this report tells apart from the rest. Named rather than inlined so the adapter and its own
 * test cannot drift on the spelling. */
private const val INVALID_RANGE_PROBLEM_TYPE = "Analytics.InvalidRange"

/** See [KtorTagBreakdownReportApi]'s own class-level doc comment. An [IOException] (no route, DNS
 * failure, a dropped socket, a timeout) is the one thing worth calling "no network"; a shape mismatch
 * on an otherwise-successful response is genuinely a different problem and falls into
 * [TagBreakdownReportFailure.Unexpected] alongside every non-2xx status this endpoint did not mark as
 * `Analytics.InvalidRange`. */
private fun classify(failure: Exception): TagBreakdownReportFailure =
    if (failure is IOException) TagBreakdownReportFailure.Transport else TagBreakdownReportFailure.Unexpected

/** RFC 7807, read for exactly the one field this call needs — the server's stable `type`, never
 * `detail`. A second declaration from [KtorConversionReportApi]'s identical private one, because both are
 * `private` to their own file on purpose: a shared wire DTO between two adapters would be a coupling
 * that outlives whichever of them changes first. */
@Serializable
private data class TagBreakdownProblemDetailsWireDto(
    val type: String? = null,
)

/** `Ago.Chat.Contracts.TagBreakdownBucketDto`. */
@Serializable
private data class TagBreakdownBucketWireDto(
    val tagId: String,
    val tagName: String,
    val conversationCount: Int,
    val convertedCount: Int,
    val notConvertedCount: Int,
    val recordedCount: Int,
    val conversionRate: Double? = null,
)

/**
 * `Ago.Chat.Contracts.TagBreakdownReportResponse`. `byTag` defaults to empty: a server with nothing to
 * report for this dimension may send `[]` or omit it, and neither is a failure. Every other field carries
 * no default, because a response missing any of them is not a thin report — it is not this endpoint's
 * shape at all, and is caught as [TagBreakdownReportFailure.Unexpected] rather than rendered as zeroes.
 */
@Serializable
private data class TagBreakdownReportResponseWireDto(
    val from: String,
    val to: String,
    val totalConversationCount: Int,
    val taggedConversationCount: Int,
    val percentageTagged: Double? = null,
    val previousFrom: String,
    val previousTo: String,
    val previousTotalConversationCount: Int,
    val previousTaggedConversationCount: Int,
    val previousPercentageTagged: Double? = null,
    val byTag: List<TagBreakdownBucketWireDto> = emptyList(),
)

private fun TagBreakdownBucketWireDto.toDomain() =
    TagBreakdownBucketRow(
        tagId = tagId,
        tagName = tagName,
        conversationCount = conversationCount,
        convertedCount = convertedCount,
        notConvertedCount = notConvertedCount,
        recordedCount = recordedCount,
        conversionRate = conversionRate,
    )

private fun TagBreakdownReportResponseWireDto.toDomain() =
    TagBreakdownReport(
        from = from,
        to = to,
        totalConversationCount = totalConversationCount,
        taggedConversationCount = taggedConversationCount,
        percentageTagged = percentageTagged,
        previousFrom = previousFrom,
        previousTo = previousTo,
        previousTotalConversationCount = previousTotalConversationCount,
        previousTaggedConversationCount = previousTaggedConversationCount,
        previousPercentageTagged = previousPercentageTagged,
        byTag = byTag.map { it.toDomain() },
    )
