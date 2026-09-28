package ago.chat.android.siteexport

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.siteexport.RequestSiteExportResult
import ago.chat.android.core.domain.siteexport.SiteExportApi
import ago.chat.android.core.domain.siteexport.SiteExportHistoryItem
import ago.chat.android.core.domain.siteexport.SiteExportHistoryResult
import ago.chat.android.core.domain.siteexport.SiteExportStatus
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
 * `26-251`: the «Скачать данные» screen's own state machine — the history load (happy/empty/error) and the
 * request-export write (success re-reads the history and shows the new row; a refusal/failure keeps the
 * list and surfaces inline). The identical `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * [ago.chat.android.storage.StorageViewModelTest] establishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SiteExportViewModelTest {
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
            val api = FakeSiteExportApi(hang = true)
            val viewModel = SiteExportViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(SiteExportUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a happy load carries the history newest-first`() =
        runTest(dispatcher) {
            val api = FakeSiteExportApi(history = SiteExportHistoryResult.Loaded(listOf(item("b"), item("a"))))
            val viewModel = SiteExportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val loaded = viewModel.state.value as SiteExportUiState.Loaded
            assertEquals(listOf("b", "a"), loaded.items.map { it.exportId })
        }

    @Test
    fun `an empty history is a loaded-but-empty screen, not a failure`() =
        runTest(dispatcher) {
            val api = FakeSiteExportApi(history = SiteExportHistoryResult.Loaded(emptyList()))
            val viewModel = SiteExportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val loaded = viewModel.state.value as SiteExportUiState.Loaded
            assertTrue(loaded.items.isEmpty())
        }

    @Test
    fun `a failed history read fails the whole screen`() =
        runTest(dispatcher) {
            val api = FakeSiteExportApi(history = SiteExportHistoryResult.Failed(NetworkFailure.NoConnection))
            val viewModel = SiteExportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(SiteExportUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `a successful request re-reads the history and shows the new row`() =
        runTest(dispatcher) {
            val api =
                FakeSiteExportApi(
                    history = SiteExportHistoryResult.Loaded(listOf(item("a"))),
                    request = RequestSiteExportResult.Requested("new"),
                )
            val viewModel = SiteExportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.historyCalls)

            // A fresh history to prove the reload actually happened.
            api.history = SiteExportHistoryResult.Loaded(listOf(item("new"), item("a")))

            viewModel.requestExport()
            advanceUntilIdle()

            val loaded = viewModel.state.value as SiteExportUiState.Loaded
            assertEquals(1, api.requestCalls)
            assertEquals("the history is re-read after a request", 2, api.historyCalls)
            assertEquals(listOf("new", "a"), loaded.items.map { it.exportId })
            assertFalse(loaded.requesting)
            assertNull(loaded.requestError)
            assertNull(loaded.requestRefusal)
        }

    @Test
    fun `a failed request keeps the list and surfaces the failure inline`() =
        runTest(dispatcher) {
            val api =
                FakeSiteExportApi(
                    history = SiteExportHistoryResult.Loaded(listOf(item("a"))),
                    request = RequestSiteExportResult.Failed(NetworkFailure.ServerError(500)),
                )
            val viewModel = SiteExportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.requestExport()
            advanceUntilIdle()

            val loaded = viewModel.state.value as SiteExportUiState.Loaded
            assertEquals("a failed request must not re-read the history", 1, api.historyCalls)
            assertEquals(listOf("a"), loaded.items.map { it.exportId })
            assertEquals(NetworkFailure.ServerError(500), loaded.requestError)
            assertFalse(loaded.requesting)
        }

    @Test
    fun `a refused request surfaces the server detail inline`() =
        runTest(dispatcher) {
            val api =
                FakeSiteExportApi(
                    history = SiteExportHistoryResult.Loaded(listOf(item("a"))),
                    request = RequestSiteExportResult.Refused("Сайт не найден."),
                )
            val viewModel = SiteExportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.requestExport()
            advanceUntilIdle()

            val loaded = viewModel.state.value as SiteExportUiState.Loaded
            assertEquals("Сайт не найден.", loaded.requestRefusal)
            assertNull(loaded.requestError)
            assertFalse(loaded.requesting)
        }

    private fun item(id: String): SiteExportHistoryItem =
        SiteExportHistoryItem(
            exportId = id,
            status = SiteExportStatus.Pending,
            requestedAt = Instant.parse("2026-01-01T00:00:00Z"),
            completedAt = null,
            downloadUrl = null,
            expiresAt = null,
            failureReason = null,
        )

    private class FakeSiteExportApi(
        var history: SiteExportHistoryResult = SiteExportHistoryResult.Loaded(emptyList()),
        var request: RequestSiteExportResult = RequestSiteExportResult.Requested("x"),
        private val hang: Boolean = false,
    ) : SiteExportApi {
        var historyCalls: Int = 0
        var requestCalls: Int = 0

        override suspend fun fetchHistory(): SiteExportHistoryResult {
            historyCalls++
            if (hang) awaitCancellation()
            return history
        }

        override suspend fun requestExport(): RequestSiteExportResult {
            requestCalls++
            if (hang) awaitCancellation()
            return request
        }
    }
}
