package ago.chat.android.thread.contactpanel

import ago.chat.android.core.domain.contactdetails.ContactDetail
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.notes.ConversationNote
import ago.chat.android.core.domain.tags.ConversationTag
import ago.chat.android.core.domain.tags.Tag
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryConversation
import ago.chat.android.core.domain.visitorsummary.VisitorSummary
import ago.chat.android.core.network.realtime.MessageDto

/**
 * `26-147`: the contact-detail panel's own state — the join point every later section
 * (`26-148`…`26-153`) grows rather than replaces.
 *
 * **Why the header's in-hand facts (H1–H3) are not held here.** The avatar, the display name and the
 * state chip come straight from the [ago.chat.android.core.domain.conversations.ConversationSummary] the
 * thread already holds — `docs/design/26-111-thread-contact-detail-panel.md` §4 ("handed the
 * `ConversationSummary` the thread already holds … no extra round trip for those"). They flow into
 * [ContactDetailPanel] as plain parameters, exactly as they already flow into
 * [ago.chat.android.thread.ThreadScreen], so this state carries only the *async* part of the header —
 * H4/H5, read through [ago.chat.android.core.domain.visitorsummary.VisitorSummaryApi] — and, later, each
 * section's own async state.
 *
 * **Growth convention for `26-148`…`26-153`.** A section that needs its own async read adds one more
 * field here (its own `sealed interface` of Loading/Loaded/Failed, the shape [summary] already
 * establishes), defaulted so this data class's existing construction sites compile unchanged — the same
 * additive discipline [ago.chat.android.core.domain.conversations.ConversationSummary]'s own doc comment
 * states for the wire DTO it mirrors.
 *
 * `26-153`: [restriction] is the sixth and final section slice, added the identical additive way. The
 * close action needs no `Loading`/`Loaded` arm of its own — unlike every read-backed section above, it is
 * a pure write with nothing to load — so [closing]/[closeError] ride directly on this class, the same
 * "an in-flight flag plus a last-error field, nothing more" shape a write-only action needs
 * (`ContactPanelViewModel.closeConversation`'s own doc comment).
 */
public data class ContactPanelUiState(
    val summary: HeaderSummaryState = HeaderSummaryState.Loading,
    val contactDetails: ContactDetailsSectionState = ContactDetailsSectionState.Loading,
    val tags: TagsSectionState = TagsSectionState.Loading,
    val notes: NotesSectionState = NotesSectionState.Loading,
    val pastDialogs: PastDialogsSectionState = PastDialogsSectionState.Loading,
    val attachmentUpload: AttachmentUploadSectionState = AttachmentUploadSectionState.Loading,
    val restriction: RestrictionSectionState = RestrictionSectionState.Loading,
    val closing: Boolean = false,
    val closeError: CloseActionError? = null,
)

/**
 * The header's async half — H4 «Первый визит {date}» and H5 «N диалог(ов)», both from one
 * `visitor-summary` read (`26-143`). Three arms, the identical Loading/Loaded/Failed vocabulary the
 * sibling clients use, so the panel renders a skeleton while it loads, the two facts once it lands, and
 * an inline retry (never a whole-sheet failure — §4) if it does not: the in-hand H1–H3 stay on screen
 * through every arm.
 */
public sealed interface HeaderSummaryState {
    public data object Loading : HeaderSummaryState

    public data class Loaded(
        val summary: VisitorSummary,
    ) : HeaderSummaryState

    public data class Failed(
        val reason: NetworkFailure,
    ) : HeaderSummaryState
}

