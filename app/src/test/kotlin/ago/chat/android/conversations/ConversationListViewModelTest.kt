package ago.chat.android.conversations

import ago.chat.android.core.domain.conversations.ClaimResult
import ago.chat.android.core.domain.conversations.ConversationListCache
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.ConversationSummary
import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.domain.conversations.QueueResult
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.network.realtime.ConversationAssignedDto
import ago.chat.android.core.network.realtime.HistoryPage
import ago.chat.android.core.network.realtime.MessageDeliveredDto
import ago.chat.android.core.network.realtime.MessageDto
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.core.network.realtime.SendMessageResult
import ago.chat.android.core.network.realtime.TeamHistoryPage
import ago.chat.android.core.network.realtime.TeamMessageDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `26-14`: the state machine's own properties — the four this item's Done-when names, plus the two the
 * ticket asked to be genuinely provable with no server and no real session: stale-until-proven-fresh,
 * "a new assignment never navigates", "a claim's refusal is never retried", and the poll running only
 * where it is actually needed. A plain JVM test, the same `SignInViewModelTest` shape - `StandardTestDispatcher`
 * plus `Dispatchers.setMain`, so every coroutine this class launches is driven by hand rather than by a
 * real clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------- stale until proven fresh

    @Test
    fun `a cached queue renders immediately, marked stale, before the network answers`() =
        runTest(dispatcher) {
            val cache = FakeConversationListCache(cached = queueOf(mine = listOf(waiting("c1"))))
            val api = FakeConversationsApi(hangQueueFetch = true)
            val viewModel = viewModelWith(api = api, cache = cache)

            // Only the cache read has had a chance to run - the network call is still pending, exactly
            // the "never blocks on the network" property this screen exists to prove.
            dispatcher.scheduler.runCurrent()

            assertTrue("the cached row is on screen immediately", viewModel.state.value.hasData)
            assertTrue("and marked stale until the network confirms it", viewModel.state.value.isStale)
            assertEquals(1, viewModel.state.value.mine.size)
        }

    @Test
    fun `a successful fetch clears the stale flag and replaces the cached rows`() =
        runTest(dispatcher) {
            val cache = FakeConversationListCache(cached = queueOf(mine = listOf(waiting("stale-row"))))
            val api = FakeConversationsApi(queueResult = QueueResult.Loaded(queueOf(mine = listOf(waiting("fresh-row")))))
            val viewModel = viewModelWith(api = api, cache = cache)

            advanceUntilIdle()

            assertFalse(viewModel.state.value.isStale)
            assertEquals(
                listOf("fresh-row"),
                viewModel.state.value.mine
                    .map { it.conversationId },
            )
        }

    @Test
    fun `killing the network still renders the cached list, visibly marked stale, never blank`() =
        runTest(dispatcher) {
            val cache = FakeConversationListCache(cached = queueOf(mine = listOf(waiting("cached-row"))))
            val api = FakeConversationsApi(queueResult = QueueResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModelWith(api = api, cache = cache)

            advanceUntilIdle()

            assertTrue("the cache is not thrown away on a failed fetch", viewModel.state.value.hasData)
            assertEquals(
                listOf("cached-row"),
                viewModel.state.value.mine
                    .map { it.conversationId },
            )
            assertTrue("still marked stale - the fetch never actually confirmed it", viewModel.state.value.isStale)
            assertEquals(NetworkFailure.NoConnection, viewModel.state.value.loadError)
        }

    @Test
    fun `no cache and no network yet is a real loading state, not an invented empty list`() =
        runTest(dispatcher) {
            val viewModel = viewModelWith(api = FakeConversationsApi(hangQueueFetch = true))

            dispatcher.scheduler.runCurrent()

            assertFalse(viewModel.state.value.hasData)
        }

    // -------------------------------------------------------------------------- assignment pushes

    @Test
    fun `a ConversationAssigned push marks the row New and re-fetches, and never navigates`() =
        runTest(dispatcher) {
            val hubEvents = FakeOperatorHubEvents()
            val api =
                FakeConversationsApi(
                    queueResult = QueueResult.Loaded(queueOf(mine = listOf(waiting("new-conv")))),
                )
            val viewModel = viewModelWith(api = api, hubEvents = hubEvents)
            advanceUntilIdle()
            val fetchesBeforePush = api.fetchCalls

            hubEvents.assignments.tryEmit(ConversationAssignedDto("new-conv", "op-1", "2026-09-22T10:00:00Z"))
            advanceUntilIdle()

            val row =
                viewModel.state.value.mine
                    .single { it.conversationId == "new-conv" }
            assertTrue("the row carries the New badge", row.isNewlyAssigned)
            assertTrue(
                "the push triggered a re-fetch, the same way the console re-fetches on assignment",
                api.fetchCalls > fetchesBeforePush,
            )
            // The whole point of this test: nothing about this class's public surface is a navigation
            // event. `selectedTab` - the only thing that could move the operator anywhere on this
            // screen - is unchanged, and there is no second channel to have fired one on.
            assertEquals(ConversationListTab.Mine, viewModel.state.value.selectedTab)
        }

    @Test
    fun `opening a newly-assigned row clears its New badge`() =
        runTest(dispatcher) {
            val hubEvents = FakeOperatorHubEvents()
            val api = FakeConversationsApi(queueResult = QueueResult.Loaded(queueOf(mine = listOf(waiting("c1")))))
            val viewModel = viewModelWith(api = api, hubEvents = hubEvents)
            advanceUntilIdle()
            hubEvents.assignments.tryEmit(ConversationAssignedDto("c1", "op-1", "2026-09-22T10:00:00Z"))
            advanceUntilIdle()
            assertTrue(
                viewModel.state.value.mine
                    .single()
                    .isNewlyAssigned,
            )

            viewModel.onRowOpened("c1")

            assertFalse(
                viewModel.state.value.mine
                    .single()
                    .isNewlyAssigned,
            )
        }

    // ------------------------------------------------------------------------------- unread counts

    @Test
    fun `a visitor message on an assigned conversation bumps its unread count live`() =
        runTest(dispatcher) {
            val hubEvents = FakeOperatorHubEvents()
            val api =
                FakeConversationsApi(
                    queueResult = QueueResult.Loaded(queueOf(mine = listOf(waiting("c1", unread = 2)))),
                )
            val viewModel = viewModelWith(api = api, hubEvents = hubEvents)
            advanceUntilIdle()

            hubEvents.allMessages.tryEmit(MessageDto(id = "m1", sequence = 1, conversationId = "c1", authorKind = "Visitor"))
            advanceUntilIdle()

            assertEquals(
                3,
                viewModel.state.value.mine
                    .single()
                    .unreadCount,
            )
        }

    @Test
    fun `the operator's own echoed message never counts as unread`() =
        runTest(dispatcher) {
            val hubEvents = FakeOperatorHubEvents()
            val api = FakeConversationsApi(queueResult = QueueResult.Loaded(queueOf(mine = listOf(waiting("c1", unread = 0)))))
            val viewModel = viewModelWith(api = api, hubEvents = hubEvents)
            advanceUntilIdle()

            hubEvents.allMessages.tryEmit(MessageDto(id = "m1", sequence = 1, conversationId = "c1", authorKind = "Operator"))
            advanceUntilIdle()

            assertEquals(
                0,
                viewModel.state.value.mine
                    .single()
                    .unreadCount,
            )
        }

    // ---------------------------------------------------------------------------- `26-30`: snippet

    @Test
    fun `lastMessagePreview and lastMessageAt carry through to the row unchanged`() =
        runTest(dispatcher) {
            val summaryWithSnippet =
                waiting("c1").copy(lastMessagePreview = "how can I help?", lastMessageAt = "2026-09-22T09:05:00Z")
            val api = FakeConversationsApi(queueResult = QueueResult.Loaded(queueOf(mine = listOf(summaryWithSnippet))))
            val viewModel = viewModelWith(api = api)

            advanceUntilIdle()

            val row =
                viewModel.state.value.mine
                    .single()
            assertEquals("how can I help?", row.lastMessagePreview)
            assertEquals("2026-09-22T09:05:00Z", row.lastMessageAt)
        }

    @Test
    fun `26-76 lastMessageContentKind carries through to the row unchanged`() =
        runTest(dispatcher) {
            val summaryWithModuleStep =
                waiting("c1").copy(lastMessagePreview = "Выберите дату", lastMessageContentKind = "choice_list")
            val api = FakeConversationsApi(queueResult = QueueResult.Loaded(queueOf(mine = listOf(summaryWithModuleStep))))
            val viewModel = viewModelWith(api = api)

            advanceUntilIdle()

            val row =
                viewModel.state.value.mine
                    .single()
            assertEquals("choice_list", row.lastMessageContentKind)
        }

    @Test
    fun `the operator's own message still refreshes the row's snippet, live`() =
        runTest(dispatcher) {
            // `26-30`: found live on a real device - an operator sent a reply, returned to the list, and
            // read their own words from before the send. `onMessage` used to return immediately for any
            // non-`Visitor` author, which correctly kept an operator's own echoed send out of the unread
            // count but wrongly also kept it from ever refreshing the snippet, since `26-29`'s own field
            // is "the latest message", not "the latest visitor message".
            val hubEvents = FakeOperatorHubEvents()
            val api =
                FakeConversationsApi(
                    queueResult =
                        QueueResult.Loaded(
                            queueOf(mine = listOf(waiting("c1").copy(lastMessagePreview = "before the send"))),
                        ),
                )
            val viewModel = viewModelWith(api = api, hubEvents = hubEvents)
            advanceUntilIdle()

            // The server's own snapshot changes between the send and the next fetch - exactly what a
            // real `refresh()` round trip would see, not a client-side patch of the old snippet.
            api.queueResult =
                QueueResult.Loaded(
                    queueOf(mine = listOf(waiting("c1").copy(lastMessagePreview = "after the send"))),
                )
            hubEvents.allMessages.tryEmit(MessageDto(id = "m1", sequence = 1, conversationId = "c1", authorKind = "Operator"))
            advanceUntilIdle()

            assertEquals(
                "after the send",
                viewModel.state.value.mine
                    .single()
                    .lastMessagePreview,
            )
        }

    @Test
    fun `a row with no messages carries no snippet at all, not an empty placeholder`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi(queueResult = QueueResult.Loaded(queueOf(mine = listOf(waiting("c1")))))
            val viewModel = viewModelWith(api = api)

            advanceUntilIdle()

            val row =
                viewModel.state.value.mine
                    .single()
            assertNull(row.lastMessagePreview)
            assertNull(row.lastMessageAt)
        }

    // -------------------------------------------------------------------------------------- claim

    @Test
    fun `claiming successfully re-fetches so the row's own move into Мои is observed, not assumed`() =
        runTest(dispatcher) {
            val api =
                FakeConversationsApi(
                    queueResult = QueueResult.Loaded(queueOf(waiting = listOf(waiting("c1")))),
                    claimResult = { ClaimResult.Claimed },
                )
            val viewModel = viewModelWith(api = api)
            advanceUntilIdle()

            // The server's own answer, from here on: the conversation moved.
            api.queueResult = QueueResult.Loaded(queueOf(mine = listOf(waiting("c1"))))
            viewModel.claim("c1")
            advanceUntilIdle()

            assertEquals(listOf("c1"), api.claimCalls)
            assertTrue(
                viewModel.state.value.waiting
                    .isEmpty(),
            )
            assertEquals(
                listOf("c1"),
                viewModel.state.value.mine
                    .map { it.conversationId },
            )
        }

    @Test
    fun `a claim another operator already won renders the refusal and is never retried`() =
        runTest(dispatcher) {
            val api =
                FakeConversationsApi(
                    queueResult = QueueResult.Loaded(queueOf(waiting = listOf(waiting("c1")))),
                    claimResult = { ClaimResult.Refused("Этот диалог уже забрал другой оператор.") },
                )
            val viewModel = viewModelWith(api = api)
            advanceUntilIdle()

            viewModel.claim("c1")
            advanceUntilIdle()

            assertEquals("exactly one attempt was made", listOf("c1"), api.claimCalls)
            val row =
                viewModel.state.value.waiting
                    .single()
            assertEquals(ClaimErrorUi.ServerRefusal("Этот диалог уже забрал другой оператор."), row.claimError)
            assertFalse(
                "the row is not silently moved on a refusal",
                row.conversationId in
                    viewModel.state.value.mine
                        .map { it.conversationId },
            )

            // Time passes. Nothing retries it on its own.
            advanceTimeBy(60_000)
            assertEquals(listOf("c1"), api.claimCalls)
        }

    @Test
    fun `dismissing a claim error clears it without asking the server anything`() =
        runTest(dispatcher) {
            val api =
                FakeConversationsApi(
                    queueResult = QueueResult.Loaded(queueOf(waiting = listOf(waiting("c1")))),
                    claimResult = { ClaimResult.Refused("нет") },
                )
            val viewModel = viewModelWith(api = api)
            advanceUntilIdle()
            viewModel.claim("c1")
            advanceUntilIdle()

            viewModel.dismissClaimError("c1")

            assertNull(
                viewModel.state.value.waiting
                    .single()
                    .claimError,
            )
            assertEquals(1, api.claimCalls.size)
        }

    @Test
    fun `claiming successfully switches to Мои and fires the navigation event straight off the response`() =
        runTest(dispatcher) {
            // `26-75`: `hangQueueFetch` makes `refresh()`'s own re-fetch hang forever - if the
            // navigation below only fired once that re-fetch answered, this test would never get past
            // `runCurrent()` below. It does, which is the proof the event fires directly off
            // `ClaimResult.Claimed`, the same synchronous guarantee `ago-console`'s own
            // `ClaimConversationButtonProps.onClaimed` documents.
            //
            // `runCurrent()`, not `advanceUntilIdle()`, on purpose from here down: selecting «Ожидают»
            // starts [ConversationListViewModel.startWaitingPollIfNeeded]'s own `while (isActive) {
            // delay(...); refresh() }` loop, and `advanceUntilIdle()` fast-forwards virtual time through
            // every one of that loop's own `delay` calls forever, since nothing ever satisfies it - the
            // exact trap `` `the waiting poll only runs while Ожидают is selected and the screen is
            // visible` `` avoids with its own bounded `advanceTimeBy` calls, restated here because this
            // test also needs the tab actually selected. `onTabSelected(Mine)` inside the `Claimed`
            // branch below cancels that job before its first `delay` ever elapses, so `runCurrent()` -
            // draining only what is runnable *now*, never touching a future-scheduled `delay` - is
            // enough to observe the whole claim without ever running the poll body at all.
            val api =
                FakeConversationsApi(
                    queueResult = QueueResult.Loaded(queueOf(waiting = listOf(waiting("c1")))),
                    claimResult = { ClaimResult.Claimed },
                )
            val viewModel = viewModelWith(api = api)
            advanceUntilIdle()
            viewModel.onTabSelected(ConversationListTab.Waiting)
            runCurrent()

            val navigated = mutableListOf<String>()
            // `SettingsViewModelTest`'s own `` `switchSite carries the new site id on siteSwitched...` ``
            // establishes this shape for the identical reason: a plain `launch` here would make
            // `collectJob` a child of this test's own scope, which never completes on its own since
            // [ConversationListViewModel.claimedConversations] never closes - a separate
            // `CoroutineScope(dispatcher)` is not tracked by `runTest`'s own completion check at all.
            val collectJob = CoroutineScope(dispatcher).launch { viewModel.claimedConversations.collect { navigated.add(it) } }

            api.hangQueueFetch = true
            viewModel.claim("c1")
            runCurrent()

            assertEquals(listOf("c1"), navigated)
            assertEquals(
                "claiming switches the operator to Мои the same way tapping the tab would",
                ConversationListTab.Mine,
                viewModel.state.value.selectedTab,
            )

            collectJob.cancel()
        }

    @Test
    fun `a refused claim never fires the navigation event or switches tabs`() =
        runTest(dispatcher) {
            // `runCurrent()`, not `advanceUntilIdle()`: unlike the `Claimed` case above, nothing in the
            // `Refused` branch ever cancels the «Ожидают» poll started below, so `advanceUntilIdle()`
            // would run that job's own `while (isActive) { delay(...); refresh() }` loop forever - see
            // the sibling test above for the full reasoning.
            val api =
                FakeConversationsApi(
                    queueResult = QueueResult.Loaded(queueOf(waiting = listOf(waiting("c1")))),
                    claimResult = { ClaimResult.Refused("Этот диалог уже забрал другой оператор.") },
                )
            val viewModel = viewModelWith(api = api)
            advanceUntilIdle()
            viewModel.onTabSelected(ConversationListTab.Waiting)
            runCurrent()

            val navigated = mutableListOf<String>()
            val collectJob = CoroutineScope(dispatcher).launch { viewModel.claimedConversations.collect { navigated.add(it) } }

            viewModel.claim("c1")
            runCurrent()

            assertEquals("a refusal never navigates anywhere", emptyList<String>(), navigated)
            assertEquals(
                "a refusal leaves the operator exactly where they were, in «Ожидают»",
                ConversationListTab.Waiting,
                viewModel.state.value.selectedTab,
            )

            collectJob.cancel()
            // The «Ожидают» poll this test started is still active (never cancelled by a refusal) -
            // stopped explicitly rather than left to outlive the test.
            viewModel.onScreenStopped()
        }

    // ------------------------------------------------------------------------------- poll interval

    @Test
    fun `the waiting poll only runs while Ожидают is selected and the screen is visible`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi(queueResult = QueueResult.Loaded(queueOf()))
            val viewModel = viewModelWith(api = api)
            advanceUntilIdle()
            val initialFetches = api.fetchCalls

            // Selecting «Мои» (the default) must never start a background timer - the hub pushes are
            // this tab's own freshness signal, and a phone should not spend data polling a tab nobody
            // is looking at (this class's own `startWaitingPollIfNeeded` doc comment).
            advanceTimeBy(60_000)
            assertEquals("no poll while «Мои» is selected", initialFetches, api.fetchCalls)

            viewModel.onTabSelected(ConversationListTab.Waiting)
            advanceTimeBy(15_001)
            assertEquals("one poll tick at the 15s mark, matching ago-console's own interval", initialFetches + 1, api.fetchCalls)

            advanceTimeBy(15_000)
            assertEquals("a second tick 15s later", initialFetches + 2, api.fetchCalls)

            viewModel.onScreenStopped()
            val fetchesAfterStop = api.fetchCalls
            advanceTimeBy(60_000)
            assertEquals("stopping the screen cancels the poll - no further ticks", fetchesAfterStop, api.fetchCalls)
        }

    @Test
    fun `switching back to Мои stops the poll without needing onScreenStopped`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi(queueResult = QueueResult.Loaded(queueOf()))
            val viewModel = viewModelWith(api = api)
            advanceUntilIdle()

            viewModel.onTabSelected(ConversationListTab.Waiting)
            advanceTimeBy(15_001)
            val fetchesWhileWaiting = api.fetchCalls

            viewModel.onTabSelected(ConversationListTab.Mine)
            advanceTimeBy(60_000)

            assertEquals(fetchesWhileWaiting, api.fetchCalls)
        }

    // ---------------------------------------------------------------------- 26-17: active site changes

    @Test
    fun `the first onActiveSiteChanged call never re-fetches - init's own first fetch already covers it`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi(queueResult = QueueResult.Loaded(queueOf()))
            val viewModel = viewModelWith(api = api)
            advanceUntilIdle()
            val fetchesAfterInit = api.fetchCalls

            viewModel.onActiveSiteChanged("11111111-1111-1111-1111-111111111111")
            advanceUntilIdle()

            assertEquals(fetchesAfterInit, api.fetchCalls)
        }

    /**
     * The exact case `ConversationListRoute`'s own `LaunchedEffect(activeSiteId)` produces on every
     * remount whose `activeSiteId` value has not actually changed - `BackContractDialogsTabTest`'s
     * "back from a thread does not re-fetch" is this same shape at the UI-test level. Proven here
     * directly against the view model, with no Compose in play at all.
     */
    @Test
    fun `being told the same site again is a no-op, not a re-fetch`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi(queueResult = QueueResult.Loaded(queueOf()))
            val viewModel = viewModelWith(api = api)
            advanceUntilIdle()

            viewModel.onActiveSiteChanged("11111111-1111-1111-1111-111111111111")
            advanceUntilIdle()
            val fetchesAfterFirstTold = api.fetchCalls

            viewModel.onActiveSiteChanged("11111111-1111-1111-1111-111111111111")
            advanceUntilIdle()

            assertEquals(fetchesAfterFirstTold, api.fetchCalls)
        }

    @Test
    fun `a genuinely different site refreshes the queue`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi(queueResult = QueueResult.Loaded(queueOf()))
            val viewModel = viewModelWith(api = api)
            advanceUntilIdle()

            viewModel.onActiveSiteChanged("11111111-1111-1111-1111-111111111111")
            advanceUntilIdle()
            val fetchesBeforeSwitch = api.fetchCalls

            viewModel.onActiveSiteChanged("22222222-2222-2222-2222-222222222222")
            advanceUntilIdle()

            assertEquals(fetchesBeforeSwitch + 1, api.fetchCalls)
        }

    // ------------------------------------------------------------------------------------- fakes

    private fun viewModelWith(
        api: ConversationsApi,
        cache: ConversationListCache = FakeConversationListCache(),
        hubEvents: FakeOperatorHubEvents = FakeOperatorHubEvents(),
    ): ConversationListViewModel =
        ConversationListViewModel(
            api = api,
            cache = cache,
            hubEvents = hubEvents,
            ioDispatcher = dispatcher,
        )

    private fun waiting(
        id: String,
        unread: Int = 0,
    ) = ConversationSummary(
        conversationId = id,
        visitorId = "visitor-$id",
        emojiCreature = "🦊",
        emojiFood = "🍕",
        visitorName = null,
        createdAt = "2026-09-22T09:00:00Z",
        operatorUnreadCount = unread,
    )

    private fun queueOf(
        waiting: List<ConversationSummary> = emptyList(),
        mine: List<ConversationSummary> = emptyList(),
    ) = ConversationQueue(waiting = waiting, assignedToMe = mine)

    private class FakeConversationsApi(
        var queueResult: QueueResult = QueueResult.Failed(NetworkFailure.Unexpected),
        /** When set, [fetchQueue] never returns at all - proves a render sourced only from the cache,
         * with the network call genuinely still pending rather than merely fast. */
        var hangQueueFetch: Boolean = false,
        var claimResult: (String) -> ClaimResult = { ClaimResult.Claimed },
    ) : ConversationsApi {
        var fetchCalls: Int = 0
            private set
        val claimCalls: MutableList<String> = mutableListOf()

        override suspend fun fetchQueue(): QueueResult {
            fetchCalls++
            if (hangQueueFetch) awaitCancellation()
            return queueResult
        }

        override suspend fun claim(conversationId: String): ClaimResult {
            claimCalls.add(conversationId)
            return claimResult(conversationId)
        }
    }

    private class FakeConversationListCache(
        private var cached: ConversationQueue? = null,
    ) : ConversationListCache {
        override suspend fun read(): ConversationQueue? = cached

        override suspend fun write(queue: ConversationQueue) {
            cached = queue
        }
    }

    private class FakeOperatorHubEvents : OperatorHubEvents {
        override val state = MutableStateFlow<OperatorHubConnectionState>(OperatorHubConnectionState.Disconnected)
        override val messages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 16)
        override val allMessages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 16)
        override val assignments = MutableSharedFlow<ConversationAssignedDto>(extraBufferCapacity = 16)
        override val messageDelivered = MutableSharedFlow<MessageDeliveredDto>(extraBufferCapacity = 16)
        override val teamMessages = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 16)
        override val teamMessageRemovals = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 16)

        // `26-15`: this screen never joins/sends/pages history - it only ever reads [assignments]/
        // [allMessages] above - so these four exist purely to satisfy the interface, the same "not this
        // screen's concern" reasoning `ThreadViewModelTest`'s own fake states for its own unused members.
        override suspend fun joinConversation(conversationId: String): HistoryPage = error("not used by this screen")

        override fun leaveConversation() = error("not used by this screen")

        override suspend fun loadOlderHistory(
            conversationId: String,
            beforeSequence: Long,
            pageSize: Int,
        ): HistoryPage = error("not used by this screen")

        override suspend fun sendMessage(
            conversationId: String,
            body: String,
            clientMessageId: String,
            attachmentId: String?,
        ): SendMessageResult = error("not used by this screen")

        override suspend fun reconnectToActiveSite() = error("not used by this screen")

        override suspend fun getTeamHistory(
            beforeSequence: Long?,
            pageSize: Int,
        ): TeamHistoryPage = error("not used by this screen")

        override suspend fun getTeamDelta(afterSequence: Long): TeamHistoryPage = error("not used by this screen")

        override suspend fun sendTeamMessage(
            body: String,
            clientMessageId: String,
        ): SendMessageResult = error("not used by this screen")

        override suspend fun removeTeamMessage(teamMessageId: String) = error("not used by this screen")
    }
}
