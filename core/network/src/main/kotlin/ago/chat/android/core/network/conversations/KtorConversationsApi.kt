package ago.chat.android.core.network.conversations

import ago.chat.android.core.domain.conversations.AllConversationsPage
import ago.chat.android.core.domain.conversations.AllConversationsResult
import ago.chat.android.core.domain.conversations.ClaimResult
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.ConversationSummary
import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.domain.conversations.ErasureResult
import ago.chat.android.core.domain.conversations.QueueResult
import ago.chat.android.core.domain.net.NetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
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

    /**
     * `26-80`: `POST /api/v1/conversations/{id}/read`, body `{"upToSequence":...}` -
     * `MarkConversationReadRequest` on the server side (`ConversationsEndpoints.cs`). The body is
     * handed to Ktor as a `@Serializable` value, not a raw string - `installAgoRestDefaults`' own
     * `ContentNegotiation { json(agoJson) }` is what turns [MarkConversationReadRequestWireDto] into
     * the request's JSON, the identical plugin [fetchQueue] already relies on for the read direction.
     *
     * The `200` body carries the conversation's resulting unread state
     * (`MarkConversationReadHandler`'s own doc comment explains why `200` rather than `204` here), but
     * this method never reads it - [ConversationsApi.markRead]'s own doc comment says why a plain
     * success flag is the whole contract a fire-and-forget caller needs.
     */
    override suspend fun markRead(
        conversationId: String,
        upToSequence: Int,
    ): Boolean {
        val response =
            try {
                client.post("$apiBaseUrl/api/v1/conversations/$conversationId/read") {
                    // `26-80`, confirmed the hard way by a `MockEngine` test rather than assumed:
                    // `ContentNegotiation`'s client-side request transform only serializes a body it can
                    // match to a registered converter, and it matches on the request's own declared
                    // `Content-Type` - with none set, `setBody` alone falls through to Ktor's generic
                    // "guess how to send this object" path, which needs `kotlin-reflect` (not on this
                    // app's classpath) and fails with "Kotlin reflection is not available". This is the
                    // first request body this class has ever sent - every fix here is one every future
                    // POST body will need too.
                    contentType(ContentType.Application.Json)
                    setBody(MarkConversationReadRequestWireDto(upToSequence))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return false
            }

        return response.status.isSuccess()
    }

    /**
     * `26-90`: `GET /api/v1/conversations/all?beforeId=…&pageSize=…&state=…&state=…` — the site-wide
     * admin list, gated server-side by `site:configure`.
     *
     * `state` is sent as a **repeated** query key rather than one comma-joined value, because that is
     * what the server binds (`ConversationsEndpoints.HandleGetAllForSiteAsync`'s own `string[]? state`,
     * ASP.NET Core's own repeatable-key binding). Ktor's [io.ktor.client.request.parameter] appends one
     * key per call, which is exactly that shape; a joined string would arrive as a single unparseable
     * state name and be refused by the handler's own vocabulary check.
     *
     * A `403` here is a real, expected answer rather than a bug: the whole tab is hidden from an
     * operator without `site:configure` (`ConversationListUiState`'s own gate), so reaching this method
     * without the permission should not happen — but it renders as an ordinary
     * [AllConversationsResult.Failed] if it ever does, never as an empty list, the identical
     * "a bad status is not an empty answer" rule [fetchQueue] above already states.
     */
    override suspend fun fetchAllConversations(
        beforeId: String?,
        pageSize: Int,
        states: List<String>,
    ): AllConversationsResult {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/conversations/all") {
                    parameter("pageSize", pageSize)
                    beforeId?.let { parameter("beforeId", it) }
                    states.forEach { state -> parameter("state", state) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return AllConversationsResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return AllConversationsResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            AllConversationsResult.Loaded(response.body<AllConversationsForSiteResponseWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            AllConversationsResult.Failed(NetworkFailure.from(failure))
        }
    }

    /**
     * `26-90`: `POST /api/v1/conversations/{id}/erase` — `202 Accepted` on success, which this method
     * reports as [ErasureResult.Accepted] and nothing more. It deliberately does **not** check for
     * `202` specifically: any 2xx is "the request was recorded", and pinning the client to one exact
     * success code would make a future `200`-with-a-body look like a failure for no gain.
     *
     * The refusal path is [claim]'s, verbatim: an RFC 7807 `detail` becomes [ErasureResult.Refused]
     * shown to the operator as-is, and a transport failure or a bare non-2xx becomes
     * [ErasureResult.Failed] carrying a classification this class never fabricates a sentence for.
     */
    override suspend fun requestErasure(conversationId: String): ErasureResult {
        val response =
            try {
                client.post("$apiBaseUrl/api/v1/conversations/$conversationId/erase")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ErasureResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return ErasureResult.Accepted
        }

        val detail =
            try {
                response.body<ProblemDetailsWireDto>().detail
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }

        return detail?.let { ErasureResult.Refused(it) } ?: ErasureResult.Failed(NetworkFailure.ServerError(response.status.value))
    }
}

/** `Ago.Chat.Api.Conversations.ConversationsEndpoints.MarkConversationReadRequest` - the one field
 * that endpoint's own body carries. */
@Serializable
private data class MarkConversationReadRequestWireDto(
    val upToSequence: Int,
)

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
    // `26-90`: additive the identical way — `0` for the queue read, which does not populate it, and for
    // a row that predates the field. A total, never an unread count
    // (`Ago.Chat.Contracts.ConversationSummaryDto.MessageCount`'s own remarks).
    val messageCount: Int = 0,
    // `26-90`: additive the identical way — `null` for a Waiting row (no operator by definition) and
    // for the queue read, which does not join `operators` in at all.
    val operatorName: String? = null,
    // `26-152`: additive the identical way — `null` for a row with no grant at all and for one that
    // predates the pair. `Ago.Chat.Contracts.ConversationSummaryDto.AttachmentUploadGrantedAt`/
    // `.AttachmentUploadGrantedByOperatorId`'s own raw wire values, unparsed here — [ConversationSummary]'s
    // own doc comment carries the reasoning (the contact-detail panel's «Приём файлов от посетителя»
    // caption, `26-152`).
    val attachmentUploadGrantedAt: String? = null,
    val attachmentUploadGrantedByOperatorId: String? = null,
)

/** `Ago.Chat.Contracts.OperatorQueueResponse`. */
@Serializable
private data class OperatorQueueResponseWireDto(
    val waiting: List<ConversationSummaryWireDto>,
    val assignedToMe: List<ConversationSummaryWireDto>,
)

/** `26-90`: `Ago.Chat.Contracts.AllConversationsForSiteResponse` — the same row shape as the queue's
 * two lists, plus the keyset cursor. */
@Serializable
private data class AllConversationsForSiteResponseWireDto(
    val conversations: List<ConversationSummaryWireDto>,
    val nextBeforeId: String? = null,
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
        messageCount = messageCount,
        operatorName = operatorName,
        attachmentUploadGrantedAt = attachmentUploadGrantedAt,
        attachmentUploadGrantedByOperatorId = attachmentUploadGrantedByOperatorId,
    )

private fun OperatorQueueResponseWireDto.toDomain() =
    ConversationQueue(
        waiting = waiting.map { it.toDomain() },
        assignedToMe = assignedToMe.map { it.toDomain() },
    )

private fun AllConversationsForSiteResponseWireDto.toDomain() =
    AllConversationsPage(
        conversations = conversations.map { it.toDomain() },
        nextBeforeId = nextBeforeId,
    )
