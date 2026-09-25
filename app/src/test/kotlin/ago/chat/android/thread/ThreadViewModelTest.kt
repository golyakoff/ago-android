package ago.chat.android.thread

import ago.chat.android.core.domain.conversations.ComposerDraftStore
import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.network.realtime.ConversationAssignedDto
import ago.chat.android.core.network.realtime.HistoryPage
import ago.chat.android.core.network.realtime.MessageDeliveredDto
import ago.chat.android.core.network.realtime.MessageDto
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.core.network.realtime.SendMessageResult
import ago.chat.android.core.network.realtime.TeamHistoryPage
import ago.chat.android.core.network.realtime.TeamMessageDto
import ago.chat.android.devices.OpenConversationTracker
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    // -------------------------------------------------------------------- hub reconnect (26-62)

    @Test
    fun `a join that failed on a bad connection re-drives itself the moment the hub reconnects`() =
        runTest(dispatcher) {
            val hub = FakeOperatorHubEvents(fixtureAscending = messages(1..3))
            hub.state.value = OperatorHubConnectionState.Reconnecting
            hub.joinFailures.add(RuntimeException("connection dropped mid-join"))
            val viewModel = viewModelWith(hub)

            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals(1, hub.joinCalls.size)
            assertNotNull("the failed join left its error up, exactly as the manual Retry path does", viewModel.state.value.historyError)
            assertTrue(
                viewModel.state.value.messages
                    .isEmpty(),
            )

            // The signal comes back - the app bar's own dot, `ThreadUiState.hubConnectionState`, turns
            // green - with no tap from the operator.
            hub.state.value = OperatorHubConnectionState.Connected
            advanceUntilIdle()

            assertEquals(
                "the reconnect re-drove the join exactly once, through the same retryJoin path the button uses",
                2,
                hub.joinCalls.size,
            )
            assertNull(
                "clearing historyError is this class's own decision, not a side effect of a message arriving",
                viewModel.state.value.historyError,
            )
            assertEquals(
                listOf(1L, 2L, 3L),
                viewModel.state.value.messages
                    .map { it.sequence },
            )
        }

    @Test
    fun `a join failure that happens while the hub was already, and remains, connected is never auto-retried`() =
        runTest(dispatcher) {
            // `hub.state` starts, and stays, `Connected` (`FakeOperatorHubEvents`'s own default) - the
            // connection itself was never the problem, so the collector never observes a transition
            // into `Connected` at all, and this item's own Scope only ever re-drives a join on a
            // genuine reconnect, never on `historyError` alone. This is also the "rotation" hazard the
            // item warns about, turned into a direct assertion: a collector that mistook the very first
            // replay of an already-`Connected` value for a transition would auto-retry here too, since
            // nothing in this test ever proves the connection actually dropped.
            val hub = FakeOperatorHubEvents(fixtureAscending = messages(1..3))
            hub.joinFailures.add(RuntimeException("server error, unrelated to the socket"))
            val viewModel = viewModelWith(hub)

            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals(1, hub.joinCalls.size)
            assertNotNull(viewModel.state.value.historyError)

            // Give every stray coroutine a chance to run - nothing here should call joinConversation
            // again on its own.
            advanceUntilIdle()
            assertEquals("no transition happened, so nothing re-drives the join", 1, hub.joinCalls.size)

            viewModel.retryJoin()
            advanceUntilIdle()
            assertEquals("the manual button is still the only other entry point", 2, hub.joinCalls.size)
            assertNull(viewModel.state.value.historyError)
        }

    @Test
    fun `rotating the device after a reconnect-driven recovery issues no extra join`() =
        runTest(dispatcher) {
            val hub = FakeOperatorHubEvents(fixtureAscending = messages(1..3))
            hub.state.value = OperatorHubConnectionState.Reconnecting
            hub.joinFailures.add(RuntimeException("connection dropped mid-join"))
            val viewModel = viewModelWith(hub)

            viewModel.open("c1")
            advanceUntilIdle()
            hub.state.value = OperatorHubConnectionState.Connected
            advanceUntilIdle()
            assertEquals(2, hub.joinCalls.size)

            // `ThreadRoute`'s own `LaunchedEffect(conversationId) { viewModel.open(conversationId) }`
            // re-invokes `open` with the identical id after a plain device rotation recreates the
            // `Activity`'s Compose tree - the `ViewModel` itself, and the `previous` connection-state
            // tracker inside its one-and-only `init` collector, both survive untouched
            // (`hiltViewModel()` does not recreate this class for a rotation). `open`'s own same-id
            // guard makes the call below a no-op on its own; this proves the reconnect machinery this
            // item adds does not add a second reason for it to stop being one.
            viewModel.open("c1")
            advanceUntilIdle()

            assertEquals("rotating a healthy, already-recovered thread must never re-join it", 2, hub.joinCalls.size)
        }

    @Test
    fun `the connection dot and the join error never both show at once`() =
        runTest(dispatcher) {
            val hub = FakeOperatorHubEvents(fixtureAscending = messages(1..3))
            hub.state.value = OperatorHubConnectionState.Reconnecting
            hub.joinFailures.add(RuntimeException("connection dropped mid-join"))
            val viewModel = viewModelWith(hub)

            val observedStates = mutableListOf<ThreadUiState>()
            val collector = launch { viewModel.state.collect { observedStates.add(it) } }

            viewModel.open("c1")
            advanceUntilIdle()
            hub.state.value = OperatorHubConnectionState.Connected
            advanceUntilIdle()
            collector.cancel()

            assertTrue(
                "no emission ever claims both 'the hub is connected' and 'this thread failed to load' at once",
                observedStates.none {
                    it.hubConnectionState == OperatorHubConnectionState.Connected && it.historyError != null
                },
            )
        }

    @Test
    fun `a load-older failure is never auto-retried by a reconnect - only the initial join is`() =
        runTest(dispatcher) {
            // Same `historyError` field, a different situation (`docs/backlog/26-62-*.md`'s own Out of
            // scope): the join itself already succeeded, so `messages` is non-empty by the time
            // `loadOlder` can even be called, and that is exactly the signal this class uses to leave a
            // "load older" failure to its own retry banner.
            val hub = FakeOperatorHubEvents(fixtureAscending = messages(1..151))
            val viewModel = viewModelWith(hub)
            viewModel.open("c1")
            advanceUntilIdle()

            hub.loadOlderFailures.add(RuntimeException("boom"))
            viewModel.loadOlder()
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.historyError)
            assertTrue(
                viewModel.state.value.messages
                    .isNotEmpty(),
            )

            hub.state.value = OperatorHubConnectionState.Reconnecting
            advanceUntilIdle()
            hub.state.value = OperatorHubConnectionState.Connected
            advanceUntilIdle()

            assertEquals(
                "the reconnect must not call joinConversation a second time for a load-older failure",
                1,
                hub.joinCalls.size,
            )
            assertNotNull(
                "the load-older error is untouched - it keeps its own retry banner, per this item's own Out of scope",
                viewModel.state.value.historyError,
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

    // ------------------------------------------------------------------------------ delivery tick

    @Test
    fun `a live MessageDelivered push sets deliveredAt on the message already on screen`() =
        runTest(dispatcher) {
            val sent = MessageDto(id = "m1", sequence = 1, conversationId = "c1", authorKind = "Operator", body = "Добрый день")
            val hub = FakeOperatorHubEvents(fixtureAscending = listOf(sent))
            val viewModel = viewModelWith(hub)
            viewModel.open("c1")
            advanceUntilIdle()

            assertNull(
                viewModel.state.value.messages
                    .single()
                    .deliveredAt,
            )

            hub.messageDelivered.tryEmit(MessageDeliveredDto(conversationId = "c1", messageId = "m1", deliveredAt = "2026-09-22T09:41:00Z"))
            advanceUntilIdle()

            assertEquals(
                "2026-09-22T09:41:00Z",
                viewModel.state.value.messages
                    .single()
                    .deliveredAt,
            )
        }

    @Test
    fun `a repeated MessageDelivered for the same message changes nothing`() =
        runTest(dispatcher) {
            val sent = MessageDto(id = "m1", sequence = 1, conversationId = "c1", authorKind = "Operator", body = "Добрый день")
            val hub = FakeOperatorHubEvents(fixtureAscending = listOf(sent))
            val viewModel = viewModelWith(hub)
            viewModel.open("c1")
            advanceUntilIdle()

            hub.messageDelivered.tryEmit(MessageDeliveredDto(conversationId = "c1", messageId = "m1", deliveredAt = "2026-09-22T09:41:00Z"))
            advanceUntilIdle()
            // A second, later delivery notice for the same message - at-least-once redelivery, or a
            // duplicate fan-out - must not overwrite the first timestamp with a different one.
            hub.messageDelivered.tryEmit(MessageDeliveredDto(conversationId = "c1", messageId = "m1", deliveredAt = "2026-09-22T09:55:00Z"))
            advanceUntilIdle()

            assertEquals(
                "the first delivery notice wins - a repeat changes nothing",
                "2026-09-22T09:41:00Z",
                viewModel.state.value.messages
                    .single()
                    .deliveredAt,
            )
            assertEquals(1, viewModel.state.value.messages.size)
        }

    @Test
    fun `a MessageDelivered for a different conversation than the one open is ignored`() =
        runTest(dispatcher) {
            val sent = MessageDto(id = "m1", sequence = 1, conversationId = "c1", authorKind = "Operator", body = "Добрый день")
            val hub = FakeOperatorHubEvents(fixtureAscending = listOf(sent))
            val viewModel = viewModelWith(hub)
            viewModel.open("c1")
            advanceUntilIdle()

            hub.messageDelivered.tryEmit(
                MessageDeliveredDto(conversationId = "other", messageId = "m1", deliveredAt = "2026-09-22T09:41:00Z"),
            )
            advanceUntilIdle()

            assertNull(
                viewModel.state.value.messages
                    .single()
                    .deliveredAt,
            )
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

    // -------------------------------------------------------------------------------- mark-read (26-80)

    @Test
    fun `markReadUpTo debounces - nothing is sent before the window elapses`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi()
            val viewModel = viewModelWith(FakeOperatorHubEvents(fixtureAscending = messages(1..3)), conversationsApi = api)
            viewModel.open("c1")
            advanceUntilIdle()

            viewModel.markReadUpTo(3)
            advanceTimeBy(100)
            assertEquals("still inside the debounce window", emptyList<Pair<String, Int>>(), api.markReadCalls)

            advanceTimeBy(450)
            assertEquals(listOf("c1" to 3), api.markReadCalls)
        }

    @Test
    fun `rapid successive reports before the debounce fires collapse into one call, for the latest sequence`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi()
            val viewModel = viewModelWith(FakeOperatorHubEvents(fixtureAscending = messages(1..5)), conversationsApi = api)
            viewModel.open("c1")
            advanceUntilIdle()

            // The shape three quick scroll/arrival ticks take before the 500ms window closes - each
            // one cancels the previous still-pending timer (`ThreadViewModel.markReadUpTo`'s own doc
            // comment), the same `clearTimeout` cleanup `ago-console`'s own effect relies on.
            viewModel.markReadUpTo(3)
            viewModel.markReadUpTo(4)
            viewModel.markReadUpTo(5)
            advanceUntilIdle()

            assertEquals(
                "only the latest sequence is ever sent - the earlier timers were cancelled, not queued",
                listOf("c1" to 5),
                api.markReadCalls,
            )
        }

    @Test
    fun `a sequence already confirmed sent is not re-sent`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi()
            val viewModel = viewModelWith(FakeOperatorHubEvents(fixtureAscending = messages(1..5)), conversationsApi = api)
            viewModel.open("c1")
            advanceUntilIdle()

            viewModel.markReadUpTo(3)
            advanceUntilIdle()
            assertEquals(listOf("c1" to 3), api.markReadCalls)

            // A later report at or below the already-confirmed watermark - a recomposition that
            // reports the same visible item again, or a momentary scroll back over already-read
            // history - must not spend a second network call on a position the server already knows.
            viewModel.markReadUpTo(3)
            viewModel.markReadUpTo(2)
            advanceUntilIdle()

            assertEquals(
                "no new call for a sequence already confirmed sent",
                listOf("c1" to 3),
                api.markReadCalls,
            )
        }

    @Test
    fun `a higher sequence arriving after one was already confirmed sent is still forwarded - the watermark keeps moving`() =
        runTest(dispatcher) {
            // The client-side half of `Conversation.MarkReadByOperator`'s own doc comment: a visitor
            // message landing in the same instant as a read must still be counted once it is itself
            // read, which on this side of the wire means confirming sequence 3 must never stop this
            // instance from later confirming 5 once the operator has genuinely seen that message too -
            // proving the client's own watermark, not re-deriving the server's clamp-and-subtract logic.
            val api = FakeConversationsApi()
            val viewModel = viewModelWith(FakeOperatorHubEvents(fixtureAscending = messages(1..5)), conversationsApi = api)
            viewModel.open("c1")
            advanceUntilIdle()

            viewModel.markReadUpTo(3)
            advanceUntilIdle()
            assertEquals(listOf("c1" to 3), api.markReadCalls)

            // A new message (sequence 5) arrives and is scrolled into view - the identical live shape
            // `MessageList`'s own `onNewestVisibleSequenceChanged` reports for a real arrival.
            viewModel.markReadUpTo(5)
            advanceUntilIdle()

            assertEquals(
                "the later, higher watermark is a real second call, never swallowed by the first send",
                listOf("c1" to 3, "c1" to 5),
                api.markReadCalls,
            )
        }

    @Test
    fun `opening a different conversation resets the watermark - a stale high sequence never suppresses the new one's first read`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi()
            val viewModel = viewModelWith(FakeOperatorHubEvents(fixtureAscending = messages(1..10)), conversationsApi = api)
            viewModel.open("c1")
            advanceUntilIdle()
            viewModel.markReadUpTo(10)
            advanceUntilIdle()
            assertEquals(listOf("c1" to 10), api.markReadCalls)

            viewModel.close()
            viewModel.open("c2")
            advanceUntilIdle()
            viewModel.markReadUpTo(1)
            advanceUntilIdle()

            assertEquals(
                "c2's own low sequence must not be swallowed by c1's leftover watermark",
                listOf("c1" to 10, "c2" to 1),
                api.markReadCalls,
            )
        }

    @Test
    fun `closing the thread abandons a still-pending mark-read rather than sending it early`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi()
            val viewModel = viewModelWith(FakeOperatorHubEvents(fixtureAscending = messages(1..3)), conversationsApi = api)
            viewModel.open("c1")
            advanceUntilIdle()

            viewModel.markReadUpTo(3)
            viewModel.close()
            advanceUntilIdle()

            assertEquals(
                "the debounce timer never got to fire - closing cancelled it, matching the doc comment's own reasoning",
                emptyList<Pair<String, Int>>(),
                api.markReadCalls,
            )
        }

    @Test
    fun `markReadUpTo before anything is open does nothing`() =
        runTest(dispatcher) {
            val api = FakeConversationsApi()
            val viewModel = viewModelWith(FakeOperatorHubEvents(), conversationsApi = api)

            viewModel.markReadUpTo(1)
            advanceUntilIdle()

            assertEquals(emptyList<Pair<String, Int>>(), api.markReadCalls)
        }

    @Test
    fun `open records the conversation as open on the tracker - decideAlert's own input`() =
        runTest(dispatcher) {
            val tracker = FakeOpenConversationTracker()
            val viewModel = viewModelWith(FakeOperatorHubEvents(), openConversationTracker = tracker)

            viewModel.open("conv-1")
            advanceUntilIdle()

            assertEquals(listOf("conv-1"), tracker.openedCalls)
            assertEquals("conv-1", tracker.currentConversationId)
        }

    @Test
    fun `close clears the conversation from the tracker`() =
        runTest(dispatcher) {
            val tracker = FakeOpenConversationTracker()
            val viewModel = viewModelWith(FakeOperatorHubEvents(), openConversationTracker = tracker)
            viewModel.open("conv-1")
            advanceUntilIdle()

            viewModel.close()

            assertEquals(listOf("conv-1"), tracker.closedCalls)
            assertNull(tracker.currentConversationId)
        }

    @Test
    fun `opening a second conversation closes the first on the tracker before opening the second`() =
        runTest(dispatcher) {
            val tracker = FakeOpenConversationTracker()
            val viewModel = viewModelWith(FakeOperatorHubEvents(), openConversationTracker = tracker)
            viewModel.open("conv-1")
            advanceUntilIdle()

            viewModel.open("conv-2")
            advanceUntilIdle()

            assertEquals(listOf("conv-1"), tracker.closedCalls)
            assertEquals(listOf("conv-1", "conv-2"), tracker.openedCalls)
            assertEquals("conv-2", tracker.currentConversationId)
        }

    @Test
    fun `onCleared is a backstop that also clears the tracker`() =
        runTest(dispatcher) {
            val tracker = FakeOpenConversationTracker()
            val viewModel = viewModelWith(FakeOperatorHubEvents(), openConversationTracker = tracker)
            viewModel.open("conv-1")
            advanceUntilIdle()

            // `onCleared()` is `protected` - a real `ViewModelStore` is the ordinary, public way to
            // trigger it from outside the class, the same mechanism Android itself uses when a
            // `ViewModelStoreOwner` (an `Activity`, a `NavBackStackEntry`) is actually destroyed.
            ViewModelStore().apply {
                put("thread", viewModel)
                clear()
            }

            assertEquals(listOf("conv-1"), tracker.closedCalls)
        }

    // ------------------------------------------------------------------------------------- fakes

    private fun viewModelWith(
        hub: OperatorHubEvents,
        draftStore: ComposerDraftStore = FakeComposerDraftStore(),
        conversationsApi: ConversationsApi = FakeConversationsApi(),
        openConversationTracker: OpenConversationTracker = FakeOpenConversationTracker(),
    ): ThreadViewModel =
        ThreadViewModel(
            hubEvents = hub,
            draftStore = draftStore,
            conversationsApi = conversationsApi,
            ioDispatcher = dispatcher,
            openConversationTracker = openConversationTracker,
        )

    /** `26-80`: records every call rather than branching on outcome - [ThreadViewModel.markReadUpTo]'s
     * own doc comment states why the `Boolean` [ConversationsApi.markRead] returns is never read: this
     * app never distinguishes a server refusal from a transport failure for this one call, so a fake
     * that always answers `true` is a faithful stand-in for "the real adapter never throws" without
     * needing a failure-injection knob nothing here would read. */
    private class FakeConversationsApi : ConversationsApi {
        val markReadCalls: MutableList<Pair<String, Int>> = mutableListOf()

        override suspend fun fetchQueue() = error("not used by this screen")

        override suspend fun claim(conversationId: String) = error("not used by this screen")

        // `26-90`: the site-wide list and its erase action belong to the conversation list's own «Все»
        // tab - the same "not used by this screen" shape every other unused member of this fake takes.
        override suspend fun fetchAllConversations(
            beforeId: String?,
            pageSize: Int,
            states: List<String>,
        ) = error("not used by this screen")

        override suspend fun requestErasure(conversationId: String) = error("not used by this screen")

        override suspend fun markRead(
            conversationId: String,
            upToSequence: Int,
        ): Boolean {
            markReadCalls.add(conversationId to upToSequence)
            return true
        }
    }

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

        override val state: MutableStateFlow<OperatorHubConnectionState> = MutableStateFlow(OperatorHubConnectionState.Connected)
        override val messages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 16)
        override val allMessages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 16)
        override val assignments = MutableSharedFlow<ConversationAssignedDto>(extraBufferCapacity = 16)
        override val messageDelivered = MutableSharedFlow<MessageDeliveredDto>(extraBufferCapacity = 16)
        override val teamMessages = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 16)
        override val teamMessageRemovals = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 16)

        val loadOlderCursors: MutableList<Long> = mutableListOf()
        val sendCalls: MutableList<Pair<String, String>> = mutableListOf()
        val sendResults: MutableList<SendMessageResult> = mutableListOf()

        /** Every `joinConversation` call, in order - `26-62`'s own tests assert on the exact count to
         * prove a reconnect re-drives it exactly once, never zero and never twice. */
        val joinCalls: MutableList<String> = mutableListOf()

        /** `26-62`: a queue of failures for `joinConversation` to throw, one per call, before falling
         * back to its ordinary successful page - the same "queue of outcomes" shape [sendResults]
         * already uses for [sendMessage], so a test can make the *next* join fail without touching every
         * other call site. */
        val joinFailures: MutableList<Exception> = mutableListOf()

        /** `26-62`: the identical queue shape as [joinFailures], for `loadOlderHistory` - proves a
         * "load older" failure is left alone by the reconnect logic, which only ever re-drives the
         * *initial* join. */
        val loadOlderFailures: MutableList<Exception> = mutableListOf()

        override suspend fun joinConversation(conversationId: String): HistoryPage {
            joinCalls.add(conversationId)
            if (joinFailures.isNotEmpty()) throw joinFailures.removeAt(0)
            return pageBefore(null, JOIN_PAGE_SIZE)
        }

        override fun leaveConversation() = Unit

        override suspend fun loadOlderHistory(
            conversationId: String,
            beforeSequence: Long,
            pageSize: Int,
        ): HistoryPage {
            loadOlderCursors.add(beforeSequence)
            if (loadOlderFailures.isNotEmpty()) throw loadOlderFailures.removeAt(0)
            return pageBefore(beforeSequence, pageSize)
        }

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
        ): SendMessageResult {
            sendCalls.add(body to clientMessageId)
            return if (sendResults.isNotEmpty()) sendResults.removeAt(0) else SendMessageResult.Sent(0)
        }

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

    /** `26-18`: records every call rather than only the current answer - this class's own new tests
     * assert on the exact sequence [ThreadViewModel.open]/[ThreadViewModel.close] produce, the same
     * "call order, not merely presence" proof `DeviceRegistrationCoordinatorTest`'s own revoke-then-
     * delete case already establishes for a different class. */
    private class FakeOpenConversationTracker : OpenConversationTracker {
        val openedCalls = mutableListOf<String>()
        val closedCalls = mutableListOf<String>()
        override var currentConversationId: String? = null
            private set

        override fun conversationOpened(conversationId: String) {
            openedCalls += conversationId
            currentConversationId = conversationId
        }

        override fun conversationClosed(conversationId: String) {
            closedCalls += conversationId
            if (currentConversationId == conversationId) currentConversationId = null
        }
    }
}
