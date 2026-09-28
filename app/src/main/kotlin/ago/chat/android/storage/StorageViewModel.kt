package ago.chat.android.storage

import ago.chat.android.core.domain.storage.AttachmentEgressResult
import ago.chat.android.core.domain.storage.AttachmentListFilter
import ago.chat.android.core.domain.storage.AttachmentListResult
import ago.chat.android.core.domain.storage.AttachmentListSort
import ago.chat.android.core.domain.storage.BulkDeleteResult
import ago.chat.android.core.domain.storage.LargestConversationsResult
import ago.chat.android.core.domain.storage.SiteAttachmentStorageApi
import ago.chat.android.core.domain.storage.StorageSummaryResult
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
 * `26-250`: the «Хранилище» screen's own state machine — the quota/egress/largest reads plus the paged,
 * sorted, filtered attachments list, and the destructive bulk-delete behind an explicit confirm gate. The
 * identical "loads on construction, retry just asks again" shape [ago.chat.android.products.ProductsViewModel]
 * establishes for its own read, extended with the read-then-mutate-then-reload discipline
 * [ago.chat.android.documents.ConsentDocumentsViewModel] establishes for a write.
 *
 * **The delete never trusts its own tally over a fresh quota read.** After a [BulkDeleteResult.Deleted]
 * the deleted rows are dropped from the in-memory list *and* [SiteAttachmentStorageApi.fetchStorageSummary]
 * is re-read, so the quota bar reflects what actually freed — the identical "reload it, never an optimistic
 * insert" discipline the consent view model states for its own post-publish reload.
 */
