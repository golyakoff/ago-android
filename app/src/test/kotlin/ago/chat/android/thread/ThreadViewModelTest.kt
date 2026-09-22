package ago.chat.android.thread

import ago.chat.android.core.domain.conversations.ComposerDraftStore
import ago.chat.android.core.network.realtime.ConversationAssignedDto
import ago.chat.android.core.network.realtime.HistoryPage
import ago.chat.android.core.network.realtime.MessageDto
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.core.network.realtime.SendMessageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * `26-15`'s own Done-when, proven with no server and no real session (this item's own hand-off
 * notes): the keyset boundary, both halves of dedup, the composer draft's own debounce/flush, and the
 * retry-id rule `SendMessageResult`'s own doc comment states. Same `StandardTestDispatcher` shape
 * `ConversationListViewModelTest` already establishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------------------- open

    @Test
    fun `opening joins and renders the newest page, oldest first`() =
        runTest(dispatcher) {
            val hub = FakeOperatorHubEvents(fixtureAscending = messages(1..3))
            val viewModel = viewModelWith(hub)

            viewModel.open("c1")
            advanceUntilIdle()

            assertFalse(viewModel.state.value.joining)
            assertFalse("only 3 messages exist - nothing more to page into", viewModel.state.value.canLoadOlder)
            assertEquals(
                listOf(1L, 2L, 3L),
                viewModel.state.value.messages
                    .map { it.sequence },
            )
        }

    // ---------------------------------------------------------------------------- keyset boundary

    @Test
    fun `the keyset boundary - paging older to the end never duplicates or drops a message`() =
        runTest(dispatcher) {
            // 151 messages: a join page (50) plus two full loadOlder pages (50, 50) plus a final
            // partial one (1) - the exact shape ago-chat's own `sequence < @BeforeSequence` produces.
            val hub = FakeOperatorHubEvents(fixtureAscending = messages(1..151))
            val viewModel = viewModelWith(hub)

            viewModel.open("c1")
            advanceUntilIdle()
            assertTrue(viewModel.state.value.canLoadOlder)
            assertEquals(
                (102L..151L).toList(),
                viewModel.state.value.messages
                    .map { it.sequence },
            )

            viewModel.loadOlder()
            advanceUntilIdle()
            viewModel.loadOlder()
            advanceUntilIdle()
            viewModel.loadOlder()
            advanceUntilIdle()

            val sequences =
                viewModel.state.value.messages
                    .map { it.sequence }
            assertEquals("every message from 1 to 151, no gap and no duplicate", (1L..151L).toList(), sequences)
            assertFalse("the cursor is genuinely exhausted", viewModel.state.value.canLoadOlder)

            // The three cursors this walk actually used - each one an exact boundary value the SQL's
            // own `sequence < @BeforeSequence` excludes from the *next* page while it was still the
            // smallest member of the *previous* one. Proven here rather than only inferred from the
            // final list: a client that asked for the wrong cursor could still, by coincidence, produce
            // a gap-free final list against a naive fake - this fixture is built to make that
            // impossible (`FakeOperatorHubEvents.pageBefore`'s own strict `<`), but asserting the
            // cursors directly is what pins the client's own request shape, not just the outcome.
            assertEquals(listOf(102L, 52L, 2L), hub.loadOlderCursors)

            // The boundary message itself - the smallest sequence of one page and the exact cursor for
            // the next - appears exactly once, never on both sides of the cut.
            assertEquals(
                1,
                viewModel.state.value.messages
                    .count { it.sequence == 102L },
            )
            assertEquals(
                1,
                viewModel.state.value.messages
                    .count { it.sequence == 52L },
            )
        }

    @Test
    fun `an exact multiple leaves one trailing empty page, and that costs nothing`() =
        runTest(dispatcher) {
            // 100 = one full join page (50) plus one full loadOlder page (50) exactly - the cursor after
            // the second page still looks like "maybe more" (a full page), so a third call is needed
            // purely to discover there is nothing left. `ConversationReadStore`'s own comment on this
            // file predicts exactly this shape.
            val hub = FakeOperatorHubEvents(fixtureAscending = messages(1..100))
            val viewModel = viewModelWith(hub)

            viewModel.open("c1")
            advanceUntilIdle()
            assertTrue(viewModel.state.value.canLoadOlder)

            viewModel.loadOlder()
            advanceUntilIdle()
            assertTrue("a full page still looks like there may be more", viewModel.state.value.canLoadOlder)

            viewModel.loadOlder()
            advanceUntilIdle()

            assertFalse(viewModel.state.value.canLoadOlder)
            assertEquals(
                (1L..100L).toList(),
                viewModel.state.value.messages
                    .map { it.sequence },
            )
            assertEquals(listOf(51L, 1L), hub.loadOlderCursors)
        }

    @Test
    fun `loadOlder is a no-op once the cursor is already exhausted`() =
        runTest(dispatcher) {
            val hub = FakeOperatorHubEvents(fixtureAscending = messages(1..3))
            val viewModel = viewModelWith(hub)
            viewModel.open("c1")
            advanceUntilIdle()

            viewModel.loadOlder()
            advanceUntilIdle()

            assertEquals("the cursor was already exhausted at open() - nothing to load", 0, hub.loadOlderCursors.size)
        }

    @Test
    fun `loadOlder is a no-op while a page is already loading - no second concurrent fetch`() =
        runTest(dispatcher) {
            val hub = FakeOperatorHubEvents(fixtureAscending = messages(1..151))
            val viewModel = viewModelWith(hub)
            viewModel.open("c1")
            advanceUntilIdle()

            viewModel.loadOlder()
            viewModel.loadOlder()
            advanceUntilIdle()

            assertEquals("the second call while the first was still in flight did nothing", listOf(102L), hub.loadOlderCursors)
        }

    // ------------------------------------------------------------------------------ inbound dedup

    @Test
    fun `a redelivered inbound message renders once`() =
        runTest(dispatcher) {
            val hub = FakeOperatorHubEvents(fixtureAscending = messages(1..2))
            val viewModel = viewModelWith(hub)
            viewModel.open("c1")
            advanceUntilIdle()

            val redelivered = MessageDto(id = "dup", sequence = 99, conversationId = "c1", authorKind = "Visitor", body = "привет")
            hub.messages.tryEmit(redelivered)
            hub.messages.tryEmit(redelivered)
            advanceUntilIdle()

            assertEquals(
                1,
                viewModel.state.value.messages
                    .count { it.id == "dup" },
            )
            assertEquals(3, viewModel.state.value.messages.size)
        }

    // ---------------------------------------------------------------------------- outbound retry

    @Test
    fun `a send retried after an ambiguous outcome reuses the exact same clientMessageId`() =
        runTest(dispatcher) {
            val hub = FakeOperatorHubEvents()
            val draftStore = FakeComposerDraftStore()
            val viewModel = viewModelWith(hub, draftStore)
            viewModel.open("c1")
            advanceUntilIdle()

            hub.sendResults.add(SendMessageResult.OutcomeUnknown(RuntimeException("connection dropped mid-invoke")))
            viewModel.onDraftChanged("Здравствуйте!")
            viewModel.sendClicked()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.pendingRetry)
            assertEquals(1, hub.sendCalls.size)
            val firstId = hub.sendCalls.single().second

            viewModel.retrySend()
            advanceUntilIdle()

            assertEquals("exactly one retry, no extra sends", 2, hub.sendCalls.size)
            assertEquals(
                "the retry reused the ambiguous attempt's own id - the id the server can dedup on",
                firstId,
                hub.sendCalls[1].second,
            )
            assertEquals("Здравствуйте!", hub.sendCalls[1].first)
            assertFalse(viewModel.state.value.pendingRetry)
        }

    @Test
    fun `a send retried after not-connected uses a fresh id, never the one that never sent`() =
        runTest(dispatcher) {
            val hub = FakeOperatorHubEvents()
            val viewModel = viewModelWith(hub)
            viewModel.open("c1")
            advanceUntilIdle()

            hub.sendResults.add(SendMessageResult.NotConnected)
            viewModel.onDraftChanged("Привет")
            viewModel.sendClicked()
            advanceUntilIdle()
            val firstId = hub.sendCalls.single().second

            viewModel.retrySend()
            advanceUntilIdle()

            assertEquals(2, hub.sendCalls.size)
            assertTrue("nothing was sent the first time, so the retry must use a fresh id", hub.sendCalls[1].second != firstId)
        }

    @Test
    fun `a definitive refusal is shown once and is never retried automatically`() =
        runTest(dispatcher) {
            val hub = FakeOperatorHubEvents()
            val viewModel = viewModelWith(hub)
            viewModel.open("c1")
            advanceUntilIdle()

            hub.sendResults.add(SendMessageResult.Refused("Conversation is closed."))
            viewModel.onDraftChanged("hi")
            viewModel.sendClicked()
            advanceUntilIdle()

            assertEquals("Conversation is closed.", viewModel.state.value.sendRefusedMessage)
            assertFalse("a definite refusal has no pending retry - retrying would fail identically", viewModel.state.value.pendingRetry)

            viewModel.retrySend()
            advanceUntilIdle()
            assertEquals("retrySend is a no-op with nothing pending", 1, hub.sendCalls.size)
        }

    @Test
    fun `editing the draft after a failed send abandons the pending retry`() =
        runTest(dispatcher) {
            val hub = FakeOperatorHubEvents()
            val viewModel = viewModelWith(hub)
            viewModel.open("c1")
            advanceUntilIdle()
            hub.sendResults.add(SendMessageResult.OutcomeUnknown(RuntimeException("boom")))
            viewModel.onDraftChanged("оригинал")
            viewModel.sendClicked()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.pendingRetry)

            viewModel.onDraftChanged("передумал")

            assertFalse(viewModel.state.value.pendingRetry)
            viewModel.retrySend()
            advanceUntilIdle()
            assertEquals("retrySend is a no-op once the draft moved on", 1, hub.sendCalls.size)
        }

    @Test
    fun `a double tap on send never sends the same draft twice`() =
        runTest(dispatcher) {
            val hub = FakeOperatorHubEvents()
            val viewModel = viewModelWith(hub)
            viewModel.open("c1")
            advanceUntilIdle()

            viewModel.onDraftChanged("hi")
            viewModel.sendClicked()
            viewModel.sendClicked()
            advanceUntilIdle()

            assertEquals(1, hub.sendCalls.size)
        }

    // ---------------------------------------------------------------------------- composer draft

    @Test
    fun `opening loads a previously saved draft`() =
        runTest(dispatcher) {
            val draftStore = FakeComposerDraftStore().apply { drafts["c1"] = "черновик с прошлого раза" }
            val viewModel = viewModelWith(FakeOperatorHubEvents(), draftStore)

            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals("черновик с прошлого раза", viewModel.state.value.draft)
        }

    @Test
    fun `typing debounces the write - nothing is saved before the debounce window elapses`() =
        runTest(dispatcher) {
            val draftStore = FakeComposerDraftStore()
            val viewModel = viewModelWith(FakeOperatorHubEvents(), draftStore)
            viewModel.open("c1")
            advanceUntilIdle()

            viewModel.onDraftChanged("п")
            viewModel.onDraftChanged("привет")
            advanceTimeBy(100)

            assertNull("still inside the debounce window", draftStore.drafts["c1"])

            advanceTimeBy(400)
            assertEquals("привет", draftStore.drafts["c1"])
        }

    @Test
    fun `flushDraft writes immediately without waiting for the debounce`() =
        runTest(dispatcher) {
            val draftStore = FakeComposerDraftStore()
            val viewModel = viewModelWith(FakeOperatorHubEvents(), draftStore)
            viewModel.open("c1")
            advanceUntilIdle()

            viewModel.onDraftChanged("уходим со экрана прямо сейчас")
            viewModel.flushDraft()
            advanceUntilIdle()

            assertEquals("уходим со экрана прямо сейчас", draftStore.drafts["c1"])
        }

    @Test
    fun `sending clears the persisted draft, not just the on-screen one`() =
        runTest(dispatcher) {
            val draftStore = FakeComposerDraftStore()
            val viewModel = viewModelWith(FakeOperatorHubEvents(), draftStore)
            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.onDraftChanged("готовый ответ")
            viewModel.flushDraft()
            advanceUntilIdle()
            assertEquals("готовый ответ", draftStore.drafts["c1"])

            viewModel.sendClicked()
            advanceUntilIdle()

            assertNull("a sent draft leaves no saved row behind", draftStore.drafts["c1"])
            assertEquals("", viewModel.state.value.draft)
        }

    // ------------------------------------------------------------------------------------- fakes

    private fun viewModelWith(
        hub: OperatorHubEvents,
        draftStore: ComposerDraftStore = FakeComposerDraftStore(),
    ): ThreadViewModel =
        ThreadViewModel(
            hubEvents = hub,
            draftStore = draftStore,
            ioDispatcher = dispatcher,
        )

    private fun messages(range: IntRange): List<MessageDto> =
        range.map { seq ->
            MessageDto(
                id = "m$seq",
                sequence = seq.toLong(),
                conversationId = "c1",
                authorKind = "Visitor",
                body = "message $seq",
                createdAt = "2026-09-22T09:00:00Z",
            )
        }

    /**
     * Mirrors `ago-chat`'s own `ConversationReadStore.Sql` exactly - `sequence < @BeforeSequence`,
     * newest first, `nextBeforeSequence` set only when the page came back full - so a test built
     * against this fake is a test of [ThreadViewModel]'s own paging logic against a faithful
     * simulation of the real contract, not a tautology against a fake that already agrees with
     * whatever the client happens to ask for.
     */
    private class FakeOperatorHubEvents(
        fixtureAscending: List<MessageDto> = emptyList(),
    ) : OperatorHubEvents {
        private val descending = fixtureAscending.sortedByDescending { it.sequence }

        override val state = MutableStateFlow(OperatorHubConnectionState.Connected)
        override val messages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 16)
        override val allMessages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 16)
        override val assignments = MutableSharedFlow<ConversationAssignedDto>(extraBufferCapacity = 16)

        val loadOlderCursors: MutableList<Long> = mutableListOf()
        val sendCalls: MutableList<Pair<String, String>> = mutableListOf()
        val sendResults: MutableList<SendMessageResult> = mutableListOf()

        override suspend fun joinConversation(conversationId: String): HistoryPage = pageBefore(null, JOIN_PAGE_SIZE)

        override fun leaveConversation() = Unit

        override suspend fun loadOlderHistory(
            conversationId: String,
            beforeSequence: Long,
            pageSize: Int,
        ): HistoryPage {
            loadOlderCursors.add(beforeSequence)
            return pageBefore(beforeSequence, pageSize)
        }

        override suspend fun sendMessage(
            conversationId: String,
            body: String,
            clientMessageId: String,
            attachmentId: String?,
        ): SendMessageResult {
            sendCalls.add(body to clientMessageId)
            return if (sendResults.isNotEmpty()) sendResults.removeAt(0) else SendMessageResult.Sent(0)
        }

        override suspend fun reconnectToActiveSite() = error("not used by this screen")

        private fun pageBefore(
            beforeSequence: Long?,
            pageSize: Int,
        ): HistoryPage {
            val filtered = if (beforeSequence == null) descending else descending.filter { it.sequence < beforeSequence }
            val page = filtered.take(pageSize)
            val next = if (page.size == pageSize) page.last().sequence else null
            return HistoryPage(page, next)
        }

        private companion object {
            const val JOIN_PAGE_SIZE = HISTORY_PAGE_SIZE
        }
    }

    private class FakeComposerDraftStore : ComposerDraftStore {
        val drafts: MutableMap<String, String> = mutableMapOf()

        override suspend fun read(conversationId: String): String? = drafts[conversationId]

        override suspend fun write(
            conversationId: String,
            draft: String,
        ) {
            drafts[conversationId] = draft
        }

        override suspend fun clear(conversationId: String) {
            drafts.remove(conversationId)
        }
    }
}