/**
 * `26-148`: the КОНТАКТНЫЕ ДАННЫЕ section's async state — the first section slice to grow
 * [ContactPanelUiState] the additive way its own doc comment describes, reading the visitor's contact
 * rows through [ago.chat.android.core.domain.contactdetails.ContactDetailsApi] (`26-115`). Three arms, the
 * same Loading/Loaded/Failed vocabulary [HeaderSummaryState] establishes, so the section renders a
 * skeleton while it loads, the rows once they land, and its own inline retry (never a whole-sheet
 * failure — `docs/design/26-111-thread-contact-detail-panel.md` §4) if it does not.
 *
 * The per-row transient fields live on the [Loaded] arm rather than on [ContactPanelUiState] itself, the
 * identical "the in-flight set travels with the loaded list it acts on" shape
 * [ago.chat.android.bookings.ContactsUiState.Loaded] already establishes for the calendar reveal.
 *
 * `26-169` (`docs/design/26-156-*.md`): [pendingIds]/[rowErrors] are `26-148`'s own `revealingIds`/
 * `revealErrors`, generalised from "a reveal in flight" to "any one of this row's three writes in flight" —
 * reveal, [ContactPanelViewModel.saveEditContactDetail] and [ContactPanelViewModel.setContactDetailAssessment]
 * all share the same shape (one row, one write, one outcome), and only one of the three can ever be in
 * flight for a given row at a time (the row's own `⋮` menu is the only entry point to edit or assess, and
 * it is unavailable while a reveal is already running, [ContactDetailsSection]'s own doc comment). A second,
 * per-row `editingId`/`editDraft` pair is new here rather than reused from [NotesSectionState.Loaded.draft]'s
 * shape verbatim: unlike the panel's one note composer, this section can have many rows, so the "which one"
 * needs its own id alongside the text.
 */
public sealed interface ContactDetailsSectionState {
    public data object Loading : ContactDetailsSectionState

    /**
     * The rows the server returned, plus the per-row transient state every write on this section shares.
     *
     * [pendingIds] — the contact-detail ids with a reveal, edit-save or assessment write in flight right
     * now; the section disables that row's «Показать»/`⋮` while its id is in this set (keyed by row id,
     * since every write here is a single-row action, unlike the calendar's per-customer key).
     *
     * [rowErrors] — the last non-success outcome per row: a [RowActionError.Refused] carries the server's
     * own RFC 7807 sentence to show verbatim regardless of which of the three writes produced it; a
     * [RowActionError.Failed] arm is specific to the write that produced it (the section renders a different
     * generic line for "couldn't show" vs "couldn't save the edit" vs "couldn't update the status" — three
     * real, different sentences, not one reused across all three). The row's own value/assessment stays
     * exactly as it was through either arm — this app never mutates a row from a failure, the identical
     * posture `26-148`'s own reveal already established.
     *
     * [editingId]/[editDraft] — which row (if any) the operator currently has open in the row `⋮`'s own
     * «Изменить» editor, and that editor's current draft text. `null`/`""` while no row is being edited;
     * opening a *different* row's editor replaces both in one step, discarding whatever was typed into the
     * first (`ContactPanelViewModel.startEditContactDetail`'s own doc comment) — "only one row edits at a
     * time" is a UI rule this state enforces by construction (one field, not a per-row map) rather than a
     * guard some caller could forget.
     */
    public data class Loaded(
        val details: List<ContactDetail>,
        val pendingIds: Set<String> = emptySet(),
        val rowErrors: Map<String, RowActionError> = emptyMap(),
        val editingId: String? = null,
        val editDraft: String = "",
    ) : ContactDetailsSectionState

    public data class Failed(
        val reason: NetworkFailure,
    ) : ContactDetailsSectionState
}

/**
 * `26-148`/`26-169`: why one row's write did not land — generalised from `26-148`'s own `RowRevealError`
 * to the three writes [ContactDetailsSectionState.Loaded.rowErrors] now covers (reveal, edit, set
 * assessment). [Refused] is shared across all three: a genuine server refusal is the identical RFC 7807
 * `detail` shape regardless of which PATCH/POST produced it, so there is nothing write-specific to carry.
 * [Failed] is **not** shared — a transport failure carries no server sentence, so the section falls back to
 * one generic, localized line, and that line's own wording differs per write («Не удалось показать» /
 * «Не удалось сохранить изменение.» / «Не удалось обновить статус.»); nesting [Failed] by write keeps that
 * choice exhaustive (a fourth write added later fails to compile here until it too states its own line)
 * rather than a shared `Failed(reason)` plus a second field the section would have to keep in sync by hand.
 * The successful arm needs no representation here in either case: it replaces the row in place.
 */
public sealed interface RowActionError {
    /** A genuine server refusal — its RFC 7807 `detail` shown verbatim, the same "show the server's own
     * sentence" posture the reveal-refusal arm originally established. */
    public data class Refused(
        val detail: String,
    ) : RowActionError

    /** A transport failure, classified by which of the section's three writes produced it — see this
     * sealed interface's own doc comment for why this arm is not a single shared shape. Each carries its
     * own [NetworkFailure], the identical "classified by reason only if a caller ever needs to" posture
     * every other `Failed` arm in this file keeps. */
    public sealed interface Failed : RowActionError {
        /** No server sentence to show for a failed reveal — the section's own generic «Не удалось
         * показать» line. */
        public data class Reveal(
            val reason: NetworkFailure,
        ) : Failed

