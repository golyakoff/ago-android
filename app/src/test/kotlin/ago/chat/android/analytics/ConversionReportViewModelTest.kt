package ago.chat.android.analytics

import ago.chat.android.core.domain.analytics.ConversionBucket
import ago.chat.android.core.domain.analytics.ConversionOperatorBreakdownRow
import ago.chat.android.core.domain.analytics.ConversionReport
import ago.chat.android.core.domain.analytics.ConversionReportApi
import ago.chat.android.core.domain.analytics.ConversionReportFailure
import ago.chat.android.core.domain.analytics.ConversionReportResult
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
 * `26-71`: the whole state machine — the default-window first load, the four
 * [ConversionReportUiState] arms, and a retry that repeats the operator's own last request rather than
 * the server's default. The `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * `SiteAnalyticsViewModelTest` already establishes for its sibling report.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversionReportViewModelTest {
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
            val api = FakeConversionReportApi(hangFetch = true)
            val viewModel = ConversionReportViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(ConversionReportUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `loads the server's default window with no range chosen, on construction alone`() =
        runTest(dispatcher) {
            val api = FakeConversionReportApi(result = ConversionReportResult.Loaded(report()))
            ConversionReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(1, api.calls.size)
            assertEquals(null to null, api.calls.single())
        }

    /** The range this screen displays is the response's own, never the request's - proved here by
     * asking with no bounds at all and finding the server's bounds in the state. */
    @Test
    fun `a loaded answer carries the response's own range and breakdown through unedited`() =
        runTest(dispatcher) {
            val body = report(from = "2026-08-01T00:00:00Z", to = "2026-09-01T00:00:00Z")
            val api = FakeConversionReportApi(result = ConversionReportResult.Loaded(body))
            val viewModel = ConversionReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ConversionReportUiState.Loaded(body), viewModel.state.value)
            assertEquals(null to null, api.calls.single())
        }

    /** The one distinction this item exists to keep visible all the way to the state a screen renders
     * from: a `null` rate ("nothing recorded yet") is never a real `0`. */
    @Test
    fun `a null conversion rate reaches the state distinct from a real zero`() =
        runTest(dispatcher) {
            val api =
                FakeConversionReportApi(
                    result =
                        ConversionReportResult.Loaded(
                            report(
                                overall = ConversionBucket(0, 0, 0, 4, 0, null),
                            ),
                        ),
                )
            val viewModel = ConversionReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val loaded = viewModel.state.value as ConversionReportUiState.Loaded
            assertNull(loaded.report.overall.conversionRate)
            assertEquals(4, loaded.report.overall.unsetCount)
        }

    @Test
    fun `Analytics_InvalidRange lands on its own state, not Failed`() =
        runTest(dispatcher) {
            val api = FakeConversionReportApi(result = ConversionReportResult.InvalidRange)
            val viewModel = ConversionReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ConversionReportUiState.InvalidRange, viewModel.state.value)
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeConversionReportApi(result = ConversionReportResult.Failed(ConversionReportFailure.Transport))
            val viewModel = ConversionReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ConversionReportUiState.Failed(ConversionReportFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `load applies a chosen range`() =
        runTest(dispatcher) {
            val api = FakeConversionReportApi(result = ConversionReportResult.Loaded(report()))
            val viewModel = ConversionReportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.load(from = "2026-01-01T00:00:00Z", to = "2026-01-31T23:59:59.999Z")
            advanceUntilIdle()

            assertEquals("2026-01-01T00:00:00Z" to "2026-01-31T23:59:59.999Z", api.calls.last())
        }

    @Test
    fun `retry repeats the operator's own last requested range, not the default window`() =
        runTest(dispatcher) {
            val api = FakeConversionReportApi(result = ConversionReportResult.Failed(ConversionReportFailure.Transport))
            val viewModel = ConversionReportViewModel(api = api, ioDispatcher = dispatcher)
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
            val api = FakeConversionReportApi(result = ConversionReportResult.Loaded(report()))
            val viewModel = ConversionReportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.load(from = "2026-03-01T00:00:00Z", to = null)
            advanceUntilIdle()

            assertEquals("2026-03-01T00:00:00Z" to null, api.calls.last())
        }

    private fun report(
        from: String = "2026-08-23T00:00:00Z",
        to: String = "2026-09-22T00:00:00Z",
        overall: ConversionBucket = ConversionBucket(12, 5, 2, 3, 19, 0.631578947368421),
    ) = ConversionReport(
        from = from,
        to = to,
        overall = overall,
        previousFrom = "2026-07-24T00:00:00Z",
        previousTo = from,
        previousOverall = ConversionBucket(8, 4, 1, 2, 13, 0.6153846153846154),
        byOperator =
            listOf(
                ConversionOperatorBreakdownRow(
                    operatorId = "op-with-name",
                    operatorName = "Вера",
                    bucket = ConversionBucket(10, 3, 1, 1, 14, 0.7142857142857143),
                ),
                ConversionOperatorBreakdownRow(
                    operatorId = "op-without-name",
                    operatorName = null,
                    bucket = ConversionBucket(2, 2, 1, 2, 5, 0.4),
                ),
            ),
    )

    private class FakeConversionReportApi(
        var result: ConversionReportResult = ConversionReportResult.Failed(ConversionReportFailure.Unexpected),
        private val hangFetch: Boolean = false,
    ) : ConversionReportApi {
        val calls = mutableListOf<Pair<String?, String?>>()

        override suspend fun fetchConversionReport(
            from: String?,
            to: String?,
        ): ConversionReportResult {
            calls += from to to
            if (hangFetch) awaitCancellation()
            return result
        }
    }
}
