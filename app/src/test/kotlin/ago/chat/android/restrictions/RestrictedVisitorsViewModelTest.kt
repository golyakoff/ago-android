package ago.chat.android.restrictions

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.restrictions.RestrictionKind
import ago.chat.android.core.domain.restrictions.VisitorRestriction
import ago.chat.android.core.domain.restrictions.VisitorRestrictionActionResult
import ago.chat.android.core.domain.restrictions.VisitorRestrictionApi
import ago.chat.android.core.domain.restrictions.VisitorRestrictionPageResult
import ago.chat.android.core.domain.restrictions.VisitorRestrictionStatusResult
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
 * `26-227`: the list/load-more/lift state machine for «Ограниченные посетители» — the identical
 * `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * [ago.chat.android.analytics.PhoneRevealsReportViewModelTest] already establishes for its closest
 * sibling screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestrictedVisitorsViewModelTest {
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
            val api = FakeVisitorRestrictionApi(hangList = true)
            val viewModel = RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(RestrictedVisitorsUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `asks for the first page on construction alone, with no cursor`() =
        runTest(dispatcher) {
            val api = FakeVisitorRestrictionApi(listResult = VisitorRestrictionPageResult.Loaded(listOf(row("r1")), null))
            RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(1, api.listCalls.size)
            assertEquals(null to null, api.listCalls.single())
        }

    @Test
    fun `a loaded page passes straight through, unedited`() =
        runTest(dispatcher) {
            val page = listOf(row("r2"), row("r1"))
            val api = FakeVisitorRestrictionApi(listResult = VisitorRestrictionPageResult.Loaded(page, "r1"))
            val viewModel = RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(RestrictedVisitorsUiState.Loaded(rows = page, nextBeforeId = "r1"), viewModel.state.value)
        }

    @Test
    fun `a failed first page carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeVisitorRestrictionApi(listResult = VisitorRestrictionPageResult.Failed(NetworkFailure.NoConnection))
            val viewModel = RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(RestrictedVisitorsUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `refresh asks for the first page again, discarding whatever cursor was already in hand`() =
        runTest(dispatcher) {
            val api = FakeVisitorRestrictionApi(listResult = VisitorRestrictionPageResult.Loaded(listOf(row("r1")), "r0"))
            val viewModel = RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            api.listResult = VisitorRestrictionPageResult.Loaded(listOf(row("r2")), null)
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(listOf(null to null, null to null), api.listCalls)
            assertEquals(RestrictedVisitorsUiState.Loaded(listOf(row("r2")), nextBeforeId = null), viewModel.state.value)
        }

    @Test
    fun `load more is a no-op once the cursor is exhausted`() =
        runTest(dispatcher) {
            val api = FakeVisitorRestrictionApi(listResult = VisitorRestrictionPageResult.Loaded(listOf(row("r1")), null))
            val viewModel = RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(1, api.listCalls.size)
        }

    @Test
    fun `load more sends the current cursor and appends the next page to the end of the list`() =
        runTest(dispatcher) {
            val api = FakeVisitorRestrictionApi(listResult = VisitorRestrictionPageResult.Loaded(listOf(row("r2")), "r2"))
            val viewModel = RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            api.listResult = VisitorRestrictionPageResult.Loaded(listOf(row("r1")), null)
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(listOf(null to null, "r2" to null), api.listCalls)
            assertEquals(
                RestrictedVisitorsUiState.Loaded(listOf(row("r2"), row("r1")), nextBeforeId = null),
                viewModel.state.value,
            )
        }

    @Test
    fun `load more marks loadingMore immediately, before the server answers`() =
        runTest(dispatcher) {
            val api =
                FakeVisitorRestrictionApi(
                    listResult = VisitorRestrictionPageResult.Loaded(listOf(row("r2")), "r2"),
                    hangListMore = true,
                )
            val viewModel = RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.loadMore()
            dispatcher.scheduler.runCurrent()

            assertEquals(
                RestrictedVisitorsUiState.Loaded(listOf(row("r2")), nextBeforeId = "r2", loadingMore = true),
                viewModel.state.value,
            )
        }

    @Test
    fun `calling load more twice while the first page is in flight sends exactly one request`() =
        runTest(dispatcher) {
            val api =
                FakeVisitorRestrictionApi(
                    listResult = VisitorRestrictionPageResult.Loaded(listOf(row("r2")), "r2"),
                    hangListMore = true,
                )
            val viewModel = RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.loadMore()
            dispatcher.scheduler.runCurrent()
            viewModel.loadMore()
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.listCalls.count { it.first == "r2" })
        }

    @Test
    fun `a failed load more keeps the rows already on screen`() =
        runTest(dispatcher) {
            val api = FakeVisitorRestrictionApi(listResult = VisitorRestrictionPageResult.Loaded(listOf(row("r2")), "r2"))
            val viewModel = RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            api.listResult = VisitorRestrictionPageResult.Failed(NetworkFailure.NoConnection)
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(
                RestrictedVisitorsUiState.Loaded(listOf(row("r2")), nextBeforeId = "r2", loadingMore = false),
                viewModel.state.value,
            )
        }

    @Test
    fun `lifting marks the tapped row's own id in flight and clears any earlier error`() =
        runTest(dispatcher) {
            val api =
                FakeVisitorRestrictionApi(
                    listResult = VisitorRestrictionPageResult.Loaded(listOf(row("r1", visitorId = "v1")), null),
                    hangLift = true,
                )
            val viewModel = RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.liftRestriction(restrictionId = "r1", visitorId = "v1")
            dispatcher.scheduler.runCurrent()

            val loaded = viewModel.state.value as RestrictedVisitorsUiState.Loaded
            assertEquals("r1", loaded.liftingId)
            assertEquals(null, loaded.liftError)
            assertEquals(listOf("v1"), api.liftCalls)
        }

    @Test
    fun `a second lift while one is already in flight is a no-op`() =
        runTest(dispatcher) {
            val api =
                FakeVisitorRestrictionApi(
                    listResult =
                        VisitorRestrictionPageResult.Loaded(
                            listOf(row("r1", visitorId = "v1"), row("r2", visitorId = "v2")),
                            null,
                        ),
                    hangLift = true,
                )
            val viewModel = RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.liftRestriction(restrictionId = "r1", visitorId = "v1")
            dispatcher.scheduler.runCurrent()
            viewModel.liftRestriction(restrictionId = "r2", visitorId = "v2")
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf("v1"), api.liftCalls)
        }

    @Test
    fun `a successful lift reloads the whole list from the top, never an optimistic in-place flip`() =
        runTest(dispatcher) {
            val api =
                FakeVisitorRestrictionApi(
                    listResult = VisitorRestrictionPageResult.Loaded(listOf(row("r1", visitorId = "v1")), "r1"),
                    liftResult = VisitorRestrictionActionResult.Succeeded,
                )
            val viewModel = RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            api.listResult = VisitorRestrictionPageResult.Loaded(emptyList(), null)
            viewModel.liftRestriction(restrictionId = "r1", visitorId = "v1")
            advanceUntilIdle()

            // Two `list` calls total: the initial load, then the reload the successful lift triggers -
            // both asking for the first page (`before = null`), never resuming the stale cursor.
            assertEquals(listOf(null to null, null to null), api.listCalls)
            assertEquals(RestrictedVisitorsUiState.Loaded(emptyList(), nextBeforeId = null), viewModel.state.value)
        }

    @Test
    fun `a refused lift leaves the rows untouched and surfaces the server's own detail`() =
        runTest(dispatcher) {
            val api =
                FakeVisitorRestrictionApi(
                    listResult = VisitorRestrictionPageResult.Loaded(listOf(row("r1", visitorId = "v1")), null),
                    liftResult = VisitorRestrictionActionResult.Refused("Only an admin may lift a block."),
                )
            val viewModel = RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.liftRestriction(restrictionId = "r1", visitorId = "v1")
            advanceUntilIdle()

            assertEquals(
                RestrictedVisitorsUiState.Loaded(
                    listOf(row("r1", visitorId = "v1")),
                    nextBeforeId = null,
                    liftingId = null,
                    liftError = LiftRestrictionError.Refused("Only an admin may lift a block."),
                ),
                viewModel.state.value,
            )
            // The reload `Succeeded` alone triggers never fires for a refusal.
            assertEquals(1, api.listCalls.size)
        }

    @Test
    fun `a failed lift leaves the rows untouched and surfaces a classified failure`() =
        runTest(dispatcher) {
            val api =
                FakeVisitorRestrictionApi(
                    listResult = VisitorRestrictionPageResult.Loaded(listOf(row("r1", visitorId = "v1")), null),
                    liftResult = VisitorRestrictionActionResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = RestrictedVisitorsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.liftRestriction(restrictionId = "r1", visitorId = "v1")
            advanceUntilIdle()

            val loaded = viewModel.state.value as RestrictedVisitorsUiState.Loaded
            assertEquals(listOf(row("r1", visitorId = "v1")), loaded.rows)
            assertEquals(null, loaded.liftingId)
            assertEquals(LiftRestrictionError.Failed(NetworkFailure.NoConnection), loaded.liftError)
        }

    private fun row(
        id: String,
        visitorId: String = "v-$id",
    ) = VisitorRestriction(
        id = id,
        visitorId = visitorId,
        kind = RestrictionKind.Block,
        restrictedAt = Instant.parse("2026-09-25T10:00:00Z"),
        restrictedBy = "op1",
        expiresAt = null,
        sourceConversationId = "c1",
        liftedAt = null,
        liftedBy = null,
        emojiCreature = "🦉",
        emojiFood = "🍓",
    )

    private class FakeVisitorRestrictionApi(
        var listResult: VisitorRestrictionPageResult = VisitorRestrictionPageResult.Loaded(emptyList(), null),
        var liftResult: VisitorRestrictionActionResult = VisitorRestrictionActionResult.Succeeded,
        private val hangList: Boolean = false,
        var hangListMore: Boolean = false,
        private val hangLift: Boolean = false,
    ) : VisitorRestrictionApi {
        /** One entry per [list] call, as `before to limit`. */
        val listCalls: MutableList<Pair<String?, Int?>> = mutableListOf()

        /** One entry per [lift] call, the visitor id it was addressed to. */
        val liftCalls: MutableList<String> = mutableListOf()

        override suspend fun list(
            before: String?,
            limit: Int?,
        ): VisitorRestrictionPageResult {
            listCalls.add(before to limit)
            if (before == null) {
                if (hangList) awaitCancellation()
            } else {
                if (hangListMore) awaitCancellation()
            }
            return listResult
        }

        override suspend fun lift(visitorId: String): VisitorRestrictionActionResult {
            liftCalls.add(visitorId)
            if (hangLift) awaitCancellation()
            return liftResult
        }

        // `RestrictedVisitorsViewModel` reads only [list]/[lift] - [block]/[isRestricted] belong to the
        // contact-detail panel's own view model, the identical "not used by this class" posture
        // `PhoneRevealsReportViewModelTest`'s own `FakeBookingsApi` already takes for its port's other
        // methods.
        override suspend fun block(conversationId: String): VisitorRestrictionActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun isRestricted(visitorId: String): VisitorRestrictionStatusResult =
            throw UnsupportedOperationException("not used by this class")
    }
}