        /** No server sentence to show for a failed edit save — the section's own generic «Не удалось
         * сохранить изменение.» line. The draft stays on screen; this arm never clears it. */
        public data class Edit(
            val reason: NetworkFailure,
        ) : Failed

        /** No server sentence to show for a failed assessment write — the section's own generic «Не
         * удалось обновить статус.» line. */
        public data class Assessment(
            val reason: NetworkFailure,
        ) : Failed
    }
}

/**
 * `26-149`: the tags section's async state — the second section slice to grow [ContactPanelUiState] the
 * additive way its own doc comment prescribes, reading and writing through
 * [ago.chat.android.core.domain.tags.ConversationTagsApi] (`26-115`). Three arms, the same
 * Loading/Loaded/Failed vocabulary [HeaderSummaryState] and [ContactDetailsSectionState] establish, so the
 * section renders a skeleton while it loads, the chips + «+ метка» control once they land, and its own
 * inline retry (never a whole-sheet failure — `docs/design/26-111-thread-contact-detail-panel.md` §4) if
 * it does not.
 *
 * **Why one [Loaded] carries both lists.** The section needs two reads — the conversation's own applied
 * tags (`fetchConversationTags`, the chips) and the site's whole tag vocabulary (`fetchSiteTags`, what the
 * «+ метка» picker offers minus the applied ones). Rather than two arms that can each be in a different
 * Loading/Failed state (doubling this type for a picker that a single retry fixes anyway), the arm lands
 * [Loaded] only when the **applied-tags** read — the primary content — succeeds, and folds whatever the
 * vocabulary read returned into [vocabulary]; a vocabulary read that itself failed simply yields an empty
 * [vocabulary] (the picker then offers nothing, non-destructively), the same "a secondary read degrades to
 * empty rather than failing the section it decorates" posture the chips-first shape here takes. The
 * alternative — failing the whole section when only the vocabulary read failed — would hide the readable
 * chips behind a retry the operator did not need.
 *
 * The two write-transient fields live on [Loaded] rather than on [ContactPanelUiState] itself, the
 * identical "the in-flight set travels with the loaded list it acts on" shape
 * [ContactDetailsSectionState.Loaded] already establishes for the reveal.
 */
public sealed interface TagsSectionState {
    public data object Loading : TagsSectionState

    /**
     * The conversation's applied tags and the site's tag vocabulary, plus the per-write transient state.
     *
     * [applied] — the tags currently on this conversation, drawn as chips (each removable when the
     * operator holds `conversation:tag`).
     *
     * [vocabulary] — the site's whole tag vocabulary; the «+ метка» picker offers this list **minus**
     * [applied] (that subtraction is a UI concern, [ConversationTagsApi][ago.chat.android.core.domain.tags.ConversationTagsApi.fetchSiteTags]'s
     * own doc comment). Empty when the vocabulary read itself failed — the picker then offers nothing.
     *
     * [pendingTagIds] — the tag ids with an apply or a remove in flight right now; the section disables
     * that chip's remove and that picker entry while its id is in this set (keyed by tag id, since both
     * writes act on a tag id).
     *
     * [actionError] — the last write outcome when it was not a success, shown non-destructively as one
     * line beneath the chips and cleared the moment a new write begins. The applied set is left exactly as
     * it was through either arm — a refused or failed write never mutates the on-screen tags.
     */
    public data class Loaded(
        val applied: List<ConversationTag>,
        val vocabulary: List<Tag>,
        val pendingTagIds: Set<String> = emptySet(),
        val actionError: TagActionError? = null,
    ) : TagsSectionState

    public data class Failed(
        val reason: NetworkFailure,
    ) : TagsSectionState
}

/**
 * `26-149`: why an apply or remove did not take — the two non-success arms of
 * [ago.chat.android.core.domain.tags.TagActionResult], carried into the UI so the section can show a
 * genuine server refusal verbatim but a transport failure as its own generic, localized line — the
 * identical split [RowActionError] draws for the reveal/edit/assessment writes. The successful arm needs
 * no representation: it updates the applied list in place.
 */
