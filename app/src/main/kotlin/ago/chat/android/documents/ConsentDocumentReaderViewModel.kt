package ago.chat.android.documents

import ago.chat.android.core.domain.documents.PublishedDocumentApi
import ago.chat.android.core.domain.documents.PublishedDocumentResult
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
 * `26-228` (`docs/design/tenant-consent-android.md` §3.3): «Просмотр документа» — a stateless read of one
 * version's own text via [PublishedDocumentApi.fetchDocument].
 *
 * **Which document to read is not known at construction** — unlike [ConsentDocumentsViewModel] (which
 * always reads the same two-purpose overview from `init`), this screen is opened with a different
 * `(documentKey, version)` pair every time an operator taps «Открыть» on a different version, and this
 * view model has no navigation-graph back-stack entry of its own to scope a fresh instance to (the whole
 * Документы согласий area is a `remember`-held internal drill-in, not a `NavHost` destination —
 * `tenant-consent-android.md` §3's own note). So [load] is a method, called from
 * [ConsentDocumentReaderRoute] via `LaunchedEffect(documentKey, version)`, the identical shape
 * [ago.chat.android.thread.ThreadViewModel.open] already establishes for the same reason: a `hiltViewModel()`
 * instance can outlive any one document if the operator opens a second one before this composable leaves
 * composition, and [load] resets to [ConsentDocumentReaderUiState.Loading] on every call so that is never
 * visible as stale content.
 */
@HiltViewModel
internal class ConsentDocumentReaderViewModel
    @Inject
    constructor(
        private val api: PublishedDocumentApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ConsentDocumentReaderUiState>(ConsentDocumentReaderUiState.Loading)
        val state: StateFlow<ConsentDocumentReaderUiState> = mutableState.asStateFlow()

        private var lastDocumentKey: String? = null
        private var lastVersion: String? = null

        /** Starts (or restarts) a read for exactly this `(documentKey, version)` pair - always resets to
         * [ConsentDocumentReaderUiState.Loading] first, so a second [load] for a different document never
         * shows the previous one's text while the new read is in flight. */
        fun load(
            documentKey: String,
            version: String?,
        ) {
            lastDocumentKey = documentKey
            lastVersion = version
            fetch(documentKey, version)
        }

        /** The [ConsentDocumentReaderUiState.Failed] retry - re-reads the same pair [load] last saw, a
         * no-op if nothing has been requested yet. */
        fun retry() {
            val documentKey = lastDocumentKey ?: return
            fetch(documentKey, lastVersion)
        }

        private fun fetch(
            documentKey: String,
            version: String?,
        ) {
            mutableState.update { ConsentDocumentReaderUiState.Loading }
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.fetchDocument(documentKey, version) }) {
                    is PublishedDocumentResult.Loaded ->
                        mutableState.update { ConsentDocumentReaderUiState.Loaded(result.document) }

                    PublishedDocumentResult.NotFound ->
                        mutableState.update { ConsentDocumentReaderUiState.NotFound }

                    is PublishedDocumentResult.Failed ->
                        mutableState.update { ConsentDocumentReaderUiState.Failed(result.reason) }
                }
            }
        }
    }
