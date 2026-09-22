package ago.chat.android.core.network.conversations

import ago.chat.android.core.domain.conversations.ClaimResult
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.ConversationSummary
import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.domain.conversations.QueueResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-14`: the adapter behind [ConversationsApi] — the same "the whole status-code-to-meaning mapping
 * lives here, and only here" shape `KtorIdentityApi`'s own doc comment states for the pre-session flow.
 *
 * `X-Ago-Active-Site` and the bearer token are attached by client plugins (`installAgoRestDefaults`),
 * not threaded through either method here — `KtorIdentityApi`'s own remarks explain why this class can
 * be read for "which endpoints does the conversation list touch" without also being the place tenancy
 * and credentials are handled.
 */
public class KtorConversationsApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : ConversationsApi {
    /** `GET /api/v1/conversations/queue`, gated by `RequireOperatorIdentity` server-side. */
    override suspend fun fetchQueue(): QueueResult {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/conversations/queue")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return QueueResult.Failed(failure.describe())
            }

        if (!response.status.isSuccess()) {
            return QueueResult.Failed("http.${response.status.value}")
        }

        return try {
            QueueResult.Loaded(response.body<OperatorQueueResponseWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty queue" - the identical
            // `KtorIdentityApi`/`shapeGuard.ts` lesson, read onto this endpoint: a dropped array here
            // must not look like a shop with nothing waiting and nothing assigned.
            QueueResult.Failed(failure.describe())
        }
    }

    /**
     * `POST /api/v1/conversations/{id}/claim` — `204` on success, every other status a [ClaimResult.Refused]
     * carrying the server's own RFC 7807 `detail` when the body has one (`ConversationsEndpoints.HandleClaimAsync`'s
     * own doc comment: "no request body... the caller already knows what it asked for").
     */
    override suspend fun claim(conversationId: String): ClaimResult {
        val response =
            try {
                client.post("$apiBaseUrl/api/v1/conversations/$conversationId/claim")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ClaimResult.Refused(failure.describe())
            }

        if (response.status.isSuccess()) {
            return ClaimResult.Claimed
        }

        val detail =
            try {
                response.body<ProblemDetailsWireDto>().detail
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }

        return ClaimResult.Refused(detail ?: "http.${response.status.value}")
    }
}

private fun Exception.describe(): String = "${this::class.simpleName}: ${message ?: "no detail"}"

/** RFC 7807, read for exactly the one field a claim refusal needs — `ago-console`'s own
 * `problemDetailsFrom` reads `type` too, which this screen has no use for: every refusal renders the
 * same way here regardless of which server-side check produced it (this file's own `ClaimResult.Refused`
 * doc comment). */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/** `Ago.Chat.Contracts.ConversationSummaryDto`, reduced to the fields [ConversationSummary] carries. */
@Serializable
private data class ConversationSummaryWireDto(
    val conversationId: String,
    val visitorId: String,
    val createdAt: String,
    val operatorUnreadCount: Int,
    val emojiCreature: String? = null,
    val emojiFood: String? = null,
    val visitorName: String? = null,
)

/** `Ago.Chat.Contracts.OperatorQueueResponse`. */
@Serializable
private data class OperatorQueueResponseWireDto(
    val waiting: List<ConversationSummaryWireDto>,
    val assignedToMe: List<ConversationSummaryWireDto>,
)

private fun ConversationSummaryWireDto.toDomain() =
    ConversationSummary(
        conversationId = conversationId,
        visitorId = visitorId,
        emojiCreature = emojiCreature,
        emojiFood = emojiFood,
        visitorName = visitorName,
        createdAt = createdAt,
        operatorUnreadCount = operatorUnreadCount,
    )

private fun OperatorQueueResponseWireDto.toDomain() =
    ConversationQueue(
        waiting = waiting.map { it.toDomain() },
        assignedToMe = assignedToMe.map { it.toDomain() },
    )
