package ago.chat.android.thread.contactpanel

import ago.chat.android.core.domain.contactdetails.ContactDetailsApi
import ago.chat.android.core.domain.contactdetails.ContactDetailsResult
import ago.chat.android.core.domain.contactdetails.RevealContactDetailResult
import ago.chat.android.core.domain.tags.ConversationTag
import ago.chat.android.core.domain.tags.ConversationTagsApi
import ago.chat.android.core.domain.tags.ConversationTagsResult
import ago.chat.android.core.domain.tags.TagActionResult
import ago.chat.android.core.domain.tags.TagVocabularyResult
import ago.chat.android.core.domain.visitorsummary.VisitorSummaryApi
import ago.chat.android.core.domain.visitorsummary.VisitorSummaryResult
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * The remaining sections (`26-149`…`26-153`) grow it the identical way.
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
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(ContactPanelUiState())
        public val state: StateFlow<ContactPanelUiState> = mutableState.asStateFlow()

        /** The conversation the header is currently loaded (or loading) for — `null` before the first
         * [open]. Guards a re-open of the same conversation from re-issuing the read, while still letting
         * a *different* conversation, or a retry after a failure, start a fresh one. */
        private var loadedConversationId: String? = null

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
         */
        public fun open(conversationId: String) {
            val sameConversation = loadedConversationId == conversationId
            loadedConversationId = conversationId
            if (!sameConversation || mutableState.value.summary is HeaderSummaryState.Failed) {
                loadSummary(conversationId)
            }
            if (!sameConversation || mutableState.value.contactDetails is ContactDetailsSectionState.Failed) {
                loadContactDetails(conversationId)
            }
            if (!sameConversation || mutableState.value.tags is TagsSectionState.Failed) {
                loadTags(conversationId)
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
         * - A row already in [ContactDetailsSectionState.Loaded.revealingIds] is a no-op — one deliberate
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
            if (contactDetailId in loaded.revealingIds) return
            mutableState.update {
                it.copy(
                    contactDetails =
                        loaded.copy(
                            revealingIds = loaded.revealingIds + contactDetailId,
                            // A stale error about a previous attempt on this row has no business staying
                            // once a new attempt starts - the same "clear the row's error the moment a new
                            // reveal begins" moment the calendar's own reveal establishes.
                            revealErrors = loaded.revealErrors - contactDetailId,
                        ),
                )
            }

            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) { contactDetailsApi.revealContactDetail(conversationId, contactDetailId) }
                // Drop a late answer for a conversation the panel has since moved off, the same guard the
                // reads apply.
                if (loadedConversationId != conversationId) return@launch
                mutableState.update { current ->
                    val currentLoaded =
                        current.contactDetails as? ContactDetailsSectionState.Loaded ?: return@update current
                    val nextRevealing = currentLoaded.revealingIds - contactDetailId
                    val nextContactDetails =
                        when (result) {
                            is RevealContactDetailResult.Revealed ->
                                currentLoaded.copy(
                                    details =
                                        currentLoaded.details.map {
                                            if (it.id == contactDetailId) result.contactDetail else it
                                        },
                                    revealingIds = nextRevealing,
                                    revealErrors = currentLoaded.revealErrors - contactDetailId,
                                )

                            is RevealContactDetailResult.Refused ->
                                currentLoaded.copy(
                                    revealingIds = nextRevealing,
                                    revealErrors =
                                        currentLoaded.revealErrors + (contactDetailId to RowRevealError.Refused(result.detail)),
                                )

                            is RevealContactDetailResult.Failed ->
                                currentLoaded.copy(
                                    revealingIds = nextRevealing,
                                    revealErrors =
                                        currentLoaded.revealErrors + (contactDetailId to RowRevealError.Failed(result.reason)),
                                )
                        }
                    current.copy(contactDetails = nextContactDetails)
                }
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

        private companion object {
            /** `Ago.Chat.Domain.TagSource`'s wire spelling for a tag an operator applied — the value a
             * freshly-applied tag carries in [ConversationTag.source], mirrored here because a `204`-only
             * apply returns no entity to read it from (this port's own doc comment). */
            const val OPERATOR_TAG_SOURCE = "Operator"
        }
    }
