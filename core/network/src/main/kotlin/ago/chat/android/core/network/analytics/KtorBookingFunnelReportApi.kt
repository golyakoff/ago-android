package ago.chat.android.core.network.analytics

import ago.chat.android.core.domain.analytics.BookingFunnelReport
import ago.chat.android.core.domain.analytics.BookingFunnelReportApi
import ago.chat.android.core.domain.analytics.BookingFunnelReportFailure
import ago.chat.android.core.domain.analytics.BookingFunnelReportResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `26-73`: the adapter behind [BookingFunnelReportApi] — the same "the whole status-code-to-meaning
 * mapping lives here, and only here" shape [KtorTagBreakdownReportApi]/[KtorConversionReportApi]
 * already establish. No Ktor type crosses back over the port: every `@Serializable` class below is
 * `private` to this file, and the only things it hands out are `:core:domain` values.
 *
 * `X-Ago-Active-Site` and the bearer token are attached by client plugins (`installAgoRestDefaults`),
 * not threaded through this method — the identical reason [KtorTagBreakdownReportApi]'s own doc comment
 * gives.
 *
 * **Never a raw exception class name or a hostname on screen** (`26-59`): [classify] answers only "no
 * network" or "something else", by [Exception] type, never by printing `this::class.simpleName` or the
 * exception's own `message`.
 */
public class KtorBookingFunnelReportApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : BookingFunnelReportApi {
    /** `GET /api/v1/conversations/module-flow-report` — `from`/`to` appended only when present, the
     * same "let the server default the window" shape `ago-console`'s own `fetchBookingFlowReport`
     * establishes. */
    override suspend fun fetchBookingFunnelReport(
        from: String?,
        to: String?,
    ): BookingFunnelReportResult {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/conversations/module-flow-report") {
                    url {
                        from?.let { parameters.append("from", it) }
                        to?.let { parameters.append("to", it) }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return BookingFunnelReportResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            val type =
                try {
                    response.body<BookingFunnelProblemDetailsWireDto>().type
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    null
                }

            // `Conversation.Forbidden` deliberately does *not* get its own arm here, unlike in the
            // console — the identical reasoning [KtorTagBreakdownReportApi]'s own comment gives: this
            // menu entry is only drawn for an operator the app already believes holds `site:configure`,
            // so a refusal is either a genuinely stale permission set or a server-side disagreement, and
            // "we could not load this, try again" is the honest thing to say in both cases.
            return if (type == INVALID_RANGE_PROBLEM_TYPE) {
                BookingFunnelReportResult.InvalidRange
            } else {
                BookingFunnelReportResult.Failed(BookingFunnelReportFailure.Unexpected)
            }
        }

        return try {
            BookingFunnelReportResult.Loaded(response.body<BookingFunnelReportResponseWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty report" - the identical
            // [KtorTagBreakdownReportApi] lesson, read onto this endpoint.
            BookingFunnelReportResult.Failed(classify(failure))
        }
    }
}

/** The server's own stable problem `type` for a range whose start is not before its end — **this
 * endpoint's own code, not `Analytics.InvalidRange`** (`docs/backlog/26-73-*.md`'s own Found section).
 * Named rather than inlined so the adapter and its own test cannot drift on the spelling. */
private const val INVALID_RANGE_PROBLEM_TYPE = "ModuleFlow.InvalidRange"

/** See [KtorBookingFunnelReportApi]'s own class-level doc comment. An [IOException] (no route, DNS
 * failure, a dropped socket, a timeout) is the one thing worth calling "no network"; a shape mismatch
 * on an otherwise-successful response is genuinely a different problem and falls into
 * [BookingFunnelReportFailure.Unexpected] alongside every non-2xx status this endpoint did not mark as
 * `ModuleFlow.InvalidRange`. */
private fun classify(failure: Exception): BookingFunnelReportFailure =
    if (failure is IOException) BookingFunnelReportFailure.Transport else BookingFunnelReportFailure.Unexpected

/** RFC 7807, read for exactly the one field this call needs — the server's stable `type`, never
 * `detail`. A second declaration from [KtorTagBreakdownReportApi]'s identical private one, because both
 * are `private` to their own file on purpose: a shared wire DTO between two adapters would be a
 * coupling that outlives whichever of them changes first. */
@Serializable
private data class BookingFunnelProblemDetailsWireDto(
    val type: String? = null,
)

/**
 * `Ago.Chat.Contracts.BookingFlowReportResponse`. Every field carries no default, because a response
 * missing any of them is not a thin report — it is not this endpoint's shape at all, and is caught as
 * [BookingFunnelReportFailure.Unexpected] rather than rendered as zeroes. There is no list to default to
 * empty here, unlike the other three reports' adapters — this endpoint has no dimension to break down
 * by (`docs/backlog/26-73-*.md`'s own Found section).
 */
@Serializable
private data class BookingFunnelReportResponseWireDto(
    val from: String,
    val to: String,
    val flowsStarted: Int,
    val flowsClosed: Int,
    val previousFrom: String,
    val previousTo: String,
    val previousFlowsStarted: Int,
    val previousFlowsClosed: Int,
)

private fun BookingFunnelReportResponseWireDto.toDomain() =
    BookingFunnelReport(
        from = from,
        to = to,
        flowsStarted = flowsStarted,
        flowsClosed = flowsClosed,
        previousFrom = previousFrom,
        previousTo = previousTo,
        previousFlowsStarted = previousFlowsStarted,
        previousFlowsClosed = previousFlowsClosed,
    )
