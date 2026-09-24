package ago.chat.android.analytics

import ago.chat.android.core.domain.analytics.AnalyticsBucket
import ago.chat.android.core.domain.analytics.ChannelBreakdownRow
import ago.chat.android.core.domain.analytics.OperatorBreakdownRow
import ago.chat.android.core.domain.analytics.OperatorLoadSummary
import ago.chat.android.core.domain.analytics.SiteAnalytics
import ago.chat.android.core.domain.analytics.SiteAnalyticsApi
import ago.chat.android.core.domain.analytics.SiteAnalyticsFailure
import ago.chat.android.core.domain.analytics.SiteAnalyticsResult
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * `26-70`: the whole state machine — the default-window first load, the four [SiteAnalyticsUiState]
 * arms, and a retry that repeats the operator's own last request rather than the server's default. The
 * `StandardTestDispatcher`/`Dispatchers.setMain` shape `AnalyticsViewModelTest` already establishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SiteAnalyticsViewModelTest {
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
            val api = FakeSiteAnalyticsApi(hangFetch = true)
            val viewModel = SiteAnalyticsViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(SiteAnalyticsUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `loads the server's default window with no range chosen, on construction alone`() =
        runTest(dispatcher) {
            val api = FakeSiteAnalyticsApi(result = SiteAnalyticsResult.Loaded(analytics()))
            SiteAnalyticsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(1, api.calls.size)
            assertEquals(null to null, api.calls.single())
        }

    /** The range this screen displays is the response's own, never the request's - proved here by
     * asking with no bounds at all and finding the server's bounds in the state
     * (`docs/backlog/26-70-*.md`'s own Done-when). */
    @Test
    fun `a loaded answer carries the response's own range and breakdowns through unedited`() =
        runTest(dispatcher) {
            val body = analytics(from = "2026-08-01T00:00:00Z", to = "2026-09-01T00:00:00Z")
            val api = FakeSiteAnalyticsApi(result = SiteAnalyticsResult.Loaded(body))
            val viewModel = SiteAnalyticsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(SiteAnalyticsUiState.Loaded(body), viewModel.state.value)
            assertEquals(null to null, api.calls.single())
        }

    /** The three-way distinction, carried all the way to the state a screen renders from: a real `0`,
     * an average that is `null` because there was nothing to average, and a `load` that is absent
     * because there is no assignment data at all. */
    @Test
    fun `a real zero, a null average and an absent load reach the state as three different things`() =
        runTest(dispatcher) {
            val api = FakeSiteAnalyticsApi(result = SiteAnalyticsResult.Loaded(analytics()))
            val viewModel = SiteAnalyticsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val loaded = viewModel.state.value as SiteAnalyticsUiState.Loaded
            val quiet = loaded.analytics.byOperator.first { it.operatorId == "quiet" }
            assertEquals(0, quiet.bucket.conversationCount)
            assertNull(quiet.bucket.averageFirstResponseSeconds)
            assertNull(quiet.load)

            val busy = loaded.analytics.byOperator.first { it.operatorId == "busy" }
            assertEquals(0, busy.load!!.additionalIntervals)
        }

    @Test
    fun `Analytics_InvalidRange lands on its own state, not Failed`() =
        runTest(dispatcher) {
            val api = FakeSiteAnalyticsApi(result = SiteAnalyticsResult.InvalidRange)
            val viewModel = SiteAnalyticsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(SiteAnalyticsUiState.InvalidRange, viewModel.state.value)
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeSiteAnalyticsApi(result = SiteAnalyticsResult.Failed(SiteAnalyticsFailure.Transport))
            val viewModel = SiteAnalyticsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(SiteAnalyticsUiState.Failed(SiteAnalyticsFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `load applies a chosen range`() =
        runTest(dispatcher) {
            val api = FakeSiteAnalyticsApi(result = SiteAnalyticsResult.Loaded(analytics()))
            val viewModel = SiteAnalyticsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.load(from = "2026-01-01T00:00:00Z", to = "2026-01-31T23:59:59.999Z")
            advanceUntilIdle()

            assertEquals("2026-01-01T00:00:00Z" to "2026-01-31T23:59:59.999Z", api.calls.last())
        }

    @Test
    fun `retry repeats the operator's own last requested range, not the default window`() =
        runTest(dispatcher) {
            val api = FakeSiteAnalyticsApi(result = SiteAnalyticsResult.Failed(SiteAnalyticsFailure.Transport))
            val viewModel = SiteAnalyticsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            viewModel.load(from = "2026-02-01T00:00:00Z", to = "2026-02-28T23:59:59.999Z")
            advanceUntilIdle()
            assertEquals(2, api.calls.size)

            viewModel.retry()
            advanceUntilIdle()

            assertEquals(3, api.calls.size)
            assertEquals("2026-02-01T00:00:00Z" to "2026-02-28T23:59:59.999Z", api.calls.last())
        }

    /** A half-open window is a real request the server accepts - the view model must not quietly fill
     * the missing bound in, or the range reported back would stop being the server's own answer. */
    @Test
    fun `only one bound is sent when only one is chosen`() =
        runTest(dispatcher) {
            val api = FakeSiteAnalyticsApi(result = SiteAnalyticsResult.Loaded(analytics()))
            val viewModel = SiteAnalyticsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.load(from = "2026-03-01T00:00:00Z", to = null)
            advanceUntilIdle()

            assertEquals("2026-03-01T00:00:00Z" to null, api.calls.last())
        }

    private fun analytics(
        from: String = "2026-08-23T00:00:00Z",
        to: String = "2026-09-22T00:00:00Z",
    ) = SiteAnalytics(
        from = from,
        to = to,
        overall =
            AnalyticsBucket(conversationCount = 5, averageFirstResponseSeconds = 30.0, averageDurationSeconds = null, missedCount = 0),
        previousFrom = "2026-07-24T00:00:00Z",
        previousTo = from,
        previousOverall =
            AnalyticsBucket(conversationCount = 4, averageFirstResponseSeconds = null, averageDurationSeconds = null, missedCount = 1),
        byChannel = listOf(ChannelBreakdownRow("Widget", AnalyticsBucket(5, 30.0, null, 0))),
        byOperator =
            listOf(
                OperatorBreakdownRow(
                    operatorId = "busy",
                    operatorName = "Вера",
                    bucket = AnalyticsBucket(5, 30.0, null, 0),
                    load = OperatorLoadSummary(conversationsHeld = 5, intervalsHeld = 6, standardIntervals = 6, additionalIntervals = 0),
                ),
                OperatorBreakdownRow(
                    operatorId = "quiet",
                    operatorName = null,
                    bucket = AnalyticsBucket(0, null, null, 0),
                    load = null,
                ),
            ),
        byReferrer = emptyList(),
        byCampaign = emptyList(),
    )

    private class FakeSiteAnalyticsApi(
        var result: SiteAnalyticsResult = SiteAnalyticsResult.Failed(SiteAnalyticsFailure.Unexpected),
        private val hangFetch: Boolean = false,
    ) : SiteAnalyticsApi {
        val calls = mutableListOf<Pair<String?, String?>>()

        override suspend fun fetchSiteAnalytics(
            from: String?,
            to: String?,
        ): SiteAnalyticsResult {
            calls += from to to
            if (hangFetch) awaitCancellation()
            return result
        }
    }
}
