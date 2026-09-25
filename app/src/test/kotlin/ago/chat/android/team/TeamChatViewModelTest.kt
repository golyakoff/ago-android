package ago.chat.android.team

import ago.chat.android.core.network.realtime.ConversationAssignedDto
import ago.chat.android.core.network.realtime.HistoryPage
import ago.chat.android.core.network.realtime.MessageDeliveredDto
import ago.chat.android.core.network.realtime.MessageDto
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.core.network.realtime.SendMessageResult
import ago.chat.android.core.network.realtime.TeamHistoryPage
import ago.chat.android.core.network.realtime.TeamMessageDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
 * `26-54`'s own Done-when, proven with no server and no real session — the identical posture
 * `ThreadViewModelTest` already establishes for the conversation screen: the connection-gated first
 * load, the reconnect-catches-up-by-delta path (the one behaviour a real device is unlikely to exercise
 * on demand — see this file's own `reconnect` tests for why this is where that guarantee is actually
 * pinned), the keyset boundary, dedup, removal, and the reused [SendMessageResult] retry-id rule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TeamChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --------------------------------------------------------------------------- connection gating

    @Test
    fun `no history call fires before the connection reports Connected`() =
        runTest(dispatcher) {
            val hub = FakeTeamHubEvents(initialState = OperatorHubConnectionState.Connecting)
            val viewModel = TeamChatViewModel(hub)
            advanceUntilIdle()

            assertTrue("the initial load must wait for Connected", hub.getTeamHistoryCalls.isEmpty())
            assertTrue(viewModel.state.value.loading)
        }

    @Test
    fun `history loads once the connection reports Connected`() =
        runTest(dispatcher) {
            val hub = FakeTeamHubEvents(initialState = OperatorHubConnectionState.Connecting, fixtureAscending = messages(1..3))
            val viewModel = TeamChatViewModel(hub)
            advanceUntilIdle()
            assertTrue(hub.getTeamHistoryCalls.isEmpty())

            hub.state.value = OperatorHubConnectionState.Connected
            advanceUntilIdle()

            assertEquals(listOf<Long?>(null), hub.getTeamHistoryCalls)
            assertFalse(viewModel.state.value.loading)
            assertEquals(
                listOf(1L, 2L, 3L),
                viewModel.state.value.messages
                    .map { it.sequence },
            )
        }

    @Test
    fun `already connected at construction loads immediately - no crash landing directly on the room`() =
        runTest(dispatcher) {
            val hub = FakeTeamHubEvents(initialState = OperatorHubConnectionState.Connected, fixtureAscending = messages(1..2))
            val viewModel = TeamChatViewModel(hub)
            advanceUntilIdle()

            assertEquals(1, hub.getTeamHistoryCalls.size)
            assertFalse(viewModel.state.value.loading)
            assertEquals(
                listOf(1L, 2L),
                viewModel.state.value.messages
                    .map { it.sequence },
            )
        }

    // -------------------------------------------------------------------------- reconnect catch-up

    @Test
    fun `a reconnect asks for the delta after the last rendered sequence, never a second history call`() =
        runTest(dispatcher) {
            val hub = FakeTeamHubEvents(initialState = OperatorHubConnectionState.Connected, fixtureAscending = messages(1..3))
            val viewModel = TeamChatViewModel(hub)
            advanceUntilIdle()
            assertEquals(1, hub.getTeamHistoryCalls.size)
            assertEquals(
                3L,
                viewModel.state.value.messages
                    .maxOf { it.sequence },
            )

            // `advanceUntilIdle()` between each state change is deliberate, not incidental: a
            // `StateFlow` only guarantees a slow collector eventually sees the *latest* value, not
            // every value it passed through, so setting `Disconnected` and `Connected` back to back
            // with no suspension in between (the collector never gets scheduled) can be conflated away
            // entirely - a real reconnect always has a genuine `start()` suspension between the two, so
            // this is a test-fidelity fix, not a change to what the view model itself guarantees.
            hub.state.value = OperatorHubConnectionState.Disconnected
            advanceUntilIdle()
            hub.state.value = OperatorHubConnectionState.Reconnecting
            advanceUntilIdle()
            hub.deltaFixture = listOf(teamMessage(4), teamMessage(5))
            hub.state.value = OperatorHubConnectionState.Connected
            advanceUntilIdle()

            assertEquals("no second GetTeamHistoryAsync call - a reconnect never rejoins", 1, hub.getTeamHistoryCalls.size)
            assertEquals(listOf(3L), hub.getTeamDeltaCalls)
            assertEquals(
                (1L..5L).toList(),
                viewModel.state.value.messages
                    .map { it.sequence },
            )
        }

    @Test
    fun `a second reconnect asks for the delta after the first reconnect's own catch-up`() =
        runTest(dispatcher) {
            val hub = FakeTeamHubEvents(initialState = OperatorHubConnectionState.Connected, fixtureAscending = messages(1..1))
            val viewModel = TeamChatViewModel(hub)
            advanceUntilIdle()

            hub.deltaFixture = listOf(teamMessage(2))
            hub.state.value = OperatorHubConnectionState.Disconnected
            advanceUntilIdle()
            hub.state.value = OperatorHubConnectionState.Connected
            advanceUntilIdle()

            hub.deltaFixture = listOf(teamMessage(3))
            hub.state.value = OperatorHubConnectionState.Disconnected
            advanceUntilIdle()
            hub.state.value = OperatorHubConnectionState.Connected
            advanceUntilIdle()

            assertEquals(listOf(1L, 2L), hub.getTeamDeltaCalls)
            assertEquals(
                (1L..3L).toList(),
                viewModel.state.value.messages
                    .map { it.sequence },
            )
        }

    // ------------------------------------------------------------------------------ inbound dedup

    @Test
    fun `a redelivered live push renders once`() =
        runTest(dispatcher) {
            val hub = FakeTeamHubEvents(initialState = OperatorHubConnectionState.Connected, fixtureAscending = messages(1..1))
            val viewModel = TeamChatViewModel(hub)
            advanceUntilIdle()

            val pushed = teamMessage(99, id = "dup")
            hub.teamMessages.tryEmit(pushed)
            hub.teamMessages.tryEmit(pushed)
            advanceUntilIdle()

            assertEquals(
                1,
                viewModel.state.value.messages
                    .count { it.id == "dup" },
            )
            assertEquals(2, viewModel.state.value.messages.size)
        }

    // ---------------------------------------------------------------------------------- removal

    @Test
    fun `a removal push tombstones the matching message already on screen`() =
        runTest(dispatcher) {
            val hub = FakeTeamHubEvents(initialState = OperatorHubConnectionState.Connected, fixtureAscending = messages(1..1))
            val viewModel = TeamChatViewModel(hub)
            advanceUntilIdle()

            val removed = teamMessage(1, id = "m1", body = null).copy(removedAt = "2026-09-23T10:00:00Z")
            hub.teamMessageRemovals.tryEmit(removed)
            advanceUntilIdle()

            val onScreen =
                viewModel.state.value.messages
                    .single()
            assertEquals("2026-09-23T10:00:00Z", onScreen.removedAt)
            assertNull(onScreen.body)
        }

    @Test
    fun `a removal for a message never loaded is ignored`() =
        runTest(dispatcher) {
            val hub = FakeTeamHubEvents(initialState = OperatorHubConnectionState.Connected, fixtureAscending = emptyList())
            val viewModel = TeamChatViewModel(hub)
            advanceUntilIdle()

            hub.teamMessageRemovals.tryEmit(teamMessage(1, id = "never-seen").copy(removedAt = "2026-09-23T10:00:00Z"))
            advanceUntilIdle()

            assertTrue(
                viewModel.state.value.messages
                    .isEmpty(),
            )
        }

    // ---------------------------------------------------------------------------- outbound retry

    @Test
    fun `a send retried after an ambiguous outcome reuses the exact same clientMessageId`() =
        runTest(dispatcher) {
            val hub = FakeTeamHubEvents(initialState = OperatorHubConnectionState.Connected)
            val viewModel = TeamChatViewModel(hub)
            advanceUntilIdle()

            hub.sendResults.add(SendMessageResult.OutcomeUnknown(RuntimeException("connection dropped mid-invoke")))
            viewModel.onDraftChanged("Здравствуйте, команда!")
            viewModel.sendClicked()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.pendingRetry)
            assertEquals(1, hub.sendCalls.size)
            val firstId = hub.sendCalls.single().second

            viewModel.retrySend()
            advanceUntilIdle()

            assertEquals(2, hub.sendCalls.size)
            assertEquals(firstId, hub.sendCalls[1].second)
            assertFalse(viewModel.state.value.pendingRetry)
        }

    @Test
    fun `a send retried after not-connected uses a fresh id`() =
        runTest(dispatcher) {
            val hub = FakeTeamHubEvents(initialState = OperatorHubConnectionState.Connected)
            val viewModel = TeamChatViewModel(hub)
            advanceUntilIdle()

            hub.sendResults.add(SendMessageResult.NotConnected)
            viewModel.onDraftChanged("Привет")
            viewModel.sendClicked()
            advanceUntilIdle()
            val firstId = hub.sendCalls.single().second

            viewModel.retrySend()
            advanceUntilIdle()

            assertEquals(2, hub.sendCalls.size)
            assertTrue(hub.sendCalls[1].second != firstId)
        }

    @Test
    fun `a definitive refusal is shown once and is never retried automatically`() =
        runTest(dispatcher) {
            val hub = FakeTeamHubEvents(initialState = OperatorHubConnectionState.Connected)
            val viewModel = TeamChatViewModel(hub)
            advanceUntilIdle()

            hub.sendResults.add(SendMessageResult.Refused("Room is unavailable."))
            viewModel.onDraftChanged("hi")
            viewModel.sendClicked()
            advanceUntilIdle()

            assertEquals("Room is unavailable.", viewModel.state.value.sendRefusedMessage)
            assertFalse(viewModel.state.value.pendingRetry)

            viewModel.retrySend()
            advanceUntilIdle()
            assertEquals(1, hub.sendCalls.size)
        }

    // ---------------------------------------------------------------------------- keyset paging

    @Test
    fun `loadOlder pages backward without duplicating the boundary message`() =
        runTest(dispatcher) {
            val hub = FakeTeamHubEvents(initialState = OperatorHubConnectionState.Connected, fixtureAscending = messages(1..75))
            val viewModel = TeamChatViewModel(hub)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.canLoadOlder)

            viewModel.loadOlder()
            advanceUntilIdle()

            assertFalse(viewModel.state.value.canLoadOlder)
            assertEquals(
                (1L..75L).toList(),
                viewModel.state.value.messages
                    .map { it.sequence },
            )
            assertEquals(listOf<Long?>(null, 26L), hub.getTeamHistoryCalls)
        }

    // ------------------------------------------------------------------------------------- fakes

    private fun messages(range: IntRange): List<TeamMessageDto> = range.map { seq -> teamMessage(seq) }

    private fun teamMessage(
        seq: Int,
        id: String = "m$seq",
        body: String? = "message $seq",
    ): TeamMessageDto =
        TeamMessageDto(
            id = id,
            sequence = seq.toLong(),
            authorOperatorId = "op-1",
            authorDisplayName = "Оператор",
            body = body,
            createdAt = "2026-09-22T09:00:00Z",
        )

    /**
     * Mirrors `ago-chat`'s own `TeamMessageReadStore` keyset shape - `sequence < @BeforeSequence`,
     * newest first, `nextBeforeSequence` set only when the page came back full - the identical fidelity
     * `ThreadViewModelTest`'s own `FakeOperatorHubEvents` keeps for a conversation's history.
     */
    private class FakeTeamHubEvents(
        initialState: OperatorHubConnectionState = OperatorHubConnectionState.Connected,
        fixtureAscending: List<TeamMessageDto> = emptyList(),
    ) : OperatorHubEvents {
        private val descending = fixtureAscending.sortedByDescending { it.sequence }

        override val state = MutableStateFlow(initialState)
        override val messages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 1)
        override val allMessages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 1)
        override val assignments = MutableSharedFlow<ConversationAssignedDto>(extraBufferCapacity = 1)
        override val messageDelivered = MutableSharedFlow<MessageDeliveredDto>(extraBufferCapacity = 1)
        override val teamMessages = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 16)
        override val teamMessageRemovals = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 16)

        val getTeamHistoryCalls: MutableList<Long?> = mutableListOf()
        val getTeamDeltaCalls: MutableList<Long> = mutableListOf()
        val sendCalls: MutableList<Pair<String, String>> = mutableListOf()
        val sendResults: MutableList<SendMessageResult> = mutableListOf()

        /** What the next [getTeamDelta] call returns - set by a test right before flipping [state]
         * back to [OperatorHubConnectionState.Connected], the same "arrange, then reconnect" shape a
         * real reconnect's own timing has. */
        var deltaFixture: List<TeamMessageDto> = emptyList()

        override suspend fun joinConversation(conversationId: String): HistoryPage = error("not used by this screen")

        override fun leaveConversation() = error("not used by this screen")

        override suspend fun loadOlderHistory(
            conversationId: String,
            beforeSequence: Long,
            pageSize: Int,
        ): HistoryPage = error("not used by this screen")

        override suspend fun getVisitorHistoryConversation(
            conversationId: String,
            historicalConversationId: String,
            beforeSequence: Long?,
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
        ): TeamHistoryPage {
            getTeamHistoryCalls.add(beforeSequence)
            val filtered = if (beforeSequence == null) descending else descending.filter { it.sequence < beforeSequence }
            val page = filtered.take(pageSize)
            val next = if (page.size == pageSize) page.last().sequence else null
            return TeamHistoryPage(page, next)
        }

        override suspend fun getTeamDelta(afterSequence: Long): TeamHistoryPage {
            getTeamDeltaCalls.add(afterSequence)
            return TeamHistoryPage(deltaFixture, null)
        }

        override suspend fun sendTeamMessage(
            body: String,
            clientMessageId: String,
        ): SendMessageResult {
            sendCalls.add(body to clientMessageId)
            return if (sendResults.isNotEmpty()) sendResults.removeAt(0) else SendMessageResult.Sent(0)
        }

        override suspend fun removeTeamMessage(teamMessageId: String) = error("not used by this screen")
    }
}