public sealed interface TagActionError {
    /** A genuine server refusal — its RFC 7807 `detail` shown verbatim, the same "show the server's own
     * sentence" posture [RowActionError.Refused] establishes. */
    public data class Refused(
        val detail: String,
    ) : TagActionError

    /** A transport failure — no server sentence to show, so the section renders one generic
     * «Не удалось изменить метки» line, classified by [reason] only if a caller ever needs to. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : TagActionError
}

/**
 * `26-150`: the «Заметки команды» row's own async state — the third section slice to grow
 * [ContactPanelUiState] the additive way its own doc comment prescribes, reading and writing through
 * [ago.chat.android.core.domain.notes.ConversationNotesApi] (`26-115`). Three arms, the same
 * Loading/Loaded/Failed vocabulary [HeaderSummaryState], [ContactDetailsSectionState] and
 * [TagsSectionState] establish — fetched once, at panel-open time, the same moment those two sections'
 * own reads fire (design Q8: "fetch the notes list on open … no separate count field"), so the row's own
 * count is never a second read, only [Loaded.notes]'s own size.
 *
 * **Why the composer's draft and in-flight state live on [Loaded] rather than on
 * [ContactPanelUiState] itself.** The identical "the in-flight state travels with the loaded list it
 * acts on" shape [ContactDetailsSectionState.Loaded] and [TagsSectionState.Loaded] already establish for
 * their own single write. Unlike those two, the note composer's own typed text has nowhere else to live —
 * this is the one section slice that needs a live draft rather than a picker selection — so [draft] rides
 * here rather than in a `remember`-only Compose state that a process death (or simply closing and
 * reopening the notes sub-screen) would silently drop.
 */
public sealed interface NotesSectionState {
    public data object Loading : NotesSectionState

    /**
     * The notes the server returned, in the order it returned them (this app reorders nothing — the
     * identical "the server's own order is the order" contract [ConversationNote]'s own doc comment
     * states), plus the composer's own transient state.
     *
     * [draft] — the note composer's current text, empty until the operator types into it and cleared
     * again once [AddNoteResult.Added] lands (`ContactPanelViewModel.addNote`'s own doc comment).
     *
     * [addingNote] — `true` while one add is in flight; the composer's submit control is disabled and its
     * label swaps to a "sending" word while this is set, the identical single-write in-flight shape
     * [ContactDetailsSectionState.Loaded.pendingIds] already establishes for a per-row write, here for
     * the panel's one composer instead of a per-row set.
     *
     * [addNoteError] — the last add outcome when it was not a success: a genuine server refusal shown
     * verbatim, or a transport failure shown as one generic line — the identical [RowActionError] /
     * [TagActionError] split. Unlike a refused tag write, [draft] is deliberately **not** cleared on a
     * refusal or a failure — the operator's own typed words stay in the composer so a refused note is
     * fixable (a blank body, say) rather than retyped from scratch.
     */
    public data class Loaded(
        val notes: List<ConversationNote>,
        val draft: String = "",
        val addingNote: Boolean = false,
        val addNoteError: AddNoteError? = null,
    ) : NotesSectionState

    public data class Failed(
        val reason: NetworkFailure,
    ) : NotesSectionState
}

/**
 * `26-150`: why adding one team note did not take — the two non-success arms of
 * [ago.chat.android.core.domain.notes.AddNoteResult], carried into the UI the identical way
 * [RowActionError] and [TagActionError] already carry their own write's non-success arms.
 */
public sealed interface AddNoteError {
    /** A genuine server refusal — its RFC 7807 `detail` shown verbatim, the same "show the server's own
     * sentence" posture [RowActionError.Refused] establishes. */
    public data class Refused(
        val detail: String,
    ) : AddNoteError

