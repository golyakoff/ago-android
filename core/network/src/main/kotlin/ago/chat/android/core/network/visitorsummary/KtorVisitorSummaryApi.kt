package ago.chat.android.core.network.visitorsummary

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.visitorsummary.VisitorSummary
import ago.chat.android.core.domain.visitorsummary.VisitorSummaryApi
import ago.chat.android.core.domain.visitorsummary.VisitorSummaryResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

/**
 * `26-143`: the adapter behind [VisitorSummaryApi] — the same "the whole status-code-to-meaning mapping
 * lives here, and only here" shape [ago.chat.android.core.network.contactdetails.KtorContactDetailsApi]'s
 * own doc comment states for the same panel.
 *
 * `X-Ago-Active-Site` and the bearer token are attached by client plugins (`installAgoRestDefaults`), not
 * threaded through the method here — the identical reason [ago.chat.android.core.network.conversations.KtorConversationsApi]
 * gives; the site scope for this endpoint is carried by the token/header, not by the URL, so this read
 * takes no `{siteId}` path segment (unlike the tags vocabulary read).
 */
public class KtorVisitorSummaryApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : VisitorSummaryApi {
    /** `GET /api/v1/conversations/{conversationId}/visitor-summary`. */
    override suspend fun fetchVisitorSummary(conversationId: String): VisitorSummaryResult {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/conversations/$conversationId/visitor-summary")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return VisitorSummaryResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return VisitorSummaryResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            VisitorSummaryResult.Loaded(response.body<VisitorSummaryWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty summary" - the identical
            // `KtorContactDetailsApi`/`shapeGuard.ts` lesson, read onto this endpoint. A missing
            // `conversationCount` or `visitorFirstSeenAt` field lands here as Unexpected; a *present but
            // unparseable* date does not - that is absorbed to `null` in `toDomain` below.
            VisitorSummaryResult.Failed(NetworkFailure.from(failure))
        }
    }
}

/** `Ago.Chat.Contracts.VisitorSummaryResponse` — both fields required on the wire (the server's own
 * record is non-nullable), so a body missing either one fails deserialization and is classified
 * Unexpected upstream, never silently defaulted. [visitorFirstSeenAt] is kept a raw ISO-8601 string here
 * and parsed in [toDomain]: the string→[Instant] conversion is exactly the HTTP-shaped decision this
 * adapter exists to own. */
@Serializable
private data class VisitorSummaryWireDto(
    val visitorFirstSeenAt: String,
    val conversationCount: Int,
)

/** [visitorFirstSeenAt] parses through [OffsetDateTime] (the wire carries an explicit offset, `+03:00`
 * or `Z`) and drops to the absolute [Instant] the header renders in the operator's own zone; a value
 * that will not parse becomes `null` rather than failing the whole read, per [VisitorSummary.firstSeenAt]'s
 * own contract. */
private fun VisitorSummaryWireDto.toDomain() =
    VisitorSummary(
        firstSeenAt = runCatching { OffsetDateTime.parse(visitorFirstSeenAt).toInstant() }.getOrNull(),
        conversationCount = conversationCount,
    )
