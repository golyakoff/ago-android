package ago.chat.android.thread.contactpanel

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.visitorsummary.VisitorSummary
import ago.chat.android.core.domain.visitorsummary.VisitorSummaryApi
import ago.chat.android.core.domain.visitorsummary.VisitorSummaryResult
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
 * `26-147`: the header's async half — the one visitor-summary read, its three UI arms, and the
 * open/retry sequencing. The identical `StandardTestDispatcher`/`Dispatchers.setMain` shape the sibling
 * one-shot view-model tests already establish.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactPanelViewModelTest {
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
    fun `open shows Loading before the summary comes back`() =
        runTest(dispatcher) {
            val api = FakeVisitorSummaryApi(hang = true)
            val viewModel = ContactPanelViewModel(visitorSummaryApi = api, ioDispatcher = dispatcher)

            viewModel.open("c1")
            dispatcher.scheduler.runCurrent()

            assertEquals(HeaderSummaryState.Loading, viewModel.state.value.summary)
        }

    @Test
    fun `a loaded summary becomes the Loaded header arm`() =
        runTest(dispatcher) {
            val summary = VisitorSummary(firstSeenAt = Instant.parse("2026-03-14T06:30:00Z"), conversationCount = 7)
            val api = FakeVisitorSummaryApi(result = VisitorSummaryResult.Loaded(summary))
            val viewModel = ContactPanelViewModel(visitorSummaryApi = api, ioDispatcher = dispatcher)

            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals(HeaderSummaryState.Loaded(summary), viewModel.state.value.summary)
        }

    @Test
    fun `a failed read becomes the Failed arm, and retry asks again`() =
        runTest(dispatcher) {
            val api = FakeVisitorSummaryApi(result = VisitorSummaryResult.Failed(NetworkFailure.NoConnection))
            val viewModel = ContactPanelViewModel(visitorSummaryApi = api, ioDispatcher = dispatcher)

            viewModel.open("c1")
            advanceUntilIdle()
            assertEquals(HeaderSummaryState.Failed(NetworkFailure.NoConnection), viewModel.state.value.summary)

            viewModel.retry()
            advanceUntilIdle()

            assertEquals(2, api.calls)
        }

    @Test
    fun `re-opening the same conversation does not re-read`() =
        runTest(dispatcher) {
            val summary = VisitorSummary(firstSeenAt = null, conversationCount = 1)
            val api = FakeVisitorSummaryApi(result = VisitorSummaryResult.Loaded(summary))
            val viewModel = ContactPanelViewModel(visitorSummaryApi = api, ioDispatcher = dispatcher)

            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals(1, api.calls)
        }

    private class FakeVisitorSummaryApi(
        private val result: VisitorSummaryResult = VisitorSummaryResult.Failed(NetworkFailure.Unexpected),
        private val hang: Boolean = false,
    ) : VisitorSummaryApi {
        var calls = 0

        override suspend fun fetchVisitorSummary(conversationId: String): VisitorSummaryResult {
            calls++
            if (hang) awaitCancellation()
            return result
        }
    }
}