    /** A transport failure — no server sentence to show, so the sub-screen renders one generic
     * «Не удалось добавить заметку» line, classified by [reason] only if a caller ever needs to. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : AddNoteError
}

/**
 * `26-151`: the «Прошлые диалоги» row's own async state — the fourth and final section slice to grow
 * [ContactPanelUiState] the additive way its own doc comment prescribes, reading through
 * [ago.chat.android.core.domain.visitorhistory.VisitorHistoryApi] (`26-144`). Three arms, the same
 * Loading/Loaded/Failed vocabulary [HeaderSummaryState]/[ContactDetailsSectionState]/[TagsSectionState]/
 * [NotesSectionState] establish — fetched once at panel-open time, the identical moment those sections'
 * own reads fire.
 *
 * **Why this section nests a second read (`history`) rather than growing [ContactPanelUiState] with a
 * sibling field.** Design Q6 decided past dialogs are read-only and opened *from* the list (§4: "tapping
 * the row opens a list of the visitor's prior conversations; tapping one opens it read-only"), so the
 * transcript is inherently a child of *this* list, never a sibling section an operator could reach any
 * other way — the identical "the in-flight state travels with the loaded list it acts on" shape
 * [ContactDetailsSectionState.Loaded]/[TagsSectionState.Loaded] already establish for their own writes,
 * here for a nested *read* instead. [selectedConversationId]/[history] are `null`/`null` while the
 * operator is looking at the list itself, and both are set together the moment [ContactPanelViewModel.openPastDialog]
 * is called.
 */
public sealed interface PastDialogsSectionState {
    public data object Loading : PastDialogsSectionState

    /**
     * The visitor's other conversations on this site, oldest-appended as [ContactPanelViewModel.loadMorePastDialogs]
     * pages further back — a keyset list only ever grows forward, never replaces what already rendered
     * ([ago.chat.android.core.domain.visitorhistory.VisitorHistoryPage.nextBeforeId]'s own contract, the
     * identical convention [ago.chat.android.core.domain.conversations.AllConversationsPage.nextBeforeId]
     * already states).
     *
     * [nextBeforeId] — the keyset cursor for the next page of [conversations], `null` once the last page
     * has been fetched; drives whether the sub-screen offers a "load more" control at all.
     *
     * [loadingMore] — `true` while one further page is in flight, the section's own single load-more
     * in-flight flag (there is only ever one such fetch at a time, so a `Boolean` suffices — unlike
     * [ContactDetailsSectionState.Loaded.pendingIds]'s per-row `Set`, which needs one flag per
     * independently-triggerable row).
     *
     * [selectedConversationId]/[history] — which of [conversations] the operator opened for a read-only
     * transcript, and that transcript's own async state; both `null` while the operator is looking at the
     * list rather than a transcript.
     */
    public data class Loaded(
        val conversations: List<VisitorHistoryConversation>,
        val nextBeforeId: String? = null,
        val loadingMore: Boolean = false,
        val selectedConversationId: String? = null,
        val history: PastDialogHistoryState? = null,
    ) : PastDialogsSectionState

    public data class Failed(
        val reason: NetworkFailure,
    ) : PastDialogsSectionState
}

/**
 * `26-151`: one past conversation's own read-only transcript, opened through the hub's
 * `GetVisitorHistoryConversationAsync` ([ago.chat.android.core.network.realtime.OperatorHubEvents.getVisitorHistoryConversation]).
 * Q6 decided past dialogs are read-only — this type carries no draft, no `sending`, no send-refusal arm,
 * unlike [ago.chat.android.thread.ThreadUiState], which is the shape a *live*, writable conversation
 * needs and this deliberately is not.
 */
public sealed interface PastDialogHistoryState {
    public data object Loading : PastDialogHistoryState

    /**
     * [messages] — this past conversation's own messages, ascending by sequence, the identical ordering
     * contract [ago.chat.android.thread.ThreadUiState.messages] states, fed straight into the same
     * message renderer.
     *
     * [nextBeforeSequence] — the keyset cursor for "load older messages" within *this* transcript, `null`
     * once exhausted; [loadingOlder] is that manual load's own in-flight flag, and [historyError] is a
     * failed "load older" call's inline error — the identical three-field shape
     * [ago.chat.android.thread.ThreadUiState] draws for [ago.chat.android.thread.ThreadUiState.loadingOlder]/
     * [ago.chat.android.thread.ThreadUiState.historyError], minus everything about sending because this
     * transcript never sends.
     */
    public data class Loaded(
        val messages: List<MessageDto>,
        val nextBeforeSequence: Long? = null,
        val loadingOlder: Boolean = false,
        val historyError: NetworkFailure? = null,
    ) : PastDialogHistoryState

