package ago.chat.android.storage

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.storage.AttachmentEgress
import ago.chat.android.core.domain.storage.AttachmentListCursor
import ago.chat.android.core.domain.storage.AttachmentListFilter
import ago.chat.android.core.domain.storage.AttachmentListItem
import ago.chat.android.core.domain.storage.AttachmentListSort
import ago.chat.android.core.domain.storage.BulkDeleteOutcome
import ago.chat.android.core.domain.storage.LargestConversation
import ago.chat.android.core.domain.storage.StorageSummary

/**
 * `26-250` (`ago-console`'s own `StoragePage`, ported): Ещё → Администрирование → «Хранилище» — the quota
 * bar first (this item's own "the thing the author asked for first"), the attachments table underneath
 * with the console's own sort/filter, and bulk-select-and-delete behind an explicit confirmation. The
 * identical three-arm Loading/Loaded/Failed vocabulary every other site-scoped screen in this app uses
 * ([ago.chat.android.products.ProductsUiState] is the closest read-only sibling; the write half mirrors
 * [ago.chat.android.automation.TagsUiState]'s own in-`Loaded` `busy`/`error` flags).
 *
 * **The quota and the list are the required pair; egress and largest-conversations are best-effort.** The
 * console reads all four in one `Promise.all` and fails the whole screen if any rejects; this app keeps
 * the two supplementary summaries ([Loaded.egress] nullable, [Loaded.largest] possibly empty) from taking
 * the whole screen down with them, since neither is load-bearing for the delete flow. A failed *summary*
 * or *list* is still a [Failed] screen — those two are what the screen exists to show.
 */
public sealed interface StorageUiState {
    public data object Loading : StorageUiState

    public data class Loaded(
        val summary: StorageSummary,
        /** `null` when this month's egress read failed — a supplementary figure, not worth failing the
         * whole screen for (this file's own doc comment). */
        val egress: AttachmentEgress?,
        val largest: List<LargestConversation>,
        val items: List<AttachmentListItem>,
        val nextCursor: AttachmentListCursor?,
        val sort: AttachmentListSort,
        val filter: AttachmentListFilter,
        /** The ids the operator has ticked for deletion — a `Set`, so a double-tap toggles rather than
         * duplicates. Reset to empty on every list reload (sort/filter change, or a completed delete). */
        val selected: Set<String> = emptySet(),
        /** A sort/filter change or a «показать ещё» page is in flight — the list is being replaced or
         * extended while the rest of the screen stays put. */
        val listBusy: Boolean = false,
        /** The bulk-delete is in flight — the confirm dialog's own button shows a spinner and both its
         * buttons disable, exactly as the console's own `deleting` flag drives. */
        val deleting: Boolean = false,
        /** Whether the destructive-confirm dialog is open. The delete itself
         * ([StorageViewModel.confirmDelete]) is a no-op unless this is `true` — the gate is modelled here,
         * not only in the composition, so it is testable without a UI. */
        val confirmingDelete: Boolean = false,
        /** The tally of the last completed delete — drives the success banner, cleared on the next list
         * change. `null` before any delete has landed. */
        val lastDelete: BulkDeleteOutcome? = null,
        /** A transport failure from a *secondary* action (load-more, a sort/filter reload, or the delete
         * itself) — surfaced inline without discarding the list already on screen, unlike the whole-screen
         * [Failed]. */
        val actionError: NetworkFailure? = null,
    ) : StorageUiState {
        val selectedBytes: Long
            get() = items.filter { it.id in selected }.sumOf { it.sizeBytes }
    }

    public data class Failed(
        val reason: NetworkFailure,
    ) : StorageUiState
}
