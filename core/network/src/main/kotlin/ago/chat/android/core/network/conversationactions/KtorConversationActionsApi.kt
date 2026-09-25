package ago.chat.android.core.network.conversationactions

import ago.chat.android.core.domain.conversationactions.ConversationActionResult
import ago.chat.android.core.domain.conversationactions.ConversationActionsApi
import ago.chat.android.core.domain.net.NetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-146`: the adapter behind [ConversationActionsApi] — the same "the whole status-code-to-meaning
 * mapping lives here, and only here" shape
 * [ago.chat.android.core.network.conversations.KtorConversationsApi]'s own doc comment states.
 *
 * `X-Ago-Active-Site` and the bearer token are attached by client plugins (`installAgoRestDefaults`),
 * not threaded through any method here — the identical reason
 * [ago.chat.android.core.network.notes.KtorConversationNotesApi] gives for its own sibling
 * per-conversation writes.
 *
 * All three actions share [postAction] because they are the identical wire shape: a body-less `POST`
 * addressed by the conversation, a `2xx` meaning "it landed", and the exact refusal/transport split
 * [ago.chat.android.core.network.conversations.KtorConversationsApi.claim] already draws. The grant and
 * revoke endpoints answer `200` with a grant-status body; this adapter deliberately does not read it, the
 * same fire-and-return choice [ago.chat.android.core.network.conversations.KtorConversationsApi.markRead]
 * makes with its own `200` body — the panel re-reads the conversation for the caption's who/when.
 */
public class KtorConversationActionsApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : ConversationActionsApi {
    /** `POST /api/v1/conversations/{conversationId}/close`. */
    override suspend fun close(conversationId: String): ConversationActionResult =
        postAction("$apiBaseUrl/api/v1/conversations/$conversationId/close")

    /** `POST /api/v1/conversations/{conversationId}/grant-attachment-upload`. */
    override suspend fun grantAttachmentUpload(conversationId: String): ConversationActionResult =
        postAction("$apiBaseUrl/api/v1/conversations/$conversationId/grant-attachment-upload")

    /** `POST /api/v1/conversations/{conversationId}/revoke-attachment-upload`. */
    override suspend fun revokeAttachmentUpload(conversationId: String): ConversationActionResult =
        postAction("$apiBaseUrl/api/v1/conversations/$conversationId/revoke-attachment-upload")

    /**
     * The one body-less-POST idiom every action here shares — a `2xx` is [ConversationActionResult.Succeeded],
     * a non-2xx with an RFC 7807 `detail` is [ConversationActionResult.Refused] carrying it verbatim, and a
     * transport exception or a bare non-2xx with no `detail` is [ConversationActionResult.Failed], never a
     * `detail` this method fabricates. It does not check for one exact success code (`204` for close, `200`
     * for grant/revoke): any 2xx is "the action landed", the identical reason
     * [ago.chat.android.core.network.conversations.KtorConversationsApi.requestErasure]'s own doc comment
     * gives for not pinning itself to `202`.
     */
    private suspend fun postAction(url: String): ConversationActionResult {
        val response =
            try {
                client.post(url)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ConversationActionResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return ConversationActionResult.Succeeded
        }

        val detail =
            try {
                response.body<ProblemDetailsWireDto>().detail
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }

        return detail?.let { ConversationActionResult.Refused(it) }
            ?: ConversationActionResult.Failed(NetworkFailure.ServerError(response.status.value))
    }
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared copy
 * every adapter in this codebase keeps for itself. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)