    public data class Failed(
        val reason: NetworkFailure,
    ) : PastDialogHistoryState
}

/**
 * `26-152`: the «Приём файлов от посетителя» toggle's own async state — the fifth and final section
 * slice to grow [ContactPanelUiState] the additive way that type's own doc comment prescribes. Unlike
 * every section above, there is no dedicated read endpoint for one conversation's own grant status —
 * [ago.chat.android.core.domain.conversationactions.ConversationActionsApi]'s own doc comment on
 * [ago.chat.android.core.domain.conversationactions.ConversationActionsApi.grantAttachmentUpload]
 * already names the mechanism this section actually uses: "the panel re-reads the conversation" through
 * [ago.chat.android.core.domain.conversations.ConversationsApi.fetchQueue] (the same two lists
 * [ago.chat.android.thread.ThreadRoute]'s own caller already draws this conversation's row from — a
 * thread can only ever be open on a `Waiting` or `AssignedToMe` conversation, never a closed admin-list
 * one, so the queue is always the right place to look). Three arms, the same Loading/Loaded/Failed
 * vocabulary every sibling section establishes.
 *
 * **Whole section hidden without `conversation:attachment_upload_grant`, not merely its write half.**
 * Unlike [TagsSectionState]/[NotesSectionState] (whose *read* half rides the panel's own
 * `conversation:read` gate and only the write is separately gated), this feature has no read gate of its
 * own to fall back to — `docs/design/26-111-contact-panel-slices.md`'s own S-J line and
 * `ago-console`'s own `AttachmentUploadGrantToggle` ("hidden, not disabled ... without
 * `ATTACHMENT_UPLOAD_GRANT_PERMISSION`") agree the entire row disappears, not just a control on it. The
 * view model itself stays permission-agnostic regardless (it always fetches and always exposes
 * [ago.chat.android.thread.contactpanel.ContactPanelViewModel.toggleAttachmentUpload]) — the gate lives
 * in the UI layer, the identical split [TagsSectionState]'s own doc comment states for [canTag].
 */
public sealed interface AttachmentUploadSectionState {
    public data object Loading : AttachmentUploadSectionState

    /**
     * [granted] — whether the visitor may currently upload attachments to this conversation
     * ([ago.chat.android.core.domain.conversations.ConversationSummary.hasAttachmentUploadGrant]'s own
     * meaning, re-read fresh rather than trusted from an earlier queue snapshot).
     *
     * [grantedAt]/[grantedByOperatorId] — the raw wire timestamp and granting operator's id, both `null`
     * while [granted] is `false` and whenever the server itself has neither (a row that predates the
     * pair). [grantedByOperatorId] is never resolved to a display name — the caption says "an operator
     * granted it", the identical "who/when, not a resolved name" posture `ago-console`'s own
     * `AttachmentUploadGrantToggle` states for the same two fields.
     *
     * [toggling] — `true` while one grant/revoke write (and the re-read that follows a successful one)
     * is in flight; the toggle control is disabled while this is set, the identical single-write
     * in-flight shape [NotesSectionState.Loaded.addingNote] already establishes for the notes composer.
     *
     * [actionError] — the last toggle outcome when it was not a success, shown non-destructively beneath
     * the control and cleared the moment a new toggle begins. [granted] is left exactly as it was
     * through either non-success arm — a refused or failed toggle never flips the control on screen.
     */
    public data class Loaded(
        val granted: Boolean,
        val grantedAt: String? = null,
        val grantedByOperatorId: String? = null,
        val toggling: Boolean = false,
        val actionError: AttachmentUploadActionError? = null,
    ) : AttachmentUploadSectionState

    public data class Failed(
        val reason: NetworkFailure,
    ) : AttachmentUploadSectionState
}

/**
 * `26-152`: why a grant/revoke toggle did not take — the two non-success arms of
 * [ago.chat.android.core.domain.conversationactions.ConversationActionResult], carried into the UI the
 * identical way [TagActionError]/[AddNoteError] already carry their own write's non-success arms.
 */
public sealed interface AttachmentUploadActionError {
    /** A genuine server refusal — its RFC 7807 `detail` shown verbatim, the same "show the server's own
     * sentence" posture [TagActionError.Refused] establishes. */
    public data class Refused(
        val detail: String,
    ) : AttachmentUploadActionError