@HiltViewModel
public class StorageViewModel
    @Inject
    constructor(
        private val api: SiteAttachmentStorageApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<StorageUiState>(StorageUiState.Loading)
        public val state: StateFlow<StorageUiState> = mutableState.asStateFlow()

        init {
            refresh(AttachmentListSort.SizeDesc, AttachmentListFilter.None)
        }

        /** The initial load and the [StorageUiState.Failed] retry — the whole screen back to
         * [StorageUiState.Loading] first. The summary and the first list page are the required pair; a
         * failure of either is a failed screen. Egress and largest-conversations are best-effort: a
         * failure of either leaves the screen loaded, only without that supplementary figure. */
        public fun refresh(
            sort: AttachmentListSort,
            filter: AttachmentListFilter,
        ) {
            mutableState.update { StorageUiState.Loading }
            viewModelScope.launch {
                val summaryResult = withContext(ioDispatcher) { api.fetchStorageSummary() }
                val listResult = withContext(ioDispatcher) { api.fetchAttachments(sort, filter, cursor = null) }

                if (summaryResult is StorageSummaryResult.Failed) {
                    mutableState.update { StorageUiState.Failed(summaryResult.reason) }
                    return@launch
                }
                if (listResult is AttachmentListResult.Failed) {
                    mutableState.update { StorageUiState.Failed(listResult.reason) }
                    return@launch
                }

                val summary = (summaryResult as StorageSummaryResult.Loaded).summary
                val page = (listResult as AttachmentListResult.Loaded).page
                val egress = (withContext(ioDispatcher) { api.fetchEgress() } as? AttachmentEgressResult.Loaded)?.egress
                val largest =
                    (withContext(ioDispatcher) { api.fetchLargestConversations() } as? LargestConversationsResult.Loaded)
                        ?.conversations
                        .orEmpty()

                mutableState.update {
                    StorageUiState.Loaded(
                        summary = summary,
                        egress = egress,
                        largest = largest,
                        items = page.items,
                        nextCursor = page.nextCursor,
                        sort = sort,
                        filter = filter,
                    )
                }
            }
        }

        /** A new sort — reloads the list in place (a fresh page, not an append), clearing any selection
         * and any pending confirm, while the quota/egress/largest panels above stay put. A no-op while a
         * list operation is already in flight. */
        public fun changeSort(sort: AttachmentListSort) {
            val loaded = mutableState.value as? StorageUiState.Loaded ?: return
            if (loaded.sort == sort || loaded.listBusy || loaded.deleting) return
            reloadList(sort = sort, filter = loaded.filter)
        }

        /** A new filter — the same in-place list reload as [changeSort]. */
        public fun changeFilter(filter: AttachmentListFilter) {
            val loaded = mutableState.value as? StorageUiState.Loaded ?: return
            if (loaded.filter == filter || loaded.listBusy || loaded.deleting) return
            reloadList(sort = loaded.sort, filter = filter)
        }

        private fun reloadList(
            sort: AttachmentListSort,
            filter: AttachmentListFilter,
        ) {
            mutableState.update { current ->
                (current as? StorageUiState.Loaded)?.copy(
                    listBusy = true,
                    sort = sort,
                    filter = filter,
                    selected = emptySet(),
                    confirmingDelete = false,
                    actionError = null,
                    lastDelete = null,
                ) ?: current
            }
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.fetchAttachments(sort, filter, cursor = null) }) {
                    is AttachmentListResult.Loaded ->
                        mutableState.update { current ->
                            (current as? StorageUiState.Loaded)?.copy(
                                items = result.page.items,
                                nextCursor = result.page.nextCursor,
                                listBusy = false,
                            ) ?: current
                        }

                    is AttachmentListResult.Failed ->
                        mutableState.update { current ->
                            (current as? StorageUiState.Loaded)?.copy(listBusy = false, actionError = result.reason) ?: current
                        }
                }
            }
        }

        /** Appends the next keyset page — a no-op if there is no [StorageUiState.Loaded.nextCursor] or a
         * list operation is already running. A failed page keeps what is already shown, surfacing the
         * failure inline rather than discarding the list. */
        public fun loadMore() {
            val loaded = mutableState.value as? StorageUiState.Loaded ?: return
            val cursor = loaded.nextCursor ?: return
            if (loaded.listBusy || loaded.deleting) return

            mutableState.update { current ->
                (current as? StorageUiState.Loaded)?.copy(listBusy = true, actionError = null) ?: current
            }
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.fetchAttachments(loaded.sort, loaded.filter, cursor) }) {
                    is AttachmentListResult.Loaded ->
                        mutableState.update { current ->
                            (current as? StorageUiState.Loaded)?.copy(
                                items = current.items + result.page.items,
                                nextCursor = result.page.nextCursor,
                                listBusy = false,
                            ) ?: current
                        }

                    is AttachmentListResult.Failed ->
                        mutableState.update { current ->
                            (current as? StorageUiState.Loaded)?.copy(listBusy = false, actionError = result.reason) ?: current
                        }
                }
            }
        }

        /** Ticks or un-ticks one attachment for deletion. */
        public fun toggleSelected(id: String) {
            mutableState.update { current ->
                (current as? StorageUiState.Loaded)?.let {
                    val next = if (id in it.selected) it.selected - id else it.selected + id
                    it.copy(selected = next)
                } ?: current
            }
        }

        /** Opens the destructive-confirm dialog — a no-op with nothing selected, so an empty delete can
         * never even reach the confirm step. */
        public fun requestDelete() {
            mutableState.update { current ->
                (current as? StorageUiState.Loaded)?.takeIf { it.selected.isNotEmpty() }?.copy(confirmingDelete = true) ?: current
            }
        }

        /** Closes the confirm dialog without deleting anything. */
        public fun cancelDelete() {
            mutableState.update { current ->
                (current as? StorageUiState.Loaded)?.copy(confirmingDelete = false) ?: current
            }
        }

        /**
         * Fires the destructive bulk-delete — **the confirm gate is enforced here**: this is a no-op
         * unless [StorageUiState.Loaded.confirmingDelete] is `true` (set only by [requestDelete], which
         * itself refuses an empty selection) and nothing is selected-empty or already deleting. On success
         * the deleted rows drop from the list, the selection clears, the tally is kept for the banner, and
         * the quota bar is re-read from the server rather than adjusted optimistically. On a transport
         * failure the list is untouched and the failure is surfaced inline.
         */
        public fun confirmDelete() {
            val loaded = mutableState.value as? StorageUiState.Loaded ?: return
            if (!loaded.confirmingDelete || loaded.deleting || loaded.selected.isEmpty()) return

            val toDelete = loaded.selected.toList()
            mutableState.update { current ->
                (current as? StorageUiState.Loaded)?.copy(deleting = true, actionError = null) ?: current
            }

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.bulkDelete(toDelete) }) {
                    is BulkDeleteResult.Deleted -> {
                        val deletedIds = toDelete.toSet()
                        mutableState.update { current ->
                            (current as? StorageUiState.Loaded)?.copy(
                                items = current.items.filterNot { it.id in deletedIds },
                                selected = emptySet(),
                                deleting = false,
                                confirmingDelete = false,
                                lastDelete = result.outcome,
                            ) ?: current
                        }
                        reloadSummary()
                    }

                    is BulkDeleteResult.Failed ->
                        mutableState.update { current ->
                            (current as? StorageUiState.Loaded)?.copy(
                                deleting = false,
                                confirmingDelete = false,
                                actionError = result.reason,
                            ) ?: current
                        }
                }
            }
        }

        /** Re-reads the quota after a successful delete. A failed reload leaves the previous bar on
         * screen — the delete itself already succeeded, so "could not load" would misreport what just
         * happened; the freed bytes are already named in [StorageUiState.Loaded.lastDelete]. */
        private suspend fun reloadSummary() {
            val summary = (withContext(ioDispatcher) { api.fetchStorageSummary() } as? StorageSummaryResult.Loaded)?.summary ?: return
            mutableState.update { current ->
                (current as? StorageUiState.Loaded)?.copy(summary = summary) ?: current
            }
        }
    }
