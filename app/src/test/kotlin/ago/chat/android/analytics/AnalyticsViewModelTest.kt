package ago.chat.android.analytics

import ago.chat.android.core.domain.analytics.AnalyticsBucket
import ago.chat.android.core.domain.analytics.OwnAnalytics
import ago.chat.android.core.domain.analytics.OwnAnalyticsApi
import ago.chat.android.core.domain.analytics.OwnAnalyticsFailure
import ago.chat.android.core.domain.analytics.OwnAnalyticsResult
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

/**
 * `26-57`: the whole state machine — the default-window first load, the four [AnalyticsUiState] arms,
 * and a retry that repeats the operator's own last request rather than the server's default. The
 * `StandardTestDispatcher`/`Dispatchers.setMain` shape `BookingsViewModelTest` already establishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModelTest {
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
            val api = FakeOwnAnalyticsApi(hangFetch = true)
            val viewModel = AnalyticsViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(AnalyticsUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `loads the default window with no range chosen, on construction alone`() =
        runTest(dispatcher) {
            val api = FakeOwnAnalyticsApi(result = OwnAnalyticsResult.Loaded(analytics()))
            AnalyticsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(1, api.calls.size)
            assertEquals(null to null, api.calls.single())
        }

    @Test
    fun `a loaded answer carries the response through unedited`() =
        runTest(dispatcher) {
            val body = analytics(from = "2026-08-01T00:00:00Z", to = "2026-09-01T00:00:00Z")
            val api = FakeOwnAnalyticsApi(result = OwnAnalyticsResult.Loaded(body))
            val viewModel = AnalyticsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(AnalyticsUiState.Loaded(body), viewModel.state.value)
        }

    @Test
    fun `Analytics_InvalidRange lands on its own state, not Failed`() =
        runTest(dispatcher) {
            val api = FakeOwnAnalyticsApi(result = OwnAnalyticsResult.InvalidRange)
            val viewModel = AnalyticsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(AnalyticsUiState.InvalidRange, viewModel.state.value)
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeOwnAnalyticsApi(result = OwnAnalyticsResult.Failed(OwnAnalyticsFailure.Transport))
            val viewModel = AnalyticsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(AnalyticsUiState.Failed(OwnAnalyticsFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `load applies a chosen range`() =
        runTest(dispatcher) {
            val api = FakeOwnAnalyticsApi(result = OwnAnalyticsResult.Loaded(analytics()))
            val viewModel = AnalyticsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.load(from = "2026-01-01T00:00:00Z", to = "2026-01-31T23:59:59.999Z")
            advanceUntilIdle()

            assertEquals(
                "2026-01-01T00:00:00Z" to "2026-01-31T23:59:59.999Z",
                api.calls.last(),
            )
        }

    @Test
    fun `retry repeats the operator's own last requested range, not the default window`() =
        runTest(dispatcher) {
            val api = FakeOwnAnalyticsApi(result = OwnAnalyticsResult.Failed(OwnAnalyticsFailure.Transport))
            val viewModel = AnalyticsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            viewModel.load(from = "2026-02-01T00:00:00Z", to = "2026-02-28T23:59:59.999Z")
            advanceUntilIdle()
            assertEquals(2, api.calls.size)

            viewModel.retry()
            advanceUntilIdle()

            assertEquals(3, api.calls.size)
            assertEquals("2026-02-01T00:00:00Z" to "2026-02-28T23:59:59.999Z", api.calls.last())
        }

    private fun analytics(
        from: String = "2026-08-23T00:00:00Z",
        to: String = "2026-09-22T00:00:00Z",
    ) = OwnAnalytics(
        from = from,
        to = to,
        bucket = AnalyticsBucket(conversationCount = 0, averageFirstResponseSeconds = null, averageDurationSeconds = null, missedCount = 0),
        load = null,
        conversion = null,
    )

    private class FakeOwnAnalyticsApi(
        var result: OwnAnalyticsResult = OwnAnalyticsResult.Failed(OwnAnalyticsFailure.Unexpected),
        private val hangFetch: Boolean = false,
    ) : OwnAnalyticsApi {
        val calls = mutableListOf<Pair<String?, String?>>()

        override suspend fun fetchOwnAnalytics(
            from: String?,
            to: String?,
        ): OwnAnalyticsResult {
            calls += from to to
            if (hangFetch) awaitCancellation()
            return result
        }
    }
}
