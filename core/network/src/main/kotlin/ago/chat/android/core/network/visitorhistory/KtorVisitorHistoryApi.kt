package ago.chat.android.core.network.visitorhistory

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryApi
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryConversation
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryPage
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

/**
 * `26-144`: the adapter behind [VisitorHistoryApi] — the same "the whole status-code-to-meaning mapping
 * lives here, and only here" shape [ago.chat.android.core.network.visitorsummary.KtorVisitorSummaryApi]'s
 * own doc comment states for the same panel.
 *
 * `X-Ago-Active-Site` and the bearer token are attached by client plugins (`installAgoRestDefaults`), not
 * threaded through the method here — the identical reason
 * [ago.chat.android.core.network.conversations.KtorConversationsApi] gives; the site scope for this
 * endpoint is carried by the token/header, not by the URL, so this read takes no `{siteId}` path segment.
 * The keyset cursor and page size *are* query parameters, appended the identical way
 * [ago.chat.android.core.network.conversations.KtorConversationsApi.fetchAllConversations] appends its
 * own — `beforeId` sent only when non-null, so its absence means "the newest page".
 */
public class KtorVisitorHistoryApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : VisitorHistoryApi {
    /** `GET /api/v1/conversations/{conversationId}/visitor-history?beforeId=…&pageSize=…`. */
    override suspend fun fetchVisitorHistory(
        conversationId: String,
        beforeId: String?,
        pageSize: Int,
    ): VisitorHistoryResult {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/conversations/$conversationId/visitor-history") {
                    parameter("pageSize", pageSize)
                    beforeId?.let { parameter("beforeId", it) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return VisitorHistoryResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return VisitorHistoryResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            VisitorHistoryResult.Loaded(response.body<VisitorHistoryResponseWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "no past dialogs" — the identical
            // `KtorVisitorSummaryApi`/`shapeGuard.ts` lesson, read onto this endpoint: a dropped array
            // here must not look like a visitor who has never talked to this shop before.
            VisitorHistoryResult.Failed(NetworkFailure.from(failure))
        }
    }
}

/** `Ago.Chat.Contracts.VisitorHistoryResponse` — the keyset page plus its cursor. [conversations] carries
 * **no default**, so a `200` body missing the array entirely fails deserialization and is classified
 * Unexpected upstream rather than passed off as "no past dialogs" — the identical shape-guard the sibling
 * `KtorVisitorSummaryApi` applies to its own required fields; a genuine empty page arrives as an explicit
 * `[]`. [nextBeforeId] is a `Guid?` on the wire (the server always sends the key, `null` on the last
 * page), kept a `String?` here the same way every id this app carries is a string. */
@Serializable
private data class VisitorHistoryResponseWireDto(
    val conversations: List<VisitorHistoryConversationWireDto>,
    val nextBeforeId: String? = null,
)

/** `Ago.Chat.Contracts.VisitorHistoryConversationDto`. [conversationId]/[state]/[startedAt] are
 * non-nullable on the server's record, so a body missing any one of them fails deserialization and is
 * classified Unexpected upstream, never silently defaulted; the nullable-on-the-wire members
 * ([closedAt]/[previewBody]/[previewAuthorKind]/[previewCreatedAt]) default to `null`. Dates are kept as
 * raw ISO-8601 strings here and parsed in [toDomain] — the string→[Instant] conversion is exactly the
 * HTTP-shaped decision this adapter exists to own. */
@Serializable
private data class VisitorHistoryConversationWireDto(
    val conversationId: String,
    val state: String,
    val startedAt: String,
    val closedAt: String? = null,
    val previewBody: String? = null,
    val previewAuthorKind: String? = null,
    val previewCreatedAt: String? = null,
)

/** Each timestamp parses through [OffsetDateTime] (the wire carries an explicit offset, `+03:00` or `Z`)
 * and drops to the absolute [Instant] the row renders in the operator's own zone; a required-but-present
 * value that will not parse ([startedAt]/[previewCreatedAt]) becomes `null` rather than failing the whole
 * page, per [VisitorHistoryConversation]'s own contract, while a genuinely absent [closedAt] is `null`
 * because the conversation is still open. */
private fun VisitorHistoryConversationWireDto.toDomain() =
    VisitorHistoryConversation(
        conversationId = conversationId,
        state = state,
        startedAt = startedAt.toInstantOrNull(),
        closedAt = closedAt?.toInstantOrNull(),
        previewBody = previewBody,
        previewAuthorKind = previewAuthorKind,
        previewCreatedAt = previewCreatedAt?.toInstantOrNull(),
    )

private fun VisitorHistoryResponseWireDto.toDomain() =
    VisitorHistoryPage(
        conversations = conversations.map { it.toDomain() },
        nextBeforeId = nextBeforeId,
    )

private fun String.toInstantOrNull(): Instant? = runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()
