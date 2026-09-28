package ago.chat.android.storage

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.storage.AttachmentEgressResult
import ago.chat.android.core.domain.storage.AttachmentListCursor
import ago.chat.android.core.domain.storage.AttachmentListFilter
import ago.chat.android.core.domain.storage.AttachmentListItem
import ago.chat.android.core.domain.storage.AttachmentListPage
import ago.chat.android.core.domain.storage.AttachmentListResult
import ago.chat.android.core.domain.storage.AttachmentListSort
import ago.chat.android.core.domain.storage.BulkDeleteOutcome
import ago.chat.android.core.domain.storage.BulkDeleteResult
import ago.chat.android.core.domain.storage.LargestConversationsResult
import ago.chat.android.core.domain.storage.SiteAttachmentStorageApi
import ago.chat.android.core.domain.storage.StorageSummary
import ago.chat.android.core.domain.storage.StorageSummaryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * `26-250`: the «Хранилище» screen's own state machine — the list load (happy/empty/error), the
 * destructive bulk-delete (success drops the rows and re-reads the quota; error keeps the list), and the
 * confirm gate (a delete never fires without an explicit confirm). The identical
 * `StandardTestDispatcher`/`Dispatchers.setMain` shape [ago.chat.android.products.ProductsViewModelTest]
 * establishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts Loading before the first answer comes back`() =
        runTest(dispatcher) {
            val api = FakeStorageApi(hang = true)
            val viewModel = StorageViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(StorageUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a happy load carries the summary and the first page`() =
        runTest(dispatcher) {
            val api =
                FakeStorageApi(
                    summary = StorageSummaryResult.Loaded(StorageSummary(usedBytes = 2048, totalBytes = 10240)),
                    list = AttachmentListResult.Loaded(AttachmentListPage(listOf(item("a"), item("b")), nextCursor = null)),
                )
            val viewModel = StorageViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val loaded = viewModel.state.value as StorageUiState.Loaded
            assertEquals(listOf("a", "b"), loaded.items.map { it.id })
            assertEquals(2048L, loaded.summary.usedBytes)
        }

    @Test
    fun `an empty page is a loaded-but-empty screen, not a failure`() =
        runTest(dispatcher) {
            val api = FakeStorageApi(list = AttachmentListResult.Loaded(AttachmentListPage(emptyList(), nextCursor = null)))
            val viewModel = StorageViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val loaded = viewModel.state.value as StorageUiState.Loaded
            assertTrue(loaded.items.isEmpty())
        }

    @Test
    fun `a failed summary read fails the whole screen`() =
        runTest(dispatcher) {
            val api = FakeStorageApi(summary = StorageSummaryResult.Failed(NetworkFailure.NoConnection))
            val viewModel = StorageViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(StorageUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `a failed list read fails the whole screen`() =
        runTest(dispatcher) {
            val api = FakeStorageApi(list = AttachmentListResult.Failed(NetworkFailure.ServerError(503)))
            val viewModel = StorageViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(StorageUiState.Failed(NetworkFailure.ServerError(503)), viewModel.state.value)
        }

    @Test
    fun `a failed egress or largest read leaves the screen loaded, only without that figure`() =
        runTest(dispatcher) {
            val api =
                FakeStorageApi(
                    egress = AttachmentEgressResult.Failed(NetworkFailure.NoConnection),
                    largest = LargestConversationsResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = StorageViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val loaded = viewModel.state.value as StorageUiState.Loaded
            assertNull(loaded.egress)
            assertTrue(loaded.largest.isEmpty())
        }

    @Test
    fun `confirmDelete without an explicit confirm is a no-op - the gate holds`() =
        runTest(dispatcher) {
            val api =
                FakeStorageApi(
                    list = AttachmentListResult.Loaded(AttachmentListPage(listOf(item("a")), nextCursor = null)),
                )
            val viewModel = StorageViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.toggleSelected("a")
            // No requestDelete() - the dialog was never opened.
            viewModel.confirmDelete()
            advanceUntilIdle()

            assertEquals("the delete must never fire without an explicit confirm", 0, api.bulkDeleteCalls)
            val loaded = viewModel.state.value as StorageUiState.Loaded
            assertEquals(listOf("a"), loaded.items.map { it.id })
        }

    @Test
    fun `requestDelete with nothing selected does not open the dialog`() =
        runTest(dispatcher) {
            val api =
                FakeStorageApi(
                    list = AttachmentListResult.Loaded(AttachmentListPage(listOf(item("a")), nextCursor = null)),
                )
            val viewModel = StorageViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.requestDelete()

            assertFalse((viewModel.state.value as StorageUiState.Loaded).confirmingDelete)
        }

    @Test
    fun `a confirmed delete drops the rows, keeps the tally, and re-reads the quota`() =
        runTest(dispatcher) {
            val api =
                FakeStorageApi(
                    summary = StorageSummaryResult.Loaded(StorageSummary(usedBytes = 5000, totalBytes = 10000)),
                    list = AttachmentListResult.Loaded(AttachmentListPage(listOf(item("a"), item("b")), nextCursor = null)),
                    bulkDelete =
                        BulkDeleteResult.Deleted(
                            BulkDeleteOutcome(deletedCount = 1, freedBytes = 1500, notFoundIds = emptyList(), alreadyGoneCount = 0),
                        ),
                )
            val viewModel = StorageViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.summaryCalls)

            // A fresh quota to prove the reload actually happened.
            api.summary = StorageSummaryResult.Loaded(StorageSummary(usedBytes = 3500, totalBytes = 10000))

            viewModel.toggleSelected("a")
            viewModel.requestDelete()
            assertTrue((viewModel.state.value as StorageUiState.Loaded).confirmingDelete)
            viewModel.confirmDelete()
            advanceUntilIdle()

            val loaded = viewModel.state.value as StorageUiState.Loaded
            assertEquals(1, api.bulkDeleteCalls)
            assertEquals(listOf("b"), loaded.items.map { it.id })
            assertEquals(1500L, loaded.lastDelete?.freedBytes)
            assertFalse(loaded.confirmingDelete)
            assertTrue(loaded.selected.isEmpty())
            assertEquals("the quota is re-read after a delete", 2, api.summaryCalls)
            assertEquals(3500L, loaded.summary.usedBytes)
        }

    @Test
    fun `a failed delete keeps the list and surfaces the failure inline`() =
        runTest(dispatcher) {
            val api =
                FakeStorageApi(
                    list = AttachmentListResult.Loaded(AttachmentListPage(listOf(item("a")), nextCursor = null)),
                    bulkDelete = BulkDeleteResult.Failed(NetworkFailure.ServerError(500)),
                )
            val viewModel = StorageViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.toggleSelected("a")
            viewModel.requestDelete()
            viewModel.confirmDelete()
            advanceUntilIdle()

            val loaded = viewModel.state.value as StorageUiState.Loaded
            assertEquals(listOf("a"), loaded.items.map { it.id })
            assertEquals(NetworkFailure.ServerError(500), loaded.actionError)
            assertFalse(loaded.confirmingDelete)
        }

    @Test
    fun `changing the filter reloads the list in place and clears the selection`() =
        runTest(dispatcher) {
            val api =
                FakeStorageApi(
                    list = AttachmentListResult.Loaded(AttachmentListPage(listOf(item("a")), nextCursor = null)),
                )
            val viewModel = StorageViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.toggleSelected("a")
            api.list = AttachmentListResult.Loaded(AttachmentListPage(listOf(item("c")), nextCursor = null))
            viewModel.changeFilter(AttachmentListFilter.Duplicates)
            advanceUntilIdle()

            val loaded = viewModel.state.value as StorageUiState.Loaded
            assertEquals(AttachmentListFilter.Duplicates, loaded.filter)
            assertEquals(listOf("c"), loaded.items.map { it.id })
            assertTrue(loaded.selected.isEmpty())
        }

    private fun item(id: String): AttachmentListItem =
        AttachmentListItem(
            id = id,
            conversationId = "conv-$id",
            contentType = "image/png",
            sizeBytes = 1500,
            createdAt = Instant.parse("2026-09-01T08:00:00Z"),
            downloadCount = 0,
            lastDownloadedAt = null,
            senderKind = "Visitor",
            senderId = "s-$id",
            isDuplicate = false,
        )

    private class FakeStorageApi(
        var summary: StorageSummaryResult = StorageSummaryResult.Loaded(StorageSummary(usedBytes = 0, totalBytes = 100)),
        var egress: AttachmentEgressResult =
            AttachmentEgressResult.Loaded(
                ago.chat.android.core.domain.storage
                    .AttachmentEgress(periodMonth = "2026-09", downloadCount = 0, bytesOut = 0),
            ),
        var largest: LargestConversationsResult = LargestConversationsResult.Loaded(emptyList()),
        var list: AttachmentListResult = AttachmentListResult.Loaded(AttachmentListPage(emptyList(), nextCursor = null)),
        var bulkDelete: BulkDeleteResult =
            BulkDeleteResult.Deleted(BulkDeleteOutcome(deletedCount = 0, freedBytes = 0, notFoundIds = emptyList(), alreadyGoneCount = 0)),
        private val hang: Boolean = false,
    ) : SiteAttachmentStorageApi {
        var summaryCalls: Int = 0
        var bulkDeleteCalls: Int = 0
        var lastCursor: AttachmentListCursor? = null

        override suspend fun fetchStorageSummary(): StorageSummaryResult {
            summaryCalls++
            if (hang) awaitCancellation()
            return summary
        }

        override suspend fun fetchEgress(): AttachmentEgressResult {
            if (hang) awaitCancellation()
            return egress
        }

        override suspend fun fetchLargestConversations(): LargestConversationsResult {
            if (hang) awaitCancellation()
            return largest
        }

        override suspend fun fetchAttachments(
            sort: AttachmentListSort,
            filter: AttachmentListFilter,
            cursor: AttachmentListCursor?,
        ): AttachmentListResult {
            lastCursor = cursor
            if (hang) awaitCancellation()
            return list
        }

        override suspend fun bulkDelete(attachmentIds: List<String>): BulkDeleteResult {
            bulkDeleteCalls++
            if (hang) awaitCancellation()
            return bulkDelete
        }
    }
}