    /** A transport failure — no server sentence to show, so the section renders one generic
     * «Не удалось изменить разрешение» line, classified by [reason] only if a caller ever needs to. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : AttachmentUploadActionError
}

/**
 * `26-153`: the reversible «Ограничить»/«Снять ограничение» action's own async state — the sixth and
 * final section slice to grow [ContactPanelUiState] the additive way that type's own doc comment
 * prescribes, reading and writing through
 * [ago.chat.android.core.domain.restrictions.VisitorRestrictionApi] (`26-145`). Fetched once at
 * panel-open time, the identical moment every sibling section's own read fires — [Loaded.restricted] is
 * what decides which of the two button labels this section draws, per design Q2/Author decision #2 ("one
 * reversible action").
 *
 * **A fourth arm, [Unavailable], beside the usual three.** Every other section here is keyed on the
 * conversation alone; this one needs the *visitor's* id ([ago.chat.android.core.domain.restrictions.VisitorRestrictionApi.block]
 * takes a conversation id, but [ago.chat.android.core.domain.restrictions.VisitorRestrictionApi.lift]
 * and the [ago.chat.android.core.domain.restrictions.VisitorRestrictionApi.isRestricted] read both take
 * the *visitor's* id instead). [ago.chat.android.thread.ThreadRoute]'s own `visitorId` is `Boolean?` for
 * exactly one honest reason — a restored thread with no matching queue row yet
 * (`ThreadRoute`'s own doc comment on `identityUnavailable`) — and in that one case this section has
 * nothing to check or act on. [Unavailable] names that case rather than reusing [Failed] for it: it is
 * not that a read failed, it is that there was no read to attempt, the identical "an absent fact is not a
 * failed one" distinction [ago.chat.android.core.domain.conversations.ConversationSummary.hasAttachmentUploadGrant]'s
 * own doc comment already draws for a `Boolean?` elsewhere in this app. The section draws nothing for
 * [Unavailable] — hidden, not an inline error an operator could do nothing about.
 */
public sealed interface RestrictionSectionState {
    public data object Loading : RestrictionSectionState

    /** No visitor id is known for this conversation yet — see this type's own doc comment. */
    public data object Unavailable : RestrictionSectionState

    /**
     * [restricted] — whether this visitor is currently blocked on this site
     * ([ago.chat.android.core.domain.restrictions.VisitorRestrictionApi.isRestricted]'s own membership
     * read); decides whether this section draws «Ограничить» (`false`) or «Снять ограничение» (`true`).
     *
     * [toggling] — `true` while one block/lift write is in flight; the button is disabled while this is
     * set, the identical single-write in-flight shape [AttachmentUploadSectionState.Loaded.toggling]
     * already establishes.
     *
     * [actionError] — the last toggle outcome when it was not a success, shown non-destructively beneath
     * the button and cleared the moment a new toggle begins. [restricted] is left exactly as it was
     * through either non-success arm — a refused or failed toggle never flips the button's own label.
     */
    public data class Loaded(
        val restricted: Boolean,
        val toggling: Boolean = false,
        val actionError: RestrictionActionError? = null,
    ) : RestrictionSectionState

    public data class Failed(
        val reason: NetworkFailure,
    ) : RestrictionSectionState
}

/**
 * `26-153`: why a block/lift toggle did not take — the two non-success arms of
 * [ago.chat.android.core.domain.restrictions.VisitorRestrictionActionResult], carried into the UI the
 * identical way [AttachmentUploadActionError]/[TagActionError] already carry their own write's
 * non-success arms.
 */
public sealed interface RestrictionActionError {
    /** A genuine server refusal — its RFC 7807 `detail` shown verbatim, the same "show the server's own
     * sentence" posture [AttachmentUploadActionError.Refused] establishes. */
    public data class Refused(
        val detail: String,
    ) : RestrictionActionError

    /** A transport failure — no server sentence to show, so the section renders one generic
     * «Не удалось изменить ограничение» line, classified by [reason] only if a caller ever needs to. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : RestrictionActionError
}

/**
 * `26-153`: why «Закрыть диалог» did not take — the two non-success arms of
 * [ago.chat.android.core.domain.conversationactions.ConversationActionResult], carried into the UI the
 * identical way [RestrictionActionError]/[AttachmentUploadActionError] already carry their own write's
 * non-success arms. There is no `Loading`/`Loaded` counterpart for this action
 * ([ContactPanelUiState.closing]'s own doc comment states why) — only this one error type is new here.
 */
public sealed interface CloseActionError {
    /** A genuine server refusal — its RFC 7807 `detail` shown verbatim, the same "show the server's own
     * sentence" posture [RestrictionActionError.Refused] establishes. */
    public data class Refused(
        val detail: String,
    ) : CloseActionError

    /** A transport failure — no server sentence to show, so the section renders one generic
     * «Не удалось закрыть диалог» line, classified by [reason] only if a caller ever needs to. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : CloseActionError
}
