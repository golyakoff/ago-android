package ago.chat.android.thread.contactpanel

import ago.chat.android.core.domain.visitorsummary.VisitorSummaryApi
import ago.chat.android.core.domain.visitorsummary.VisitorSummaryResult
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-147`: the contact-detail panel's own view model — for now, only the header's async half (H4/H5),
 * read once through [VisitorSummaryApi] (`26-143`). The join point `26-148`…`26-153` grow: each section
 * that needs a server read adds its own port here beside [visitorSummaryApi] and its own load method,
 * folding a new arm into [ContactPanelUiState] rather than restructuring this class.
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
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(ContactPanelUiState())
        public val state: StateFlow<ContactPanelUiState> = mutableState.asStateFlow()

        /** The conversation the header is currently loaded (or loading) for — `null` before the first
         * [open]. Guards a re-open of the same conversation from re-issuing the read, while still letting
         * a *different* conversation, or a retry after a failure, start a fresh one. */
        private var loadedConversationId: String? = null

        /**
         * Opens the panel on one conversation and fetches its visitor summary. Called from
         * [ago.chat.android.thread.ThreadRoute] the moment the operator taps the open affordance. A
         * repeat call for the conversation already loaded is a no-op — unless the previous attempt failed,
         * in which case re-opening the sheet is itself the retry (the header's inline retry calls [retry]
         * directly; this covers the "close, reopen" path landing on a fresh attempt too).
         */
        public fun open(conversationId: String) {
            if (loadedConversationId == conversationId && mutableState.value.summary !is HeaderSummaryState.Failed) return
            loadedConversationId = conversationId
            load(conversationId)
        }

        /** The header's own inline retry (§4: per-section retry, never a whole-sheet failure) — re-reads
         * the summary for whatever conversation is currently open, a no-op before the first [open]. */
        public fun retry() {
            val conversationId = loadedConversationId ?: return
            load(conversationId)
        }

        private fun load(conversationId: String) {
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
    }
