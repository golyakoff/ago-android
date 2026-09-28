package ago.chat.android.siteexport

import ago.chat.android.core.domain.siteexport.RequestSiteExportResult
import ago.chat.android.core.domain.siteexport.SiteExportApi
import ago.chat.android.core.domain.siteexport.SiteExportHistoryResult
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
 * `26-251`: the «Скачать данные» screen's own state machine — the history read (happy/empty/error) plus the
 * request-export write, whose success reloads the history (never an optimistic insert) and whose failure
 * surfaces inline while the list stays put. The identical "loads on construction, retry just asks again"
 * shape [ago.chat.android.products.ProductsViewModel] establishes for its own read, extended with the
 * read-then-mutate-then-reload discipline [ago.chat.android.documents.ConsentDocumentsViewModel] establishes
 * for a write.
 *
 * **The request never trusts its own outcome over a fresh read.** After a [RequestSiteExportResult.Requested]
 * the history is re-read, so the new `Pending` row appears exactly as the server recorded it — the identical
 * "reload it, never an optimistic insert" discipline the console's own `SiteExportPage` states (its `onRequest`
 * calls `load()` after the request resolves, and nothing polls the row forward on its own).
 */
@HiltViewModel
public class SiteExportViewModel
    @Inject
    constructor(
        private val api: SiteExportApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<SiteExportUiState>(SiteExportUiState.Loading)
        public val state: StateFlow<SiteExportUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, the top bar's «Обновить» action, and the retry a [SiteExportUiState.Failed]
         * screen offers — one method for all three, back to [SiteExportUiState.Loading] first. */
        public fun refresh() {
            mutableState.update { SiteExportUiState.Loading }
            viewModelScope.launch {
                mutableState.update {
                    when (val result = withContext(ioDispatcher) { api.fetchHistory() }) {
                        is SiteExportHistoryResult.Loaded -> SiteExportUiState.Loaded(items = result.items)
                        is SiteExportHistoryResult.Failed -> SiteExportUiState.Failed(result.reason)
                    }
                }
            }
        }

        /**
         * Requests a new export. A no-op unless the screen is [SiteExportUiState.Loaded] and no request is
         * already in flight. On [RequestSiteExportResult.Requested] the history is re-read (which is what
         * shows the new `Pending` row); a [RequestSiteExportResult.Refused]/[RequestSiteExportResult.Failed]
         * leaves the list untouched and surfaces the failure inline.
         */
        public fun requestExport() {
            val loaded = mutableState.value as? SiteExportUiState.Loaded ?: return
            if (loaded.requesting) return

            mutableState.update { current ->
                (current as? SiteExportUiState.Loaded)?.copy(
                    requesting = true,
                    requestError = null,
                    requestRefusal = null,
                ) ?: current
            }

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.requestExport() }) {
                    is RequestSiteExportResult.Requested -> reloadHistoryAfterRequest()

                    is RequestSiteExportResult.Refused ->
                        mutableState.update { current ->
                            (current as? SiteExportUiState.Loaded)?.copy(
                                requesting = false,
                                requestRefusal = result.detail,
                            ) ?: current
                        }

                    is RequestSiteExportResult.Failed ->
                        mutableState.update { current ->
                            (current as? SiteExportUiState.Loaded)?.copy(
                                requesting = false,
                                requestError = result.reason,
                            ) ?: current
                        }
                }
            }
        }

        /** Re-reads the history after a successful request. A failed reload keeps the previous list on
         * screen and surfaces the failure inline — the request itself already succeeded, so a whole-screen
         * [SiteExportUiState.Failed] would misreport what just happened. */
        private suspend fun reloadHistoryAfterRequest() {
            when (val result = withContext(ioDispatcher) { api.fetchHistory() }) {
                is SiteExportHistoryResult.Loaded ->
                    mutableState.update { current ->
                        (current as? SiteExportUiState.Loaded)?.copy(
                            items = result.items,
                            requesting = false,
                        ) ?: current
                    }

                is SiteExportHistoryResult.Failed ->
                    mutableState.update { current ->
                        (current as? SiteExportUiState.Loaded)?.copy(
                            requesting = false,
                            requestError = result.reason,
                        ) ?: current
                    }
            }
        }
    }
