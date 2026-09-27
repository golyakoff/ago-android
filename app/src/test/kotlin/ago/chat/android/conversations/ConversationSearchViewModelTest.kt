package ago.chat.android.conversations

import ago.chat.android.core.domain.conversations.AllConversationsResult
import ago.chat.android.core.domain.conversations.ClaimResult
import ago.chat.android.core.domain.conversations.ConversationSearchHit
import ago.chat.android.core.domain.conversations.ConversationSearchPage
import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.domain.conversations.ErasureResult
import ago.chat.android.core.domain.conversations.QueueResult
import ago.chat.android.core.domain.conversations.SearchConversationsResult
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `26-245`: the search screen's query/search/load-more state machine — the identical
 * `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * [ago.chat.android.restrictions.RestrictedVisitorsViewModelTest] already establishes for its closest
 * sibling screen. The fake implements the whole [ConversationsApi] port; only [searchConversations] is
 * exercised, the identical "the other methods belong to another screen's view model" posture that
 * sibling test's own fake takes for its port's unused methods.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSearchViewModelTest {
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
    fun `runs nothing on construction`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi()
            val viewModel = ConversationSearchViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            assertEquals(ConversationSearchUiState(query = "", phase = ConversationSearchPhase.Idle), viewModel.state.value)
            assertTrue(api.searchCalls.isEmpty())
        }

    @Test
    fun `a blank phrase never reaches the server`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi()
            val viewModel = ConversationSearchViewModel(api = api, ioDispatcher = dispatcher)

            viewModel.onQueryChange("   ")
            viewModel.search()
            advanceUntilIdle()

            assertTrue(api.searchCalls.isEmpty())
            assertEquals(ConversationSearchPhase.Idle, viewModel.state.value.phase)
        }

    @Test
    fun `a search trims the phrase, sends no cursor, and lands its hits and searched range`() =
        runTest(dispatcher) {
            val api =
                FakeConversationsApi(
                    searchResult =
                        SearchConversationsResult.Loaded(
                            ConversationSearchPage(
                                results = listOf(hit("m1"), hit("m2")),
                                nextBeforeMessageId = "m2",
                                searchedFrom = "2026-06-28T00:00:00Z",
                                searchedTo = "2026-09-28T00:00:00Z",
                            ),
                        ),
                )
            val viewModel = ConversationSearchViewModel(api = api, ioDispatcher = dispatcher)

            viewModel.onQueryChange("  refund  ")
            viewModel.search()
            advanceUntilIdle()

            assertEquals("refund" to null, api.searchCalls.single())
            assertEquals(
                ConversationSearchPhase.Results(
                    hits = listOf(hit("m1"), hit("m2")),
                    nextBeforeMessageId = "m2",
                    searchedFrom = "2026-06-28T00:00:00Z",
                    searchedTo = "2026-09-28T00:00:00Z",
                ),
                viewModel.state.value.phase,
            )
        }

    @Test
    fun `a search with no hits is Results with an empty list, not Idle`() =
        runTest(dispatcher) {
            val api =
                FakeConversationsApi(
                    searchResult =
                        SearchConversationsResult.Loaded(
                            ConversationSearchPage(emptyList(), null, "2026-06-28T00:00:00Z", "2026-09-28T00:00:00Z"),
                        ),
                )
            val viewModel = ConversationSearchViewModel(api = api, ioDispatcher = dispatcher)

            viewModel.onQueryChange("nothing matches this")
            viewModel.search()
            advanceUntilIdle()

            val phase = viewModel.state.value.phase as ConversationSearchPhase.Results
            assertTrue(phase.hits.isEmpty())
            assertEquals(null, phase.nextBeforeMessageId)
        }

    @Test
    fun `a refused search carries the server's own detail verbatim`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi(searchResult = SearchConversationsResult.Refused("Начало периода позже конца."))
            val viewModel = ConversationSearchViewModel(api = api, ioDispatcher = dispatcher)

            viewModel.onQueryChange("refund")
            viewModel.search()
            advanceUntilIdle()

            assertEquals(ConversationSearchPhase.Refused("Начало периода позже конца."), viewModel.state.value.phase)
        }

    @Test
    fun `a failed search carries its classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi(searchResult = SearchConversationsResult.Failed(NetworkFailure.NoConnection))
            val viewModel = ConversationSearchViewModel(api = api, ioDispatcher = dispatcher)

            viewModel.onQueryChange("refund")
            viewModel.search()
            advanceUntilIdle()

            assertEquals(ConversationSearchPhase.Failed(NetworkFailure.NoConnection), viewModel.state.value.phase)
        }

    @Test
    fun `search marks Searching immediately, before the server answers`() =
        runTest(dispatcher) {
            val api =
                FakeConversationsApi(
                    searchResult =
                        SearchConversationsResult.Loaded(
                            ConversationSearchPage(listOf(hit("m1")), null, "f", "t"),
                        ),
                    hangSearch = true,
                )
            val viewModel = ConversationSearchViewModel(api = api, ioDispatcher = dispatcher)

            viewModel.onQueryChange("refund")
            viewModel.search()
            dispatcher.scheduler.runCurrent()

            assertEquals(ConversationSearchPhase.Searching, viewModel.state.value.phase)
        }

    @Test
    fun `load more sends the current cursor and the same phrase, and appends the next page`() =
        runTest(dispatcher) {
            val api =
                FakeConversationsApi(
                    searchResult =
                        SearchConversationsResult.Loaded(
                            ConversationSearchPage(listOf(hit("m1")), nextBeforeMessageId = "m1", searchedFrom = "f", searchedTo = "t"),
                        ),
                )
            val viewModel = ConversationSearchViewModel(api = api, ioDispatcher = dispatcher)
            viewModel.onQueryChange("refund")
            viewModel.search()
            advanceUntilIdle()

            api.searchResult =
                SearchConversationsResult.Loaded(
                    ConversationSearchPage(listOf(hit("m2")), nextBeforeMessageId = null, searchedFrom = "f", searchedTo = "t"),
                )
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(listOf("refund" to null, "refund" to "m1"), api.searchCalls)
            val phase = viewModel.state.value.phase as ConversationSearchPhase.Results
            assertEquals(listOf(hit("m1"), hit("m2")), phase.hits)
            assertEquals(null, phase.nextBeforeMessageId)
            assertEquals(false, phase.loadingMore)
        }

    @Test
    fun `load more is a no-op once the cursor is exhausted`() =
        runTest(dispatcher) {
            val api =
                FakeConversationsApi(
                    searchResult =
                        SearchConversationsResult.Loaded(
                            ConversationSearchPage(listOf(hit("m1")), nextBeforeMessageId = null, searchedFrom = "f", searchedTo = "t"),
                        ),
                )
            val viewModel = ConversationSearchViewModel(api = api, ioDispatcher = dispatcher)
            viewModel.onQueryChange("refund")
            viewModel.search()
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(1, api.searchCalls.size)
        }

    @Test
    fun `calling load more twice while the first next page is in flight sends exactly one request`() =
        runTest(dispatcher) {
            val api =
                FakeConversationsApi(
                    searchResult =
                        SearchConversationsResult.Loaded(
                            ConversationSearchPage(listOf(hit("m1")), nextBeforeMessageId = "m1", searchedFrom = "f", searchedTo = "t"),
                        ),
                    hangSearchMore = true,
                )
            val viewModel = ConversationSearchViewModel(api = api, ioDispatcher = dispatcher)
            viewModel.onQueryChange("refund")
            viewModel.search()
            advanceUntilIdle()

            viewModel.loadMore()
            dispatcher.scheduler.runCurrent()
            viewModel.loadMore()
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.searchCalls.count { it.second == "m1" })
        }

    @Test
    fun `a failed load more keeps the hits already on screen`() =
        runTest(dispatcher) {
            val api =
                FakeConversationsApi(
                    searchResult =
                        SearchConversationsResult.Loaded(
                            ConversationSearchPage(listOf(hit("m1")), nextBeforeMessageId = "m1", searchedFrom = "f", searchedTo = "t"),
                        ),
                )
            val viewModel = ConversationSearchViewModel(api = api, ioDispatcher = dispatcher)
            viewModel.onQueryChange("refund")
            viewModel.search()
            advanceUntilIdle()

            api.searchResult = SearchConversationsResult.Failed(NetworkFailure.NoConnection)
            viewModel.loadMore()
            advanceUntilIdle()

            val phase = viewModel.state.value.phase as ConversationSearchPhase.Results
            assertEquals(listOf(hit("m1")), phase.hits)
            assertEquals("m1", phase.nextBeforeMessageId)
            assertEquals(false, phase.loadingMore)
        }

    private fun hit(messageId: String) =
        ConversationSearchHit(
            conversationId = "c-$messageId",
            messageId = messageId,
            sequence = 1,
            matchedBody = "matched $messageId",
            authorKind = "Visitor",
            createdAt = "2026-09-22T09:00:00Z",
            conversationState = "Assigned",
        )

    private class FakeConversationsApi(
        var searchResult: SearchConversationsResult =
            SearchConversationsResult.Loaded(ConversationSearchPage(emptyList(), null, "f", "t")),
        private val hangSearch: Boolean = false,
        private val hangSearchMore: Boolean = false,
    ) : ConversationsApi {
        /** One entry per [searchConversations] call, as `phrase to beforeMessageId`. */
        val searchCalls: MutableList<Pair<String, String?>> = mutableListOf()

        override suspend fun searchConversations(
            phrase: String,
            from: String?,
            to: String?,
            beforeMessageId: String?,
            pageSize: Int?,
        ): SearchConversationsResult {
            searchCalls.add(phrase to beforeMessageId)
            if (beforeMessageId == null) {
                if (hangSearch) awaitCancellation()
            } else {
                if (hangSearchMore) awaitCancellation()
            }
            return searchResult
        }

        // The list/claim/read/erase halves belong to `ConversationListViewModel`, not this screen's own
        // view model - the identical "not used by this class" posture `RestrictedVisitorsViewModelTest`'s
        // own fake takes for its port's other methods.
        override suspend fun fetchQueue(): QueueResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun claim(conversationId: String): ClaimResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun markRead(
            conversationId: String,
            upToSequence: Int,
        ): Boolean = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchAllConversations(
            beforeId: String?,
            pageSize: Int,
            states: List<String>,
        ): AllConversationsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun requestErasure(conversationId: String): ErasureResult =
            throw UnsupportedOperationException("not used by this class")
    }
}
