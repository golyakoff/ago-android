package ago.chat.android.analytics

import ago.chat.android.core.domain.analytics.TagBreakdownBucketRow
import ago.chat.android.core.domain.analytics.TagBreakdownReport
import ago.chat.android.core.domain.analytics.TagBreakdownReportApi
import ago.chat.android.core.domain.analytics.TagBreakdownReportFailure
import ago.chat.android.core.domain.analytics.TagBreakdownReportResult
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
 * `26-72`: the whole state machine — the default-window first load, the four
 * [TagBreakdownReportUiState] arms, and a retry that repeats the operator's own last request rather than
 * the server's default. The `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * `ConversionReportViewModelTest` already establishes for its sibling report.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagBreakdownReportViewModelTest {
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
            val api = FakeTagBreakdownReportApi(hangFetch = true)
            val viewModel = TagBreakdownReportViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(TagBreakdownReportUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `loads the server's default window with no range chosen, on construction alone`() =
        runTest(dispatcher) {
            val api = FakeTagBreakdownReportApi(result = TagBreakdownReportResult.Loaded(report()))
            TagBreakdownReportViewModel(api = api, ioDispatcher = dispatcher)

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
            val api = FakeTagBreakdownReportApi(result = TagBreakdownReportResult.Loaded(body))
            val viewModel = TagBreakdownReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(TagBreakdownReportUiState.Loaded(body), viewModel.state.value)
            assertEquals(null to null, api.calls.single())
        }

    /** The one distinction this item exists to keep visible all the way to the state a screen renders
     * from: a `null` coverage percentage ("nothing to compute coverage from") is never a real `0`. */
    @Test
    fun `a null percentage tagged reaches the state distinct from a real zero`() =
        runTest(dispatcher) {
            val api =
                FakeTagBreakdownReportApi(
                    result =
                        TagBreakdownReportResult.Loaded(
                            report(totalConversationCount = 0, taggedConversationCount = 0, percentageTagged = null),
                        ),
                )
            val viewModel = TagBreakdownReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val loaded = viewModel.state.value as TagBreakdownReportUiState.Loaded
            assertNull(loaded.report.percentageTagged)
            assertEquals(0, loaded.report.totalConversationCount)
        }

    @Test
    fun `Analytics_InvalidRange lands on its own state, not Failed`() =
        runTest(dispatcher) {
            val api = FakeTagBreakdownReportApi(result = TagBreakdownReportResult.InvalidRange)
            val viewModel = TagBreakdownReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(TagBreakdownReportUiState.InvalidRange, viewModel.state.value)
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeTagBreakdownReportApi(result = TagBreakdownReportResult.Failed(TagBreakdownReportFailure.Transport))
            val viewModel = TagBreakdownReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(TagBreakdownReportUiState.Failed(TagBreakdownReportFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `load applies a chosen range`() =
        runTest(dispatcher) {
            val api = FakeTagBreakdownReportApi(result = TagBreakdownReportResult.Loaded(report()))
            val viewModel = TagBreakdownReportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.load(from = "2026-01-01T00:00:00Z", to = "2026-01-31T23:59:59.999Z")
            advanceUntilIdle()

            assertEquals("2026-01-01T00:00:00Z" to "2026-01-31T23:59:59.999Z", api.calls.last())
        }

    @Test
    fun `retry repeats the operator's own last requested range, not the default window`() =
        runTest(dispatcher) {
            val api = FakeTagBreakdownReportApi(result = TagBreakdownReportResult.Failed(TagBreakdownReportFailure.Transport))
            val viewModel = TagBreakdownReportViewModel(api = api, ioDispatcher = dispatcher)
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
            val api = FakeTagBreakdownReportApi(result = TagBreakdownReportResult.Loaded(report()))
            val viewModel = TagBreakdownReportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.load(from = "2026-03-01T00:00:00Z", to = null)
            advanceUntilIdle()

            assertEquals("2026-03-01T00:00:00Z" to null, api.calls.last())
        }

    private fun report(
        from: String = "2026-08-23T00:00:00Z",
        to: String = "2026-09-22T00:00:00Z",
        totalConversationCount: Int = 19,
        taggedConversationCount: Int = 12,
        percentageTagged: Double? = 0.631578947368421,
    ) = TagBreakdownReport(
        from = from,
        to = to,
        totalConversationCount = totalConversationCount,
        taggedConversationCount = taggedConversationCount,
        percentageTagged = percentageTagged,
        previousFrom = "2026-07-24T00:00:00Z",
        previousTo = from,
        previousTotalConversationCount = 13,
        previousTaggedConversationCount = 8,
        previousPercentageTagged = 0.6153846153846154,
        byTag =
            listOf(
                TagBreakdownBucketRow(
                    tagId = "tag-pricing",
                    tagName = "Цены",
                    conversationCount = 10,
                    convertedCount = 6,
                    notConvertedCount = 3,
                    recordedCount = 9,
                    conversionRate = 0.6666666666666666,
                ),
                TagBreakdownBucketRow(
                    tagId = "tag-delivery",
                    tagName = "Доставка",
                    conversationCount = 4,
                    convertedCount = 1,
                    notConvertedCount = 1,
                    recordedCount = 2,
                    conversionRate = 0.5,
                ),
            ),
    )

    private class FakeTagBreakdownReportApi(
        var result: TagBreakdownReportResult = TagBreakdownReportResult.Failed(TagBreakdownReportFailure.Unexpected),
        private val hangFetch: Boolean = false,
    ) : TagBreakdownReportApi {
        val calls = mutableListOf<Pair<String?, String?>>()

        override suspend fun fetchTagBreakdownReport(
            from: String?,
            to: String?,
        ): TagBreakdownReportResult {
            calls += from to to
            if (hangFetch) awaitCancellation()
            return result
        }
    }
}
