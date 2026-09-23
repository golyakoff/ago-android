package ago.chat.android.core.network.conversations

import ago.chat.android.core.domain.conversations.ClaimResult
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.ConversationSummary
import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.domain.conversations.QueueResult
import ago.chat.android.core.domain.net.NetworkFailure
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
                return QueueResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return QueueResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            QueueResult.Loaded(response.body<OperatorQueueResponseWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty queue" - the identical
            // `KtorIdentityApi`/`shapeGuard.ts` lesson, read onto this endpoint: a dropped array here
            // must not look like a shop with nothing waiting and nothing assigned.
            QueueResult.Failed(NetworkFailure.from(failure))
        }
    }

    /**
     * `POST /api/v1/conversations/{id}/claim` — `204` on success, a non-2xx with an RFC 7807 `detail`
     * a [ClaimResult.Refused] carrying it verbatim (`ConversationsEndpoints.HandleClaimAsync`'s own doc
     * comment: "no request body... the caller already knows what it asked for"). `26-59`: a transport
     * exception, or a non-2xx with no `detail` to show, is [ClaimResult.Failed] instead — this method
     * never fabricates a `detail` string of its own.
     */
    override suspend fun claim(conversationId: String): ClaimResult {
        val response =
            try {
                client.post("$apiBaseUrl/api/v1/conversations/$conversationId/claim")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ClaimResult.Failed(NetworkFailure.from(failure))
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

        return detail?.let { ClaimResult.Refused(it) } ?: ClaimResult.Failed(NetworkFailure.ServerError(response.status.value))
    }
}

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
    // `26-15`: additive, `false` for a row that predates the field — `ConversationSummaryDto.cs`'s own
    // remarks. [ConversationSummary]'s own doc comment says why this screen carries it now.
    val hasAttachmentUploadGrant: Boolean = false,
    // `26-29`/`26-30`: additive the identical way — `null` for a row that predates the pair, or for a
    // conversation with no messages at all. [ConversationSummary]'s own doc comment carries the full
    // "both null together" rule; this class only mirrors the wire shape.
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    // `26-40`: additive the identical way — `""` for a row that predates the field.
    // `Ago.Chat.Contracts.ConversationSummaryDto.State`'s own wire spelling, unparsed here -
    // [ConversationSummary]'s own doc comment on why the classification lives in `:core:domain`.
    val state: String = "",
    // `26-76`: additive the identical way — `null` for plain prose and for a row that predates the
    // field. `Ago.Chat.Contracts.ConversationSummaryDto.LastMessageContentKind`'s own raw wire value,
    // unparsed here - [ConversationSummary]'s own doc comment carries the reasoning.
    val lastMessageContentKind: String? = null,
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
        hasAttachmentUploadGrant = hasAttachmentUploadGrant,
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = lastMessageAt,
        state = state,
        lastMessageContentKind = lastMessageContentKind,
    )

private fun OperatorQueueResponseWireDto.toDomain() =
    ConversationQueue(
        waiting = waiting.map { it.toDomain() },
        assignedToMe = assignedToMe.map { it.toDomain() },
    )
