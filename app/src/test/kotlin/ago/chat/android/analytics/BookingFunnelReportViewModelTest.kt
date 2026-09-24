package ago.chat.android.analytics

import ago.chat.android.core.domain.analytics.BookingFunnelReport
import ago.chat.android.core.domain.analytics.BookingFunnelReportApi
import ago.chat.android.core.domain.analytics.BookingFunnelReportFailure
import ago.chat.android.core.domain.analytics.BookingFunnelReportResult
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
 * `26-73`: the whole state machine — the default-window first load, the four
 * [BookingFunnelReportUiState] arms, and a retry that repeats the operator's own last request rather
 * than the server's default. The `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * `ConversionReportViewModelTest`/`TagBreakdownReportViewModelTest` already establish for its siblings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookingFunnelReportViewModelTest {
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
            val api = FakeBookingFunnelReportApi(hangFetch = true)
            val viewModel = BookingFunnelReportViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(BookingFunnelReportUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `loads the server's default window with no range chosen, on construction alone`() =
        runTest(dispatcher) {
            val api = FakeBookingFunnelReportApi(result = BookingFunnelReportResult.Loaded(report()))
            BookingFunnelReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(1, api.calls.size)
            assertEquals(null to null, api.calls.single())
        }

    /** The range this screen displays is the response's own, never the request's - proved here by
     * asking with no bounds at all and finding the server's bounds in the state. */
    @Test
    fun `a loaded answer carries the response's own range and counts through unedited`() =
        runTest(dispatcher) {
            val body = report(from = "2026-08-01T00:00:00Z", to = "2026-09-01T00:00:00Z")
            val api = FakeBookingFunnelReportApi(result = BookingFunnelReportResult.Loaded(body))
            val viewModel = BookingFunnelReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(BookingFunnelReportUiState.Loaded(body), viewModel.state.value)
            assertEquals(null to null, api.calls.single())
        }

    @Test
    fun `ModuleFlow_InvalidRange lands on its own state, not Failed`() =
        runTest(dispatcher) {
            val api = FakeBookingFunnelReportApi(result = BookingFunnelReportResult.InvalidRange)
            val viewModel = BookingFunnelReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(BookingFunnelReportUiState.InvalidRange, viewModel.state.value)
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeBookingFunnelReportApi(result = BookingFunnelReportResult.Failed(BookingFunnelReportFailure.Transport))
            val viewModel = BookingFunnelReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(BookingFunnelReportUiState.Failed(BookingFunnelReportFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `load applies a chosen range`() =
        runTest(dispatcher) {
            val api = FakeBookingFunnelReportApi(result = BookingFunnelReportResult.Loaded(report()))
            val viewModel = BookingFunnelReportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.load(from = "2026-01-01T00:00:00Z", to = "2026-01-31T23:59:59.999Z")
            advanceUntilIdle()

            assertEquals("2026-01-01T00:00:00Z" to "2026-01-31T23:59:59.999Z", api.calls.last())
        }

    @Test
    fun `retry repeats the operator's own last requested range, not the default window`() =
        runTest(dispatcher) {
            val api = FakeBookingFunnelReportApi(result = BookingFunnelReportResult.Failed(BookingFunnelReportFailure.Transport))
            val viewModel = BookingFunnelReportViewModel(api = api, ioDispatcher = dispatcher)
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
            val api = FakeBookingFunnelReportApi(result = BookingFunnelReportResult.Loaded(report()))
            val viewModel = BookingFunnelReportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.load(from = "2026-03-01T00:00:00Z", to = null)
            advanceUntilIdle()

            assertEquals("2026-03-01T00:00:00Z" to null, api.calls.last())
        }

    private fun report(
        from: String = "2026-08-23T00:00:00Z",
        to: String = "2026-09-22T00:00:00Z",
        flowsStarted: Int = 19,
        flowsClosed: Int = 12,
    ) = BookingFunnelReport(
        from = from,
        to = to,
        flowsStarted = flowsStarted,
        flowsClosed = flowsClosed,
        previousFrom = "2026-07-24T00:00:00Z",
        previousTo = from,
        previousFlowsStarted = 13,
        previousFlowsClosed = 8,
    )

    private class FakeBookingFunnelReportApi(
        var result: BookingFunnelReportResult = BookingFunnelReportResult.Failed(BookingFunnelReportFailure.Unexpected),
        private val hangFetch: Boolean = false,
    ) : BookingFunnelReportApi {
        val calls = mutableListOf<Pair<String?, String?>>()

        override suspend fun fetchBookingFunnelReport(
            from: String?,
            to: String?,
        ): BookingFunnelReportResult {
            calls += from to to
            if (hangFetch) awaitCancellation()
            return result
        }
    }
}
