package ago.chat.android.core.domain.conversationactions

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-146`: the port the contact-detail panel's own close (`26-153`) and files-toggle (`26-152`) actions
 * write through — declared here and implemented in `:core:network`
 * ([ago.chat.android.core.network.conversationactions.KtorConversationActionsApi]), the identical split
 * [ago.chat.android.core.domain.conversations.ConversationsApi] already establishes for the queue's own
 * writes.
 *
 * A **new** port rather than three more methods on the queue [ago.chat.android.core.domain.conversations.ConversationsApi]:
 * these are the panel's own conversation-level actions, gated by their own server-side permissions
 * (`conversation:close`, `conversation:attachment_upload_grant`), not the queue's reads — the same "a
 * different noun behind a different gate is its own port, not a widened neighbour" reasoning
 * [ago.chat.android.core.domain.notes.ConversationNotesApi] and
 * [ago.chat.android.core.domain.tags.ConversationTagsApi]'s own doc comments already state for the
 * sibling `26-115` panel clients. Each call is a real network attempt, every time — nothing here is
 * cached, because whether a close or a grant landed is exactly the kind of write decision rule 8 forbids
 * answering from disk.
 */
public interface ConversationActionsApi {
    /**
     * `POST /api/v1/conversations/{conversationId}/close` — no request body, the same "the route already
     * names the conversation and the token already names the caller" shape
     * [ago.chat.android.core.domain.conversations.ConversationsApi.claim] documents. `204` on success
     * (`ConversationsEndpoints.HandleCloseAsync`, `ago-chat`); a genuine server refusal (a non-2xx
     * carrying an RFC 7807 `detail`) is [ConversationActionResult.Refused], shown verbatim; everything
     * else that kept this call from succeeding is [ConversationActionResult.Failed].
     */
    public suspend fun close(conversationId: String): ConversationActionResult

    /**
     * `POST /api/v1/conversations/{conversationId}/grant-attachment-upload` — no request body, gated
     * server-side by `conversation:attachment_upload_grant`. The endpoint answers `200` with the
     * resulting grant status (who granted it, when), but this port reports only whether the write
     * landed: the panel re-reads the conversation for the «Приём файлов от посетителя» caption's own
     * who/when (`26-152`), the identical "re-fetch rather than trust a partial write result" choice
     * [ago.chat.android.core.domain.conversations.ConversationsApi.claim]'s own doc comment makes.
     */
    public suspend fun grantAttachmentUpload(conversationId: String): ConversationActionResult

    /**
     * `POST /api/v1/conversations/{conversationId}/revoke-attachment-upload` — the reverse of
     * [grantAttachmentUpload], same body-less shape, same `200`-with-status the panel does not read here,
     * same gate.
     */
    public suspend fun revokeAttachmentUpload(conversationId: String): ConversationActionResult
}

/**
 * What one panel conversation-action came back with — the same three arms
 * [ago.chat.android.core.domain.conversations.ClaimResult] has, for the same reasons: a genuine server
 * refusal carries its own `detail` and is shown to the operator verbatim, anything that kept the call
 * from being a genuine answer is a [NetworkFailure] classification rather than a fabricated sentence, and
 * neither is ever retried automatically. One shared type across close/grant/revoke rather than three
 * near-identical ones, because all three are the same "did this body-less write land, and if not why"
 * question — unlike [ago.chat.android.core.domain.conversations.ErasureResult], whose distinct
 * `Accepted` arm exists only because its endpoint's `202` means something a `2xx` here does not.
 */
public sealed interface ConversationActionResult {
    /** A `2xx` — the action landed. The caller re-reads the conversation for its fresh state rather than
     * trusting anything carried back here (this file's own [ConversationActionsApi.grantAttachmentUpload]
     * doc comment). */
    public data object Succeeded : ConversationActionResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail` — shown to the operator as-is, exactly
     * once, never retried automatically. `26-59`: this is the only thing `detail` is allowed to hold; a
     * transport failure or a bare non-2xx with no `detail` is [Failed] instead, never a `detail` this
     * port made up itself. */
    public data class Refused(
        val detail: String,
    ) : ConversationActionResult

    /** Everything a body-less write can fail with that is not a genuine server refusal — a dropped
     * connection, or a non-2xx whose body carried no `detail` to show. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : ConversationActionResult
}
