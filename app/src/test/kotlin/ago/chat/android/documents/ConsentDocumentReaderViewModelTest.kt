package ago.chat.android.documents

import ago.chat.android.core.domain.documents.PublishedDocument
import ago.chat.android.core.domain.documents.PublishedDocumentApi
import ago.chat.android.core.domain.documents.PublishedDocumentResult
import ago.chat.android.core.domain.net.NetworkFailure
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
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * `26-228` (`docs/design/tenant-consent-android.md` §3.3): [ConsentDocumentReaderViewModel]'s own
 * load/retry surface - the identical `StandardTestDispatcher`/`Dispatchers.setMain` shape every sibling
 * view-model test in this app already establishes ([ConsentDocumentsViewModelTest]).
 *
 * The load-bearing tests here are: [ConsentDocumentReaderViewModel.load] starts Loading before the
 * answer comes back and resets to Loading again for a second, different `(documentKey, version)` pair
 * (never showing the first document's stale text while the second is in flight); `404` becomes
 * [ConsentDocumentReaderUiState.NotFound], never a generic [ConsentDocumentReaderUiState.Failed]; and
 * [ConsentDocumentReaderViewModel.retry] re-reads the exact same pair [load] last saw.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentDocumentReaderViewModelTest {
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
            val api = FakePublishedDocumentApi(hangFetch = true)
            val viewModel = viewModel(api)

            viewModel.load("contact-key", version = null)
            dispatcher.scheduler.runCurrent()

            assertEquals(ConsentDocumentReaderUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a current-version load reads with a null version`() =
        runTest(dispatcher) {
            val api = FakePublishedDocumentApi(result = PublishedDocumentResult.Loaded(sampleDocument()))
            val viewModel = viewModel(api)

            viewModel.load("contact-key", version = null)
            advanceUntilIdle()

            assertEquals(ConsentDocumentReaderUiState.Loaded(sampleDocument()), viewModel.state.value)
            assertEquals("contact-key", api.lastDocumentKey)
            assertEquals(null, api.lastVersion)
        }

    @Test
    fun `a specific-version load carries that version through unchanged`() =
        runTest(dispatcher) {
            val api = FakePublishedDocumentApi(result = PublishedDocumentResult.Loaded(sampleDocument()))
            val viewModel = viewModel(api)

            viewModel.load("contact-key", version = "v1")
            advanceUntilIdle()

            assertEquals("v1", api.lastVersion)
        }

    @Test
    fun `a 404 becomes NotFound, never a generic Failed`() =
        runTest(dispatcher) {
            val api = FakePublishedDocumentApi(result = PublishedDocumentResult.NotFound)
            val viewModel = viewModel(api)

            viewModel.load("contact-key", version = "v9")
            advanceUntilIdle()

            assertEquals(ConsentDocumentReaderUiState.NotFound, viewModel.state.value)
        }

    @Test
    fun `a transport failure becomes Failed carrying the adapter's own classification`() =
        runTest(dispatcher) {
            val api = FakePublishedDocumentApi(result = PublishedDocumentResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(api)

            viewModel.load("contact-key", version = null)
            advanceUntilIdle()

            assertEquals(ConsentDocumentReaderUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `a second load for a different document resets to Loading, never showing the first's stale text`() =
        runTest(dispatcher) {
            val api = FakePublishedDocumentApi(result = PublishedDocumentResult.Loaded(sampleDocument()))
            val viewModel = viewModel(api)
            viewModel.load("contact-key", version = null)
            advanceUntilIdle()
            assertEquals(ConsentDocumentReaderUiState.Loaded(sampleDocument()), viewModel.state.value)

            api.hangFetch = true
            viewModel.load("marketing-key", version = "v1")
            dispatcher.scheduler.runCurrent()

            assertEquals(ConsentDocumentReaderUiState.Loading, viewModel.state.value)
            assertEquals("marketing-key", api.lastDocumentKey)
        }

    @Test
    fun `retry re-reads the same pair load last saw`() =
        runTest(dispatcher) {
            val api = FakePublishedDocumentApi(result = PublishedDocumentResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(api)
            viewModel.load("contact-key", version = "v1")
            advanceUntilIdle()
            assertEquals(ConsentDocumentReaderUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)

            api.result = PublishedDocumentResult.Loaded(sampleDocument())
            viewModel.retry()
            advanceUntilIdle()

            assertEquals(ConsentDocumentReaderUiState.Loaded(sampleDocument()), viewModel.state.value)
            assertEquals("v1", api.lastVersion)
            assertEquals(2, api.fetchCalls)
        }

    @Test
    fun `retry before any load is a no-op, never calling the network`() =
        runTest(dispatcher) {
            val api = FakePublishedDocumentApi(result = PublishedDocumentResult.Loaded(sampleDocument()))
            val viewModel = viewModel(api)

            viewModel.retry()
            advanceUntilIdle()

            assertEquals(0, api.fetchCalls)
            assertEquals(ConsentDocumentReaderUiState.Loading, viewModel.state.value)
        }

    // ------------------------------------------------------------------------------------------ fixtures

    private fun viewModel(api: PublishedDocumentApi) = ConsentDocumentReaderViewModel(api = api, ioDispatcher = dispatcher)

    private fun sampleDocument() =
        PublishedDocument(
            documentKey = "contact-key",
            version = "v2",
            title = "Согласие на контакты",
            body = "Полный текст документа.",
            publishedAt = Instant.parse("2026-01-02T10:00:00Z"),
        )

    private class FakePublishedDocumentApi(
        var result: PublishedDocumentResult = PublishedDocumentResult.Failed(NetworkFailure.Unexpected),
        var hangFetch: Boolean = false,
    ) : PublishedDocumentApi {
        var fetchCalls: Int = 0
            private set
        var lastDocumentKey: String? = null
            private set
        var lastVersion: String? = null
            private set

        override suspend fun fetchDocument(
            documentKey: String,
            version: String?,
        ): PublishedDocumentResult {
            fetchCalls++
            lastDocumentKey = documentKey
            lastVersion = version
            if (hangFetch) awaitCancellation()
            return result
        }
    }
}
