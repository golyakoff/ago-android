package ago.chat.android.thread.contactpanel

import ago.chat.android.core.domain.contactdetails.ContactDetailWriteResult
import ago.chat.android.core.domain.contactdetails.ContactDetailsApi
import ago.chat.android.core.domain.contactdetails.ContactDetailsResult
import ago.chat.android.core.domain.contactdetails.RevealContactDetailResult
import ago.chat.android.core.domain.conversationactions.ConversationActionResult
import ago.chat.android.core.domain.conversationactions.ConversationActionsApi
import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.domain.conversations.QueueResult
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.notes.AddNoteResult
import ago.chat.android.core.domain.notes.ConversationNotesApi
import ago.chat.android.core.domain.notes.ConversationNotesResult
import ago.chat.android.core.domain.restrictions.VisitorRestrictionActionResult
import ago.chat.android.core.domain.restrictions.VisitorRestrictionApi
import ago.chat.android.core.domain.restrictions.VisitorRestrictionStatusResult
import ago.chat.android.core.domain.tags.ConversationTag
import ago.chat.android.core.domain.tags.ConversationTagsApi
import ago.chat.android.core.domain.tags.ConversationTagsResult
import ago.chat.android.core.domain.tags.TagActionResult
import ago.chat.android.core.domain.tags.TagVocabularyResult
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryApi
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryResult
import ago.chat.android.core.domain.visitorsummary.VisitorSummaryApi
import ago.chat.android.core.domain.visitorsummary.VisitorSummaryResult
import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.di.IoDispatcher
import ago.chat.android.thread.HISTORY_PAGE_SIZE
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-147`: the contact-detail panel's own view model. It began holding only the header's async half
 * (H4/H5), read once through [VisitorSummaryApi] (`26-143`); `26-148` grew it the way its own doc comment
 * prescribes for the section slices — a second port ([ContactDetailsApi], `26-115`) beside
 * [visitorSummaryApi], its own load/retry/reveal methods, and a new arm
 * ([ContactPanelUiState.contactDetails]) folded into the state rather than a restructure of this class.
 * `26-149` (tags) and `26-150` (notes) grow it the identical way, each its own port
 * ([ConversationTagsApi]/[ConversationNotesApi]) beside the ones already here. `26-151` (past dialogs)
 * grows it once more, with **two** collaborators rather than one — [VisitorHistoryApi] for the list
 * (`26-144`'s REST port) and [OperatorHubEvents] for opening one past conversation read-only
 * ([OperatorHubEvents.getVisitorHistoryConversation], the same hub connection [ago.chat.android.thread.ThreadViewModel]
 * already depends on for the *live* conversation) — because the design itself splits the two that way
 * (`VisitorHistoryApi`'s own doc comment: "a thin REST list, a hub read to open one"). The remaining
 * sections (`26-152`…`26-153`) grow it the same additive way again.
 *
 * `26-169` (`docs/design/26-156-*.md`, over the write client `26-167` landed on [contactDetailsApi]): no
 * new collaborator — [startEditContactDetail]/[onEditContactDetailDraftChanged]/[cancelEditContactDetail]/
 * [saveEditContactDetail]/[setContactDetailAssessment] are the КОНТАКТНЫЕ ДАННЫЕ section's own second and
 * third writes, added the identical way [revealContactDetail] already established its first. This class
 * stays permission-agnostic here too (`conversation:send` gates the row `⋮`'s own affordances in the UI
 * layer, [ago.chat.android.thread.contactpanel.sections.ContactDetailsSection]'s own doc comment) — it
 * always exposes the writes, whether or not the operator actually holds the permission to trigger them.
 *
 * **Opened, not injected-with-an-id.** [open] hands the conversation id in, the identical shape
 * [ago.chat.android.thread.ThreadViewModel.open] already establishes — this class is scoped to the open
 * thread (`hiltViewModel()` at [ago.chat.android.thread.ThreadRoute]'s level, one instance per
 * back-stack entry), and the id is a runtime fact the panel opens *with*, not a construction-time one a
 * `SavedStateHandle` would carry. The alternative — a `SavedStateHandle`-provided id — would tie this
 * class to the navigation argument shape and make a plain JVM test construct one just to exercise a
 * single read.
 *
 * The dependency rule is what puts [VisitorSummaryApi] behind an interface in `:core:domain`: a view
 * model holding a Ktor `HttpClient` could not be unit-tested without one, and every HTTP-shaped decision
 * lives on the far side of that port (its own doc comment). Here the class only sequences the read and
 * maps its two-arm result onto the UI's three-arm [HeaderSummaryState].
 */
@HiltViewModel
public class ContactPanelViewModel
    @Inject
    constructor(
        private val visitorSummaryApi: VisitorSummaryApi,
        private val contactDetailsApi: ContactDetailsApi,
        private val conversationTagsApi: ConversationTagsApi,
        private val conversationNotesApi: ConversationNotesApi,
        private val visitorHistoryApi: VisitorHistoryApi,
        private val hubEvents: OperatorHubEvents,
        // `26-152`: [conversationsApi] is the same shipped `26-14` port `ConversationListViewModel` reads
        // the queue through — reused rather than a new per-conversation read, because there is no
        // per-conversation grant-status endpoint gated the way this panel needs
        // ([ago.chat.android.core.domain.conversationactions.ConversationActionsApi]'s own doc comment
        // names the queue re-read as the mechanism). [conversationActionsApi] is `26-146`'s own shipped
        // port for the grant/revoke writes themselves — reused as-is, never rebuilt.
        private val conversationsApi: ConversationsApi,
        private val conversationActionsApi: ConversationActionsApi,
        // `26-153`: [visitorRestrictionApi] is `26-145`'s own shipped port for the reversible
        // «Ограничить»/«Снять ограничение» action (block/lift/is-restricted) — reused as-is, never
        // rebuilt, the identical "grow the constructor with one more collaborator" shape every section
        // slice above already takes.
        private val visitorRestrictionApi: VisitorRestrictionApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(ContactPanelUiState())
        public val state: StateFlow<ContactPanelUiState> = mutableState.asStateFlow()

        /** `26-153`: a one-shot signal that «Закрыть диалог» succeeded — a [Channel], not a [StateFlow],
         * the identical "an event, not a state, or a rotation replays it" reasoning
         * [ago.chat.android.signin.SignInViewModel]'s own `authorizationIntents`/`signOutIntents` already
         * establish for this codebase's other fire-once signals. [ago.chat.android.thread.ThreadRoute]
         * collects this to dismiss the sheet and leave the thread, returning the operator to the queue —
         * navigation this view model has no way to perform itself, and should not: it only ever reports
         * that the write landed. */
        private val conversationClosedEvents = Channel<Unit>(Channel.BUFFERED)
        public val conversationClosed: Flow<Unit> = conversationClosedEvents.receiveAsFlow()

        /** The conversation the header is currently loaded (or loading) for — `null` before the first
         * [open]. Guards a re-open of the same conversation from re-issuing the read, while still letting
         * a *different* conversation, or a retry after a failure, start a fresh one. */
        private var loadedConversationId: String? = null

        /** `26-153`: the visitor id [open] was last called with — `null` whenever the current conversation
         * has none (`ThreadRoute`'s own `identityUnavailable` edge case), which is exactly when
         * [RestrictionSectionState.Unavailable] applies and [toggleRestriction]'s own `lift` call has no
         * visitor id to address. Held separately from [loadedConversationId] because
         * [ago.chat.android.core.domain.restrictions.VisitorRestrictionApi.lift] and
         * [ago.chat.android.core.domain.restrictions.VisitorRestrictionApi.isRestricted] are addressed by
         * *visitor*, not by conversation — the one section on this panel that needs both ids in hand. */
        private var loadedVisitorId: String? = null

        /**
         * Opens the panel on one conversation and fetches everything it reads for the first time: the
         * visitor summary (H4/H5, `26-147`) and the КОНТАКТНЫЕ ДАННЫЕ rows (`26-148`). Called from
         * [ago.chat.android.thread.ThreadRoute] the moment the operator taps the open affordance —
         * itself only ever present when the operator holds `conversation:read`, which is the same
         * capability the contact-details read is gated on server-side
         * ([ago.chat.android.core.domain.contactdetails.ContactDetailsApi]'s own contract), so no
         * second gate is needed here.
         *
         * A repeat call for the conversation already loaded re-issues **only** the arms that are sitting
         * in a `Failed` state — so reopening the sheet after a section failed is itself a retry, while a
         * plain reopen of a healthy panel is a no-op and never re-reads a section that already landed. A
         * genuinely different conversation reloads both arms fresh.
         *
         * `26-153`: [visitorId] is a second, independent key beside [conversationId], because the
         * restriction section alone needs the *visitor's* id, not just the conversation's — and
         * [ago.chat.android.thread.ThreadRoute]'s own `visitorId` can genuinely change from `null` to a
         * real value on the *same* conversation once a restored thread's matching queue row lands
         * (`ThreadRoute`'s own doc comment on `identityUnavailable`), with no `conversationId` change to
         * key a fresh call on. [loadRestriction] therefore reloads whenever the visitor id itself changed,
         * even when [conversationId] did not — every other section here still only cares whether the
         * conversation changed.
         */
        public fun open(
            conversationId: String,
            visitorId: String? = null,
        ) {
            val sameConversation = loadedConversationId == conversationId
            val sameVisitor = sameConversation && loadedVisitorId == visitorId
            loadedConversationId = conversationId
            loadedVisitorId = visitorId
            if (!sameConversation || mutableState.value.summary is HeaderSummaryState.Failed) {
                loadSummary(conversationId)
            }
            if (!sameConversation || mutableState.value.contactDetails is ContactDetailsSectionState.Failed) {
                loadContactDetails(conversationId)
            }
            if (!sameConversation || mutableState.value.tags is TagsSectionState.Failed) {
                loadTags(conversationId)
            }
            if (!sameConversation || mutableState.value.notes is NotesSectionState.Failed) {
                loadNotes(conversationId)
            }
            if (!sameConversation || mutableState.value.pastDialogs is PastDialogsSectionState.Failed) {
                loadPastDialogs(conversationId)
            }
            if (!sameConversation || mutableState.value.attachmentUpload is AttachmentUploadSectionState.Failed) {
                loadAttachmentUpload(conversationId)
            }
            if (!sameVisitor || mutableState.value.restriction is RestrictionSectionState.Failed) {
                loadRestriction(conversationId, visitorId)
            }
        }

        /** The header's own inline retry (§4: per-section retry, never a whole-sheet failure) — re-reads
         * the summary for whatever conversation is currently open, a no-op before the first [open]. */
        public fun retry() {
            val conversationId = loadedConversationId ?: return
            loadSummary(conversationId)
        }

        /** `26-148`: the КОНТАКТНЫЕ ДАННЫЕ section's own inline retry — the section's sibling of [retry],
         * re-reading only its own rows and leaving the header untouched (§4: a slow or failed section never
         * fails the whole sheet). A no-op before the first [open]. */
        public fun retryContactDetails() {
            val conversationId = loadedConversationId ?: return
            loadContactDetails(conversationId)
        }

        /** `26-149`: the tags section's own inline retry — the sibling of [retry]/[retryContactDetails],
         * re-reading only its own two lists (applied tags + site vocabulary) and leaving the rest of the
         * sheet untouched (§4). A no-op before the first [open]. */
        public fun retryTags() {
            val conversationId = loadedConversationId ?: return
            loadTags(conversationId)
        }

        /** `26-150`: the «Заметки команды» row's own inline retry — the sibling of [retry]/
         * [retryContactDetails]/[retryTags], re-reading only the notes list and leaving the rest of the
         * sheet untouched (§4). A no-op before the first [open]. */
        public fun retryNotes() {
            val conversationId = loadedConversationId ?: return
            loadNotes(conversationId)
        }

        /** `26-151`: the «Прошлые диалоги» row's own inline retry — the sibling of [retry]/
         * [retryContactDetails]/[retryTags]/[retryNotes], re-reading only the past-dialogs list and
         * leaving the rest of the sheet untouched (§4). A no-op before the first [open]. */
        public fun retryPastDialogs() {
            val conversationId = loadedConversationId ?: return
            loadPastDialogs(conversationId)
        }

        /** `26-152`: the «Приём файлов от посетителя» toggle's own inline retry — the sibling of [retry]/
         * [retryContactDetails]/[retryTags]/[retryNotes]/[retryPastDialogs], re-reading only this
         * section's own grant status and leaving the rest of the sheet untouched (§4). A no-op before the
         * first [open]. */
        public fun retryAttachmentUpload() {
            val conversationId = loadedConversationId ?: return
            loadAttachmentUpload(conversationId)
        }

        /**
         * `26-152`: flips the visitor's attachment-upload permission the other way — grants it when it is
         * currently off, revokes it when it is currently on, through [conversationActionsApi] (`26-146`,
         * reused as-is). A no-op while a toggle is already [AttachmentUploadSectionState.Loaded.toggling]
         * (one tap, one server call) or before the section has landed [AttachmentUploadSectionState.Loaded]
         * — there is nothing to flip before that.
         *
         * **Update-after-`2xx`, via a re-read, not a locally fabricated result.** On
         * [ConversationActionResult.Succeeded] this method does not flip [AttachmentUploadSectionState.Loaded.granted]
         * itself from client-side knowledge — unlike [applyTag]'s own vocabulary-entry shortcut, this write's
         * own `200` body carries a grant status this adapter deliberately does not parse
         * ([ConversationActionsApi.grantAttachmentUpload]'s own doc comment), so the only honest source for
         * the resulting granted/who/when is a fresh [fetchAttachmentUploadState] — the same "re-reads the
         * conversation for the caption's own who/when" mechanism that doc comment names for this exact
         * item. [AttachmentUploadSectionState.Loaded.toggling] stays `true` through that re-read, so the
         * control stays disabled (not a flash back to the section's own skeleton) until the fresh answer
         * lands. On [ConversationActionResult.Refused]/[ConversationActionResult.Failed] the granted flag is
         * left exactly as it was and the error is surfaced beneath the control — the identical
         * refused-verbatim / failed-generic split [finishTagAction] already draws for its own write.
         */
        public fun toggleAttachmentUpload() {
            val conversationId = loadedConversationId ?: return
            val loaded = mutableState.value.attachmentUpload as? AttachmentUploadSectionState.Loaded ?: return
            if (loaded.toggling) return
            val granting = !loaded.granted

            mutableState.update { current ->
                val currentLoaded = current.attachmentUpload as? AttachmentUploadSectionState.Loaded ?: return@update current
                current.copy(attachmentUpload = currentLoaded.copy(toggling = true, actionError = null))
            }
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        if (granting) {
                            conversationActionsApi.grantAttachmentUpload(conversationId)
                        } else {
                            conversationActionsApi.revokeAttachmentUpload(conversationId)
                        }
                    }
                if (loadedConversationId != conversationId) return@launch
                when (result) {
                    ConversationActionResult.Succeeded -> {
                        val next = fetchAttachmentUploadState(conversationId)
                        if (loadedConversationId != conversationId) return@launch
                        mutableState.update { current -> current.copy(attachmentUpload = next) }
                    }

                    is ConversationActionResult.Refused ->
                        mutableState.update { current ->
                            val currentLoaded =
                                current.attachmentUpload as? AttachmentUploadSectionState.Loaded ?: return@update current
                            current.copy(
                                attachmentUpload =
                                    currentLoaded.copy(
                                        toggling = false,
                                        actionError = AttachmentUploadActionError.Refused(result.detail),
                                    ),
                            )
                        }

                    is ConversationActionResult.Failed ->
                        mutableState.update { current ->
                            val currentLoaded =
                                current.attachmentUpload as? AttachmentUploadSectionState.Loaded ?: return@update current
                            current.copy(
                                attachmentUpload =
                                    currentLoaded.copy(
                                        toggling = false,
                                        actionError = AttachmentUploadActionError.Failed(result.reason),
                                    ),
                            )
                        }
                }
            }
        }

        /** `26-153`: the reversible «Ограничить»/«Снять ограничение» section's own inline retry — the
         * sibling of [retry]/[retryContactDetails]/[retryTags]/[retryNotes]/[retryPastDialogs]/
         * [retryAttachmentUpload], re-reading only the restriction status and leaving the rest of the
         * sheet untouched (§4). A no-op before the first [open]. */
        public fun retryRestriction() {
            val conversationId = loadedConversationId ?: return
            loadRestriction(conversationId, loadedVisitorId)
        }

        /**
         * `26-153`: flips the visitor's block status the other way — blocks when currently unrestricted,
         * lifts when currently restricted, through [visitorRestrictionApi] (`26-145`, reused as-is). A
         * no-op while a toggle is already [RestrictionSectionState.Loaded.toggling] (one confirm dialog,
         * one server call) or before the section has landed [RestrictionSectionState.Loaded] — there is
         * nothing to flip before that, and [RestrictionSectionState.Unavailable] never reaches this method
         * at all (the UI draws no button to tap in that arm — [ago.chat.android.thread.contactpanel.sections.ConversationActionsSection]'s
         * own doc comment).
         *
         * **Update-after-`2xx`, from the write's own known shape, not a re-read.** Unlike
         * [toggleAttachmentUpload]'s own re-read after a successful write, this method flips
         * [RestrictionSectionState.Loaded.restricted] to the value it *asked for* the moment
         * [VisitorRestrictionActionResult.Succeeded] lands, rather than calling
         * [VisitorRestrictionApi.isRestricted] again — that read is a keyset-paged walk of every active
         * restriction on the site ([VisitorRestrictionApi.isRestricted]'s own doc comment: "the adapter
         * follows the cursor to the end"), a genuinely expensive re-read for a fact this call already
         * knows for certain: a `Succeeded` [VisitorRestrictionApi.block] leaves the visitor blocked, a
         * `Succeeded` [VisitorRestrictionApi.lift] leaves them not. On
         * [VisitorRestrictionActionResult.Refused]/[VisitorRestrictionActionResult.Failed] the flag is left
         * exactly as it was and the error is surfaced beneath the button — the identical refused-verbatim
         * / failed-generic split every other write on this class already draws.
         */
        public fun toggleRestriction() {
            val conversationId = loadedConversationId ?: return
            val visitorId = loadedVisitorId ?: return
            val loaded = mutableState.value.restriction as? RestrictionSectionState.Loaded ?: return
            if (loaded.toggling) return
            val blocking = !loaded.restricted

            mutableState.update { current ->
                val currentLoaded = current.restriction as? RestrictionSectionState.Loaded ?: return@update current
                current.copy(restriction = currentLoaded.copy(toggling = true, actionError = null))
            }
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        if (blocking) visitorRestrictionApi.block(conversationId) else visitorRestrictionApi.lift(visitorId)
                    }
                if (loadedConversationId != conversationId) return@launch
                mutableState.update { current ->
                    val currentLoaded = current.restriction as? RestrictionSectionState.Loaded ?: return@update current
                    val next =
                        when (result) {
                            VisitorRestrictionActionResult.Succeeded ->
                                currentLoaded.copy(restricted = blocking, toggling = false, actionError = null)

                            is VisitorRestrictionActionResult.Refused ->
                                currentLoaded.copy(toggling = false, actionError = RestrictionActionError.Refused(result.detail))

                            is VisitorRestrictionActionResult.Failed ->
                                currentLoaded.copy(toggling = false, actionError = RestrictionActionError.Failed(result.reason))
                        }
                    current.copy(restriction = next)
                }
            }
        }

        /**
         * `26-153`: «Закрыть диалог» — the panel's own copy of the ordinary close action
         * ([ago.chat.android.conversations.ConversationListViewModel]'s own `requestErasure` is the
         * closest sibling shape, a confirm-then-write with no `Loading`/`Loaded` arm of its own), reached
         * only after the confirm dialog
         * ([ago.chat.android.thread.contactpanel.sections.ConversationActionsSection]'s own doc comment).
         * A no-op while [ContactPanelUiState.closing] is already `true` (one confirm dialog, one server
         * call) or before the first [open].
         *
         * On [ConversationActionResult.Succeeded] this method does not touch [ContactPanelUiState.closing]
         * back to `false` before firing [conversationClosedEvents] — the sheet is about to be dismissed by
         * the collector on the other end
         * ([ago.chat.android.thread.ThreadViewModel]'s own [ago.chat.android.thread.ThreadRoute] doc
         * comment on why the panel VM is only ever read while the sheet is up), so there is no button left
         * on screen for a lingering `closing = true` to disable. On
         * [ConversationActionResult.Refused]/[ConversationActionResult.Failed] the sheet stays up exactly
         * as it was and the error is surfaced beneath the button — the identical refused-verbatim /
         * failed-generic split every other write on this class already draws.
         */
        public fun closeConversation() {
            val conversationId = loadedConversationId ?: return
            if (mutableState.value.closing) return

            mutableState.update { it.copy(closing = true, closeError = null) }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { conversationActionsApi.close(conversationId) }
                if (loadedConversationId != conversationId) return@launch
                when (result) {
                    ConversationActionResult.Succeeded -> conversationClosedEvents.send(Unit)

                    is ConversationActionResult.Refused ->
                        mutableState.update { it.copy(closing = false, closeError = CloseActionError.Refused(result.detail)) }

                    is ConversationActionResult.Failed ->
                        mutableState.update { it.copy(closing = false, closeError = CloseActionError.Failed(result.reason)) }
                }
            }
        }

        /**
         * `26-151`: pages one further batch of past dialogs onto the list already on screen — a no-op
         * unless the section is [PastDialogsSectionState.Loaded], the cursor is not yet exhausted
         * ([PastDialogsSectionState.Loaded.nextBeforeId] non-null), and no such fetch is already in
         * flight ([PastDialogsSectionState.Loaded.loadingMore]), the identical single-flight guard
         * [applyTag]/[removeTag] apply per-tag and [revealContactDetail] applies per-row, here for the
         * section's one list instead. A failed page leaves [PastDialogsSectionState.Loaded.nextBeforeId]
         * exactly as it was — a tap on the same "load more" control simply asks again — rather than
         * inventing a dedicated error arm for a read that is not the section's primary content (the
         * identical "a secondary read degrades non-destructively" posture [loadTags]'s own doc comment
         * states for a failed vocabulary read).
         */
        public fun loadMorePastDialogs() {
            val conversationId = loadedConversationId ?: return
            val loaded = mutableState.value.pastDialogs as? PastDialogsSectionState.Loaded ?: return
            val beforeId = loaded.nextBeforeId ?: return
            if (loaded.loadingMore) return

            mutableState.update { current ->
                val currentLoaded = current.pastDialogs as? PastDialogsSectionState.Loaded ?: return@update current
                current.copy(pastDialogs = currentLoaded.copy(loadingMore = true))
            }
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        visitorHistoryApi.fetchVisitorHistory(conversationId, beforeId = beforeId, pageSize = PAST_DIALOGS_PAGE_SIZE)
                    }
                if (loadedConversationId != conversationId) return@launch
                mutableState.update { current ->
                    val currentLoaded = current.pastDialogs as? PastDialogsSectionState.Loaded ?: return@update current
                    val next =
                        when (result) {
                            is VisitorHistoryResult.Loaded ->
                                currentLoaded.copy(
                                    conversations = currentLoaded.conversations + result.page.conversations,
                                    nextBeforeId = result.page.nextBeforeId,
                                    loadingMore = false,
                                )

                            is VisitorHistoryResult.Failed -> currentLoaded.copy(loadingMore = false)
                        }
                    current.copy(pastDialogs = next)
                }
            }
        }

        /**
         * `26-151`: opens one past conversation's read-only transcript — sets
         * [PastDialogsSectionState.Loaded.selectedConversationId] and starts
         * [PastDialogsSectionState.Loaded.history] at [PastDialogHistoryState.Loading], then fetches its
         * most recent page ([beforeSequence] `null`, [OperatorHubEvents.getVisitorHistoryConversation]'s
         * own "initial page" convention). A no-op unless the list itself is
         * [PastDialogsSectionState.Loaded] — there is nothing to open before it lands.
         */
        public fun openPastDialog(historicalConversationId: String) {
            val conversationId = loadedConversationId ?: return
            mutableState.update { current ->
                val loaded = current.pastDialogs as? PastDialogsSectionState.Loaded ?: return@update current
                current.copy(
                    pastDialogs =
                        loaded.copy(
                            selectedConversationId = historicalConversationId,
                            history = PastDialogHistoryState.Loading,
                        ),
                )
            }
            fetchPastDialogHistory(conversationId, historicalConversationId, beforeSequence = null, appendOlder = false)
        }

        /** `26-151`: leaves the transcript and returns to the past-dialogs list — the sub-screen's own
         * nested "back", never dismissing the sub-screen itself (that is [ContactDetailPanel]'s / the
         * section's own outer dismiss). A no-op unless the list is [PastDialogsSectionState.Loaded]. */
        public fun closePastDialogHistory() {
            mutableState.update { current ->
                val loaded = current.pastDialogs as? PastDialogsSectionState.Loaded ?: return@update current
                current.copy(pastDialogs = loaded.copy(selectedConversationId = null, history = null))
            }
        }

        /** `26-151`: the open transcript's own inline retry, the sibling of [retry]/[retryContactDetails]/
         * [retryTags]/[retryNotes]/[retryPastDialogs] one level deeper — re-fetches the same past
         * conversation's most recent page. A no-op unless a transcript is actually open. */
        public fun retryPastDialogHistory() {
            val conversationId = loadedConversationId ?: return
            val loaded = mutableState.value.pastDialogs as? PastDialogsSectionState.Loaded ?: return
            val historicalConversationId = loaded.selectedConversationId ?: return
            mutableState.update { current ->
                val currentLoaded = current.pastDialogs as? PastDialogsSectionState.Loaded ?: return@update current
                current.copy(pastDialogs = currentLoaded.copy(history = PastDialogHistoryState.Loading))
            }
            fetchPastDialogHistory(conversationId, historicalConversationId, beforeSequence = null, appendOlder = false)
        }

        /**
         * `26-151`: the open transcript's own manual "load older messages" affordance — the identical
         * shape [ago.chat.android.thread.ThreadViewModel.loadOlder] takes for the *live* conversation,
         * restated for a read-only one: a no-op while already loading or once the cursor is exhausted,
         * and older pages are **prepended** ([PastDialogHistoryState.Loaded.messages] stays ascending by
         * sequence, [ContactPanelUiState.kt]'s own doc comment on that field).
         */
        public fun loadOlderPastDialogHistory() {
            val conversationId = loadedConversationId ?: return
            val loaded = mutableState.value.pastDialogs as? PastDialogsSectionState.Loaded ?: return
            val historicalConversationId = loaded.selectedConversationId ?: return
            val history = loaded.history as? PastDialogHistoryState.Loaded ?: return
            val beforeSequence = history.nextBeforeSequence ?: return
            if (history.loadingOlder) return

            mutableState.update { current ->
                val currentLoaded = current.pastDialogs as? PastDialogsSectionState.Loaded ?: return@update current
                val currentHistory = currentLoaded.history as? PastDialogHistoryState.Loaded ?: return@update current
                current.copy(
                    pastDialogs = currentLoaded.copy(history = currentHistory.copy(loadingOlder = true, historyError = null)),
                )
            }
            fetchPastDialogHistory(conversationId, historicalConversationId, beforeSequence = beforeSequence, appendOlder = true)
        }

        /**
         * The one place [OperatorHubEvents.getVisitorHistoryConversation] is actually called, shared by
         * [openPastDialog] (fresh, [appendOlder] `false`), [retryPastDialogHistory] (fresh, after a
         * failure) and [loadOlderPastDialogHistory] ([appendOlder] `true`, prepending onto what is already
         * loaded). Unlike the REST ports on this class, the hub call is a plain `suspend` returning
         * [ago.chat.android.core.network.realtime.HistoryPage] rather than a two-arm sealed result, so this
         * function classifies a thrown exception into [NetworkFailure] itself — the identical
         * try/catch-and-classify shape [ago.chat.android.thread.ThreadViewModel.loadOlder] already uses for
         * the same hub connection's [OperatorHubEvents.loadOlderHistory].
         *
         * Two guards keep a stale answer from corrupting a screen the operator has since moved off:
         * dropped outright if the panel has since opened a *different conversation*
         * ([loadedConversationId] changed — the same guard every other read on this class applies), and
         * folded back only if [historicalConversationId] still matches
         * [PastDialogsSectionState.Loaded.selectedConversationId] — an operator can close a transcript and
         * open a *different* one before a slow fetch for the first returns, and that first answer must
         * never overwrite the second transcript's own state.
         */
        private fun fetchPastDialogHistory(
            conversationId: String,
            historicalConversationId: String,
            beforeSequence: Long?,
            appendOlder: Boolean,
        ) {
            viewModelScope.launch {
                try {
                    val page =
                        withContext(ioDispatcher) {
                            hubEvents.getVisitorHistoryConversation(
                                conversationId,
                                historicalConversationId,
                                beforeSequence,
                                HISTORY_PAGE_SIZE,
                            )
                        }
                    if (loadedConversationId != conversationId) return@launch
                    mutableState.update { current ->
                        val currentLoaded = current.pastDialogs as? PastDialogsSectionState.Loaded ?: return@update current
                        if (currentLoaded.selectedConversationId != historicalConversationId) return@update current
                        val existing = currentLoaded.history as? PastDialogHistoryState.Loaded
                        val nextHistory =
                            PastDialogHistoryState.Loaded(
                                messages = if (appendOlder) page.messages + (existing?.messages ?: emptyList()) else page.messages,
                                nextBeforeSequence = page.nextBeforeSequence,
                            )
                        current.copy(pastDialogs = currentLoaded.copy(history = nextHistory))
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    if (loadedConversationId != conversationId) return@launch
                    mutableState.update { current ->
                        val currentLoaded = current.pastDialogs as? PastDialogsSectionState.Loaded ?: return@update current
                        if (currentLoaded.selectedConversationId != historicalConversationId) return@update current
                        val existing = currentLoaded.history as? PastDialogHistoryState.Loaded
                        val reason = NetworkFailure.from(failure)
                        val nextHistory =
                            if (appendOlder && existing != null) {
                                existing.copy(loadingOlder = false, historyError = reason)
                            } else {
                                PastDialogHistoryState.Failed(reason)
                            }
                        current.copy(pastDialogs = currentLoaded.copy(history = nextHistory))
                    }
                }
            }
        }

        /** `26-150`: every keystroke in the notes sub-screen's composer. A no-op unless the section is
         * currently [NotesSectionState.Loaded] — there is no composer to type into before the notes list
         * itself has landed. */
        public fun onNoteDraftChanged(text: String) {
            mutableState.update { current ->
                val loaded = current.notes as? NotesSectionState.Loaded ?: return@update current
                current.copy(notes = loaded.copy(draft = text))
            }
        }

        /**
         * `26-150`: submits the composer's current draft as a new team note. Mirrors [applyTag]'s write
         * idiom: a no-op while an add is already [NotesSectionState.Loaded.addingNote] (one tap, one
         * server call), and a no-op for a blank (or whitespace-only) draft — the same "nothing to send"
         * guard [ago.chat.android.thread.ThreadViewModel.sendClicked] applies to the message composer. On
         * [AddNoteResult.Added] the server's own created row is appended to [NotesSectionState.Loaded.notes]
         * and the draft is cleared; on [AddNoteResult.Refused] the server's `detail` is surfaced verbatim
         * and on [AddNoteResult.Failed] a generic transport line is surfaced — in both non-success cases the
         * typed [NotesSectionState.Loaded.draft] is deliberately left exactly as it was (that state's own
         * doc comment), so a refused note is fixable rather than retyped from scratch. Update-after-`2xx`,
         * not optimistic: the list only grows once the server confirms, the identical shape
         * [revealContactDetail]/[applyTag] already establish for their own writes.
         */
        public fun addNote() {
            val conversationId = loadedConversationId ?: return
            val loaded = mutableState.value.notes as? NotesSectionState.Loaded ?: return
            if (loaded.addingNote) return
            val body = loaded.draft.trim()
            if (body.isEmpty()) return

            mutableState.update { current ->
                val currentLoaded = current.notes as? NotesSectionState.Loaded ?: return@update current
                current.copy(notes = currentLoaded.copy(addingNote = true, addNoteError = null))
            }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { conversationNotesApi.addNote(conversationId, body) }
                if (loadedConversationId != conversationId) return@launch
                mutableState.update { current ->
                    val currentLoaded = current.notes as? NotesSectionState.Loaded ?: return@update current
                    val next =
                        when (result) {
                            is AddNoteResult.Added ->
                                currentLoaded.copy(
                                    notes = currentLoaded.notes + result.note,
                                    draft = "",
                                    addingNote = false,
                                    addNoteError = null,
                                )

                            is AddNoteResult.Refused ->
                                currentLoaded.copy(addingNote = false, addNoteError = AddNoteError.Refused(result.detail))

                            is AddNoteResult.Failed ->
                                currentLoaded.copy(addingNote = false, addNoteError = AddNoteError.Failed(result.reason))
                        }
                    current.copy(notes = next)
                }
            }
        }

        /**
         * `26-149`: applies one site-vocabulary tag to this conversation. Mirrors [revealContactDetail]'s
         * write idiom: a tag already in [TagsSectionState.Loaded.pendingTagIds] is a no-op (one tap, one
         * server call per tag); on [TagActionResult.Succeeded] the tag is added to [applied] in place, built
         * from the vocabulary entry the picker offered (its [tagId], its name and its `createdAt`) with
         * `source = "Operator"` — the operator applying it now; on [TagActionResult.Refused] the applied set
         * is left untouched and the server's `detail` surfaced verbatim; on [TagActionResult.Failed] a
         * generic transport line is surfaced. Update-after-`204`, not truly optimistic: the applied set
         * changes only once the server confirms, the identical "the state changes when the result lands, an
         * in-flight flag holds until then" shape [revealContactDetail] draws — the alternative, a re-read of
         * `fetchConversationTags` after every write, would add a round trip and race with a realtime edit for
         * a mutation this app already knows the exact shape of. A no-op unless the section is [Loaded] and the
         * tag is in [vocabulary] but not already [applied].
         */
        public fun applyTag(tagId: String) {
            val conversationId = loadedConversationId ?: return
            val loaded = mutableState.value.tags as? TagsSectionState.Loaded ?: return
            if (tagId in loaded.pendingTagIds) return
            val vocabularyEntry = loaded.vocabulary.firstOrNull { it.id == tagId } ?: return
            if (loaded.applied.any { it.id == tagId }) return

            markTagPending(tagId)
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { conversationTagsApi.applyTag(conversationId, tagId) }
                if (loadedConversationId != conversationId) return@launch
                finishTagAction(tagId, result) { current ->
                    current.copy(
                        applied =
                            current.applied +
                                ConversationTag(
                                    id = vocabularyEntry.id,
                                    name = vocabularyEntry.name,
                                    createdAt = vocabularyEntry.createdAt,
                                    source = OPERATOR_TAG_SOURCE,
                                ),
                    )
                }
            }
        }

        /**
         * `26-149`: removes one applied tag from this conversation. The [applyTag] flow in reverse — an
         * in-flight guard on [TagsSectionState.Loaded.pendingTagIds], update-after-`204` (the tag drops from
         * [applied] only once the server confirms), and the same refused-verbatim / failed-generic error
         * handling that leaves the applied set untouched on a non-success. A no-op unless the section is
         * [Loaded] and the tag is currently [applied].
         */
        public fun removeTag(tagId: String) {
            val conversationId = loadedConversationId ?: return
            val loaded = mutableState.value.tags as? TagsSectionState.Loaded ?: return
            if (tagId in loaded.pendingTagIds) return
            if (loaded.applied.none { it.id == tagId }) return

            markTagPending(tagId)
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { conversationTagsApi.removeTag(conversationId, tagId) }
                if (loadedConversationId != conversationId) return@launch
                finishTagAction(tagId, result) { current ->
                    current.copy(applied = current.applied.filterNot { it.id == tagId })
                }
            }
        }

        /** Marks [tagId]'s write in flight and clears any stale error about a previous attempt on it — the
         * same "clear the row's error the moment a new write begins" moment [revealContactDetail] establishes
         * for the reveal. A no-op unless the section is [TagsSectionState.Loaded]. */
        private fun markTagPending(tagId: String) {
            mutableState.update { current ->
                val loaded = current.tags as? TagsSectionState.Loaded ?: return@update current
                current.copy(
                    tags =
                        loaded.copy(
                            pendingTagIds = loaded.pendingTagIds + tagId,
                            actionError = null,
                        ),
                )
            }
        }

        /** Folds one apply/remove result back into the section: clears [tagId]'s in-flight flag, then on
         * success applies [onSucceeded] (the caller's own add-or-drop of the applied list) and on a
         * non-success leaves the applied set exactly as it was and records the error. A no-op unless the
         * section is still [TagsSectionState.Loaded]. */
        private fun finishTagAction(
            tagId: String,
            result: TagActionResult,
            onSucceeded: (TagsSectionState.Loaded) -> TagsSectionState.Loaded,
        ) {
            mutableState.update { current ->
                val loaded = current.tags as? TagsSectionState.Loaded ?: return@update current
                val cleared = loaded.copy(pendingTagIds = loaded.pendingTagIds - tagId)
                val next =
                    when (result) {
                        TagActionResult.Succeeded -> onSucceeded(cleared)
                        is TagActionResult.Refused -> cleared.copy(actionError = TagActionError.Refused(result.detail))
                        is TagActionResult.Failed -> cleared.copy(actionError = TagActionError.Failed(result.reason))
                    }
                current.copy(tags = next)
            }
        }

        /**
         * `26-148`: reveals one masked contact-detail row's real value. Reuses `26-115`'s reveal-result
         * idiom exactly:
         *
         * - A row already in [ContactDetailsSectionState.Loaded.pendingIds] is a no-op — one deliberate
         *   tap, one server call per row.
         * - On [RevealContactDetailResult.Revealed] the row is replaced in place with the server's own
         *   unmasked value; nothing here unmasks a value client-side.
         * - On [RevealContactDetailResult.Refused] the masked value stays and the server's `detail` is
         *   surfaced verbatim under that one row.
         * - On [RevealContactDetailResult.Failed] the masked value stays and a generic transport line is
         *   surfaced under that one row.
         *
         * A no-op unless the section is currently [ContactDetailsSectionState.Loaded] (there is no masked
         * row to reveal otherwise).
         */
        public fun revealContactDetail(contactDetailId: String) {
            val conversationId = loadedConversationId ?: return
            val loaded = mutableState.value.contactDetails as? ContactDetailsSectionState.Loaded ?: return
            if (contactDetailId in loaded.pendingIds) return
            markContactDetailPending(contactDetailId)

            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) { contactDetailsApi.revealContactDetail(conversationId, contactDetailId) }
                // Drop a late answer for a conversation the panel has since moved off, the same guard the
                // reads apply.
                if (loadedConversationId != conversationId) return@launch
                mutableState.update { current ->
                    val currentLoaded =
                        current.contactDetails as? ContactDetailsSectionState.Loaded ?: return@update current
                    val nextContactDetails =
                        when (result) {
                            is RevealContactDetailResult.Revealed ->
                                finishContactDetailPending(currentLoaded, contactDetailId) {
                                    it.copy(
                                        details =
                                            it.details.map { detail ->
                                                if (detail.id == contactDetailId) result.contactDetail else detail
                                            },
                                    )
                                }

                            is RevealContactDetailResult.Refused ->
                                finishContactDetailPending(
                                    currentLoaded,
                                    contactDetailId,
                                    error = RowActionError.Refused(result.detail),
                                ) { it }

                            is RevealContactDetailResult.Failed ->
                                finishContactDetailPending(
                                    currentLoaded,
                                    contactDetailId,
                                    error = RowActionError.Failed.Reveal(result.reason),
                                ) { it }
                        }
                    current.copy(contactDetails = nextContactDetails)
                }
            }
        }

        /**
         * `26-169` (`docs/design/26-156-*.md`): opens the row `⋮`'s own «Изменить» editor on one row,
         * prefilled with its current value — the identical "captures the starting draft" moment
         * [ContactDetailsPanel.tsx]'s own `handleStartEdit` performs server-side of this same feature. Opening
         * a *different* row's editor while one is already open replaces both [ContactDetailsSectionState
         * .Loaded.editingId] and [ContactDetailsSectionState.Loaded.editDraft] in the one update below,
         * discarding whatever was typed into the first — nothing was ever sent for it, so there is nothing to
         * reconcile. A no-op unless the section is [ContactDetailsSectionState.Loaded]; the row `⋮` itself
         * never offers «Изменить» for a masked row or without `conversation:send`
         * ([ago.chat.android.thread.contactpanel.sections.ContactDetailsSection]'s own doc comment), so this
         * method does not re-check either — the UI layer is where the permission set and the masked flag are
         * both already known.
         */
        public fun startEditContactDetail(contactDetailId: String) {
            mutableState.update { current ->
                val loaded = current.contactDetails as? ContactDetailsSectionState.Loaded ?: return@update current
                val detail = loaded.details.firstOrNull { it.id == contactDetailId } ?: return@update current
                current.copy(
                    contactDetails =
                        loaded.copy(
                            editingId = contactDetailId,
                            editDraft = detail.value,
                            rowErrors = loaded.rowErrors - contactDetailId,
                        ),
                )
            }
        }

        /** `26-169`: every keystroke in the row editor opened by [startEditContactDetail]. A no-op unless a
         * row is currently being edited. */
        public fun onEditContactDetailDraftChanged(text: String) {
            mutableState.update { current ->
                val loaded = current.contactDetails as? ContactDetailsSectionState.Loaded ?: return@update current
                if (loaded.editingId == null) return@update current
                current.copy(contactDetails = loaded.copy(editDraft = text))
            }
        }

        /** `26-169`: closes the row editor without sending anything — «Отмена». A no-op unless a row is
         * currently being edited. */
        public fun cancelEditContactDetail() {
            mutableState.update { current ->
                val loaded = current.contactDetails as? ContactDetailsSectionState.Loaded ?: return@update current
                if (loaded.editingId == null) return@update current
                current.copy(contactDetails = loaded.copy(editingId = null, editDraft = ""))
            }
        }

        /**
         * `26-169`: submits the row editor's current draft as a correction to [ContactDetailsSectionState
         * .Loaded.editingId]'s own value — «Сохранить». A no-op unless a row is being edited, that row's
         * own write is not already [ContactDetailsSectionState.Loaded.pendingIds] (one tap, one server call),
         * and the draft is non-blank ([ContactDetailsApi.editContactDetail]'s own doc comment: an empty
         * value is refused server-side anyway, but there is no reason to round-trip for one this class can
         * already tell is empty).
         *
         * On [ContactDetailWriteResult.Updated] the row is replaced in place with the server's own answer —
         * **assessment included**: the server resets a row's assessment to `Unset` on every successful edit
         * ([ContactDetailsApi.editContactDetail]'s own doc comment), and this method does not need to
         * special-case that because it never fabricates the row from [detail]/the draft, only from the
         * server's own response — and the editor closes. On [ContactDetailWriteResult.Refused] the editor
         * stays open with the draft exactly as typed and the server's `detail` shown beneath the row
         * (design §3: "draft kept"); on [ContactDetailWriteResult.Failed] the same, with a generic line.
         */
        public fun saveEditContactDetail() {
            val conversationId = loadedConversationId ?: return
            val loaded = mutableState.value.contactDetails as? ContactDetailsSectionState.Loaded ?: return
            val contactDetailId = loaded.editingId ?: return
            if (contactDetailId in loaded.pendingIds) return
            val value = loaded.editDraft.trim()
            if (value.isEmpty()) return

            markContactDetailPending(contactDetailId)
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) { contactDetailsApi.editContactDetail(conversationId, contactDetailId, value) }
                if (loadedConversationId != conversationId) return@launch
                mutableState.update { current ->
                    val currentLoaded =
                        current.contactDetails as? ContactDetailsSectionState.Loaded ?: return@update current
                    val nextContactDetails =
                        when (result) {
                            is ContactDetailWriteResult.Updated ->
                                finishContactDetailPending(currentLoaded, contactDetailId) {
                                    it.copy(
                                        details =
                                            it.details.map { detail ->
                                                if (detail.id == contactDetailId) result.contactDetail else detail
                                            },
                                        editingId = null,
                                        editDraft = "",
                                    )
                                }

                            is ContactDetailWriteResult.Refused ->
                                finishContactDetailPending(
                                    currentLoaded,
                                    contactDetailId,
                                    error = RowActionError.Refused(result.detail),
                                ) { it }

                            is ContactDetailWriteResult.Failed ->
                                finishContactDetailPending(
                                    currentLoaded,
                                    contactDetailId,
                                    error = RowActionError.Failed.Edit(result.reason),
                                ) { it }
                        }
                    current.copy(contactDetails = nextContactDetails)
                }
            }
        }

        /**
         * `26-169`: sets one Phone/Email row's assessment — «Подтвердить»/«Отметить недействительным» in the
         * row `⋮`. [assessment] is the server's own raw wire spelling (`"Confirmed"` or `"Invalid"`, never
         * `"Unset"` — [ContactDetailsApi.setContactDetailAssessment]'s own doc comment), unparsed the
         * identical way [ContactDetail.kind] already travels through this class: the classification (which
         * menu entry is offered for which current [ContactDetail.assessment]) is the UI's job, this method
         * only ever forwards whatever it is given. A no-op while that row's own write is already
         * [ContactDetailsSectionState.Loaded.pendingIds] (one tap, one server call). A `Name` row's menu never
         * offers either action in the first place ([ago.chat.android.thread.contactpanel.sections
         * .ContactDetailsSection]'s own doc comment), so this method does not re-check `kind` either — the
         * server would refuse it anyway ([ContactDetailsApi.setContactDetailAssessment]'s own doc comment on
         * `VisitorContactDetail.AssessmentNotApplicable`), and that refusal would surface exactly like any
         * other [ContactDetailWriteResult.Refused] below.
         *
         * On [ContactDetailWriteResult.Updated] the row is replaced in place with the server's own answer
         * (its new [ContactDetail.assessment]); on [ContactDetailWriteResult.Refused]/[ContactDetailWriteResult.Failed]
         * the row is left exactly as it was and the error surfaced beneath it — the identical refused-verbatim
         * / failed-generic split every other write on this class already draws.
         */
        public fun setContactDetailAssessment(
            contactDetailId: String,
            assessment: String,
        ) {
            val conversationId = loadedConversationId ?: return
            val loaded = mutableState.value.contactDetails as? ContactDetailsSectionState.Loaded ?: return
            if (contactDetailId in loaded.pendingIds) return

            markContactDetailPending(contactDetailId)
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        contactDetailsApi.setContactDetailAssessment(conversationId, contactDetailId, assessment)
                    }
                if (loadedConversationId != conversationId) return@launch
                mutableState.update { current ->
                    val currentLoaded =
                        current.contactDetails as? ContactDetailsSectionState.Loaded ?: return@update current
                    val nextContactDetails =
                        when (result) {
                            is ContactDetailWriteResult.Updated ->
                                finishContactDetailPending(currentLoaded, contactDetailId) {
                                    it.copy(
                                        details =
                                            it.details.map { detail ->
                                                if (detail.id == contactDetailId) result.contactDetail else detail
                                            },
                                    )
                                }

                            is ContactDetailWriteResult.Refused ->
                                finishContactDetailPending(
                                    currentLoaded,
                                    contactDetailId,
                                    error = RowActionError.Refused(result.detail),
                                ) { it }

                            is ContactDetailWriteResult.Failed ->
                                finishContactDetailPending(
                                    currentLoaded,
                                    contactDetailId,
                                    error = RowActionError.Failed.Assessment(result.reason),
                                ) { it }
                        }
                    current.copy(contactDetails = nextContactDetails)
                }
            }
        }

        /** Marks [contactDetailId]'s write in flight and clears any stale error about a previous attempt on
         * it — the same "clear the row's error the moment a new write begins" moment `26-148`'s own reveal
         * established, now shared by all three writes on this section. A no-op unless the section is
         * [ContactDetailsSectionState.Loaded]. */
        private fun markContactDetailPending(contactDetailId: String) {
            mutableState.update { current ->
                val loaded = current.contactDetails as? ContactDetailsSectionState.Loaded ?: return@update current
                current.copy(
                    contactDetails =
                        loaded.copy(
                            pendingIds = loaded.pendingIds + contactDetailId,
                            rowErrors = loaded.rowErrors - contactDetailId,
                        ),
                )
            }
        }

        /** Folds one row write's result back into the section: clears [contactDetailId]'s in-flight flag,
         * then on success applies [onSucceeded] (the caller's own replace-in-place) and on a non-success
         * (`error` non-null) leaves [onSucceeded] unapplied and records the error instead — the shared tail
         * every one of [revealContactDetail]/[saveEditContactDetail]/[setContactDetailAssessment] folds its
         * own three-way result through, the identical "clear the flag, then branch" shape [finishTagAction]
         * already establishes for the tags section's own two writes. */
        private fun finishContactDetailPending(
            loaded: ContactDetailsSectionState.Loaded,
            contactDetailId: String,
            error: RowActionError? = null,
            onSucceeded: (ContactDetailsSectionState.Loaded) -> ContactDetailsSectionState.Loaded,
        ): ContactDetailsSectionState.Loaded {
            val cleared = loaded.copy(pendingIds = loaded.pendingIds - contactDetailId)
            return if (error == null) {
                onSucceeded(cleared)
            } else {
                cleared.copy(rowErrors = cleared.rowErrors + (contactDetailId to error))
            }
        }

        private fun loadSummary(conversationId: String) {
            mutableState.update { it.copy(summary = HeaderSummaryState.Loading) }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { visitorSummaryApi.fetchVisitorSummary(conversationId) }
                // A late answer for a conversation the panel has since moved off (reopened on another
                // thread) is dropped rather than written over the current one - the same "is this still
                // the conversation I asked about" guard `ThreadViewModel.launchJoin` already applies.
                if (loadedConversationId != conversationId) return@launch
                mutableState.update {
                    it.copy(
                        summary =
                            when (result) {
                                is VisitorSummaryResult.Loaded -> HeaderSummaryState.Loaded(result.summary)
                                is VisitorSummaryResult.Failed -> HeaderSummaryState.Failed(result.reason)
                            },
                    )
                }
            }
        }

        private fun loadContactDetails(conversationId: String) {
            mutableState.update { it.copy(contactDetails = ContactDetailsSectionState.Loading) }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { contactDetailsApi.fetchContactDetails(conversationId) }
                if (loadedConversationId != conversationId) return@launch
                mutableState.update {
                    it.copy(
                        contactDetails =
                            when (result) {
                                is ContactDetailsResult.Loaded -> ContactDetailsSectionState.Loaded(result.details)
                                is ContactDetailsResult.Failed -> ContactDetailsSectionState.Failed(result.reason)
                            },
                    )
                }
            }
        }

        /**
         * `26-149`: reads the tags section's two lists. The applied tags (the chips, the primary content) and
         * the site vocabulary (what the «+ метка» picker offers) are fetched **in parallel** — two
         * independent GETs with no ordering between them, so [async]/await halves the wall-clock wait a
         * sequential pair would cost. The section lands [TagsSectionState.Loaded] only when the applied-tags
         * read succeeds; a vocabulary read that itself failed degrades to an empty vocabulary (the picker
         * offers nothing) rather than failing the readable chips, the tradeoff [TagsSectionState]'s own doc
         * comment records.
         */
        private fun loadTags(conversationId: String) {
            mutableState.update { it.copy(tags = TagsSectionState.Loading) }
            viewModelScope.launch {
                val (appliedResult, vocabularyResult) =
                    withContext(ioDispatcher) {
                        val applied = async { conversationTagsApi.fetchConversationTags(conversationId) }
                        val vocabulary = async { conversationTagsApi.fetchSiteTags() }
                        applied.await() to vocabulary.await()
                    }
                if (loadedConversationId != conversationId) return@launch
                mutableState.update {
                    it.copy(
                        tags =
                            when (appliedResult) {
                                is ConversationTagsResult.Loaded ->
                                    TagsSectionState.Loaded(
                                        applied = appliedResult.tags,
                                        vocabulary =
                                            when (vocabularyResult) {
                                                is TagVocabularyResult.Loaded -> vocabularyResult.tags
                                                is TagVocabularyResult.Failed -> emptyList()
                                            },
                                    )

                                is ConversationTagsResult.Failed -> TagsSectionState.Failed(appliedResult.reason)
                            },
                    )
                }
            }
        }

        /** `26-150`: reads the notes list — the row's count is simply this list's own size (design Q8:
         * "fetch the notes list on open … no separate count field"), never a second read. */
        private fun loadNotes(conversationId: String) {
            mutableState.update { it.copy(notes = NotesSectionState.Loading) }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { conversationNotesApi.fetchNotes(conversationId) }
                if (loadedConversationId != conversationId) return@launch
                mutableState.update {
                    it.copy(
                        notes =
                            when (result) {
                                is ConversationNotesResult.Loaded -> NotesSectionState.Loaded(notes = result.notes)
                                is ConversationNotesResult.Failed -> NotesSectionState.Failed(result.reason)
                            },
                    )
                }
            }
        }

        /** `26-151`: reads the first page of the past-dialogs list — [PAST_DIALOGS_PAGE_SIZE] items,
         * newest first ([ago.chat.android.core.domain.visitorhistory.VisitorHistoryApi]'s own keyset
         * convention), landing [PastDialogsSectionState.Loaded] with an empty [PastDialogsSectionState.Loaded.selectedConversationId]
         * — the list, never a transcript, is what a fresh [open] or [retryPastDialogs] always returns to. */
        private fun loadPastDialogs(conversationId: String) {
            mutableState.update { it.copy(pastDialogs = PastDialogsSectionState.Loading) }
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        visitorHistoryApi.fetchVisitorHistory(conversationId, beforeId = null, pageSize = PAST_DIALOGS_PAGE_SIZE)
                    }
                if (loadedConversationId != conversationId) return@launch
                mutableState.update {
                    it.copy(
                        pastDialogs =
                            when (result) {
                                is VisitorHistoryResult.Loaded ->
                                    PastDialogsSectionState.Loaded(
                                        conversations = result.page.conversations,
                                        nextBeforeId = result.page.nextBeforeId,
                                    )

                                is VisitorHistoryResult.Failed -> PastDialogsSectionState.Failed(result.reason)
                            },
                    )
                }
            }
        }

        /** `26-152`: reads the toggle's own initial state on [open] — sets [AttachmentUploadSectionState.Loading]
         * first, then [fetchAttachmentUploadState]'s answer, dropped if the panel has since moved to a
         * different conversation (the same guard every other `load*` method on this class applies). */
        private fun loadAttachmentUpload(conversationId: String) {
            mutableState.update { it.copy(attachmentUpload = AttachmentUploadSectionState.Loading) }
            viewModelScope.launch {
                val next = fetchAttachmentUploadState(conversationId)
                if (loadedConversationId != conversationId) return@launch
                mutableState.update { it.copy(attachmentUpload = next) }
            }
        }

        /**
         * The one place this section actually reads its own data: [ConversationsApi.fetchQueue] (`26-14`,
         * reused as-is), searched for [conversationId]'s own row — the identical two lists
         * ([ago.chat.android.core.domain.conversations.ConversationQueue.waiting]/[ago.chat.android.core.domain.conversations.ConversationQueue.assignedToMe])
         * [ago.chat.android.shell.ConversationsTabHost]'s own row lookup already draws this conversation
         * from before a thread can even be opened, which is why the queue is always the right place to
         * look here and never a genuine gap: this method is only ever called for a conversation the panel
         * is already open on.
         *
         * A row that is not found (the rare case where the conversation left both lists — closed or
         * reassigned — between the panel opening and this read) becomes [AttachmentUploadSectionState.Failed]
         * with [NetworkFailure.Unexpected] — a `2xx` whose answer did not contain what this read expected,
         * the identical classification [NetworkFailure.Unexpected]'s own doc comment describes, restated
         * for a missing row rather than a malformed body. Never a fabricated "not granted" — that would be
         * exactly the kind of guessed answer [ago.chat.android.core.domain.conversations.ConversationSummary]'s
         * own `hasAttachmentUploadGrant` doc comment already rejected once, for the identical field.
         */
        private suspend fun fetchAttachmentUploadState(conversationId: String): AttachmentUploadSectionState {
            val result = withContext(ioDispatcher) { conversationsApi.fetchQueue() }
            return when (result) {
                is QueueResult.Loaded -> {
                    val row =
                        (result.queue.waiting + result.queue.assignedToMe).firstOrNull { it.conversationId == conversationId }
                    if (row != null) {
                        AttachmentUploadSectionState.Loaded(
                            granted = row.hasAttachmentUploadGrant,
                            grantedAt = row.attachmentUploadGrantedAt,
                            grantedByOperatorId = row.attachmentUploadGrantedByOperatorId,
                        )
                    } else {
                        AttachmentUploadSectionState.Failed(NetworkFailure.Unexpected)
                    }
                }

                is QueueResult.Failed -> AttachmentUploadSectionState.Failed(result.reason)
            }
        }

        /** `26-153`: reads the restriction section's own initial state on [open] — sets
         * [RestrictionSectionState.Unavailable] outright when [visitorId] is `null` (this type's own doc
         * comment on why that is a distinct arm from [RestrictionSectionState.Failed]), otherwise
         * [RestrictionSectionState.Loading] then [visitorRestrictionApi]'s answer, dropped if the panel has
         * since moved to a different conversation (the same guard every other `load*` method on this class
         * applies). */
        private fun loadRestriction(
            conversationId: String,
            visitorId: String?,
        ) {
            if (visitorId == null) {
                mutableState.update { it.copy(restriction = RestrictionSectionState.Unavailable) }
                return
            }
            mutableState.update { it.copy(restriction = RestrictionSectionState.Loading) }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { visitorRestrictionApi.isRestricted(visitorId) }
                if (loadedConversationId != conversationId) return@launch
                mutableState.update {
                    it.copy(
                        restriction =
                            when (result) {
                                is VisitorRestrictionStatusResult.Loaded ->
                                    RestrictionSectionState.Loaded(restricted = result.restricted)

                                is VisitorRestrictionStatusResult.Failed -> RestrictionSectionState.Failed(result.reason)
                            },
                    )
                }
            }
        }

        private companion object {
            /** `Ago.Chat.Domain.TagSource`'s wire spelling for a tag an operator applied — the value a
             * freshly-applied tag carries in [ConversationTag.source], mirrored here because a `204`-only
             * apply returns no entity to read it from (this port's own doc comment). */
            const val OPERATOR_TAG_SOURCE = "Operator"

            /** `26-151`: the past-dialogs list's own page size — the server's own default
             * ([ago.chat.android.core.domain.visitorhistory.VisitorHistoryApi.fetchVisitorHistory]'s own
             * doc comment: "the server defaults it to 20... but this port always states it"), stated here
             * rather than relied on. A separate constant from [HISTORY_PAGE_SIZE] on purpose: that one is
             * a *message* page size or `ago-console`'s own parity value, an unrelated wire contract. */
            const val PAST_DIALOGS_PAGE_SIZE = 20
        }
    }
