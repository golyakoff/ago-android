package ago.chat.android.core.network.notes

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.notes.AddNoteResult
import ago.chat.android.core.domain.notes.ConversationNote
import ago.chat.android.core.domain.notes.ConversationNotesApi
import ago.chat.android.core.domain.notes.ConversationNotesResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-115`: the adapter behind [ConversationNotesApi] — the same "the whole status-code-to-meaning
 * mapping lives here, and only here" shape [ago.chat.android.core.network.conversations.KtorConversationsApi]'s
 * own doc comment states.
 *
 * `X-Ago-Active-Site` and the bearer token are attached by client plugins, not threaded through either
 * method here — the identical reason [ago.chat.android.core.network.contactdetails.KtorContactDetailsApi]
 * gives for its own sibling per-conversation reads.
 */
public class KtorConversationNotesApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : ConversationNotesApi {
    /** `GET /api/v1/conversations/{conversationId}/notes`. */
    override suspend fun fetchNotes(conversationId: String): ConversationNotesResult {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/conversations/$conversationId/notes")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ConversationNotesResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return ConversationNotesResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            ConversationNotesResult.Loaded(response.body<NotesResponseWireDto>().notes.map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "no notes yet" - the identical
            // `KtorConversationsApi`/`shapeGuard.ts` lesson, read onto this endpoint.
            ConversationNotesResult.Failed(NetworkFailure.from(failure))
        }
    }

    /**
     * `POST /api/v1/conversations/{conversationId}/notes`, body `{"body": body}`. `contentType`/
     * `setBody` are required for the exact reason
     * [ago.chat.android.core.network.conversations.KtorConversationsApi.markRead]'s own doc comment
     * gives for its own first request body — `ContentNegotiation` only serializes a body it can match
     * against a declared `Content-Type`.
     */
    override suspend fun addNote(
        conversationId: String,
        body: String,
    ): AddNoteResult {
        val response =
            try {
                client.post("$apiBaseUrl/api/v1/conversations/$conversationId/notes") {
                    contentType(ContentType.Application.Json)
                    setBody(AddNoteRequestWireDto(body))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return AddNoteResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                AddNoteResult.Added(response.body<NoteWireDto>().toDomain())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                AddNoteResult.Failed(NetworkFailure.from(failure))
            }
        }

        val detail =
            try {
                response.body<ProblemDetailsWireDto>().detail
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }

        return detail?.let { AddNoteResult.Refused(it) } ?: AddNoteResult.Failed(NetworkFailure.ServerError(response.status.value))
    }
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared copy
 * every adapter in this codebase keeps for itself. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/** `NoteEndpoints.AddNoteRequest` — the one field that endpoint's own body carries. */
@Serializable
private data class AddNoteRequestWireDto(
    val body: String,
)

/** `NoteEndpoints.NoteDto`, field for field. */
@Serializable
private data class NoteWireDto(
    val id: String,
    val authorId: String,
    val body: String,
    val createdAt: String,
)

private fun NoteWireDto.toDomain() = ConversationNote(id = id, authorId = authorId, body = body, createdAt = createdAt)

/** `NoteEndpoints.NotesResponse`. */
@Serializable
private data class NotesResponseWireDto(
    val notes: List<NoteWireDto>,
)
