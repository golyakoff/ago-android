package ago.chat.android.core.domain.notes

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-115`: the port the contact-detail panel's future «Заметки команды» section reads and writes
 * through — the identical split [ago.chat.android.core.domain.conversations.ConversationsApi] already
 * establishes.
 *
 * Confirmed against the real `ago-chat` code (`NoteEndpoints.cs`) rather than assumed from the `26-111`
 * design doc's own element map: `GET`/`POST /api/v1/conversations/{conversationId}/notes`, both
 * operator-only, list gated `conversation:read`, add gated `conversation:note_write`
 * (`docs/design/26-111-*.md`'s own permission appendix). There is no count endpoint — the design doc's
 * own N1 row states the count is the list's own length, so this port has no separate "count" method for
 * a future view model to call; it counts what [fetchNotes] returns.
 */
public interface ConversationNotesApi {
    /** `GET /api/v1/conversations/{conversationId}/notes`. Every note this conversation has, oldest or
     * newest first exactly as the server orders them — this port reorders nothing. */
    public suspend fun fetchNotes(conversationId: String): ConversationNotesResult

    /** `POST /api/v1/conversations/{conversationId}/notes`, body `{"body": body}`. Returns the server's
     * own created row — `id`/`authorId`/`createdAt` are all server-assigned, never guessed at
     * client-side, the identical "the write's response is the one true copy" shape
     * [ago.chat.android.core.domain.bookings.BookingsApi.revealCustomerPhone]'s own doc comment states
     * for a different write. */
    public suspend fun addNote(
        conversationId: String,
        body: String,
    ): AddNoteResult
}

/** `NoteEndpoints.NoteDto`, field for field. [authorId] is never a name this port invents —
 * `ago.chat.android.core.domain.contactdetails.ContactDetail`'s own doc comment states the identical
 * "raw id, not a fabricated label" discipline for a sibling read; rendering an operator's own name
 * beside [authorId] is this panel's UI concern (a team-roster lookup), never this port's. */
public data class ConversationNote(
    val id: String,
    val authorId: String,
    val body: String,
    val createdAt: String,
)

/** What reading one conversation's team notes came back with — the identical two-arm shape
 * [ago.chat.android.core.domain.conversations.QueueResult] already establishes. */
public sealed interface ConversationNotesResult {
    public data class Loaded(
        val notes: List<ConversationNote>,
    ) : ConversationNotesResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : ConversationNotesResult
}

/** What adding one team note came back with — the identical three-arm shape
 * [ago.chat.android.core.domain.conversations.ClaimResult] already establishes, restated with a value on
 * success the same way
 * [ago.chat.android.core.domain.contactdetails.RevealContactDetailResult] restates it for its own write. */
public sealed interface AddNoteResult {
    /** A `2xx` carrying the server's own created row. */
    public data class Added(
        val note: ConversationNote,
    ) : AddNoteResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail` — shown to the operator verbatim (an
     * empty body rejected server-side, say). */
    public data class Refused(
        val detail: String,
    ) : AddNoteResult

    /** Everything that is not a genuine server refusal. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : AddNoteResult
}
