package ago.chat.android.thread

import ago.chat.android.core.domain.conversations.ComposerDraftStore
import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.network.realtime.MessageDeliveredDto
import ago.chat.android.core.network.realtime.MessageDto
import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.core.network.realtime.SendMessageResult
import ago.chat.android.core.network.realtime.newClientMessageId
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-15`: the thread screen's whole state machine - history paging, live receive, send-with-retry,
 * and the composer draft's own survival. Deliberately holds no [ago.chat.android.core.domain.conversations.ConversationSummary]
 * of its own (visitor identity, `hasAttachmentUploadGrant`) - `docs/architecture/realtime.md`'s
 * `ConversationSummaryDto` is also what feeds `ago-console`'s `ConversationPage` (`useWorkspace().conversation`),
 * and the Android equivalent is the row [ago.chat.android.conversations.ConversationListViewModel]
 * already fetched, handed down as a plain parameter by [ThreadRoute] rather than re-fetched here.
 *
 * ## The one message store, and why it is keyed by id rather than appended
 *
 * [byId] is a `LinkedHashMap<String, MessageDto>`, and every source that can hand this class a message
 * - the initial join page, an older page, or a single live push - goes through the same
 * [mergeAndRender]: insert-or-replace by [MessageDto.id], then re-sort by [MessageDto.sequence] and
 * publish. That single function is what makes three of this item's own Done-when boxes true by
 * construction rather than by careful bookkeeping at each call site:
 *
 * - **A redelivered inbound message renders once** - a duplicate id from a resume, a fan-out echo, or
 *   an older page that happens to overlap a live push simply overwrites its own entry, never adding a
 *   second row. This is on top of, not instead of, [ago.chat.android.core.network.realtime.SeenHubIds]'s
 *   own dedup inside `OperatorHubConnection` - defence in depth, the same "the connection already
 *   deduplicates, and this screen does not have to trust that alone" posture worth having for the one
 *   screen this item's whole proof rests on.
 * - **History pages upward without duplicating or dropping a message at the keyset boundary** -
 *   `GetHistoryAsync`'s own `sequence < @BeforeSequence` (`ago-chat/ConversationReadStore.Sql`) is a
 *   strict, non-overlapping boundary already; merging by id rather than naively prepending a page also
 *   survives a client-side mistake (asking for the same cursor twice) without corrupting the list.
 * - **Never a timestamp order** - the map is re-sorted by `sequence` on every merge, never left in
 *   arrival order (`CLAUDE.md` rule 6).
 *
 * ## The composer draft
 *
 * [onDraftChanged] debounces its own write to [draftStore] ([DRAFT_WRITE_DEBOUNCE_MILLIS]) rather than
 * writing on every keystroke - a phone's SQLite is not free, and an operator typing a reply produces
 * far more keystrokes than the draft needs persisted copies. [flushDraft] exists because a debounce
 * window is exactly the gap a process death can land in: `ThreadRoute`'s own `ON_STOP` observer calls
 * it, and Android's own contract - `onStop` runs before a process is ever killed in the ordinary
 * lifecycle-driven case - is what makes that flush actually cover the "killed and restored" Done-when
 * box, the identical guarantee `SavedStateHandle`'s own `onSaveInstanceState` timing relies on for a
 * configuration change.
 */
@HiltViewModel
public class ThreadViewModel
    @Inject
    constructor(
        private val hubEvents: OperatorHubEvents,
        private val draftStore: ComposerDraftStore,
        private val conversationsApi: ConversationsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(ThreadUiState())
        public val state: StateFlow<ThreadUiState> = mutableState.asStateFlow()

        private val byId = LinkedHashMap<String, MessageDto>()
        private var nextBeforeSequence: Long? = null
        private var failedSend: FailedSend? = null
        private var draftWriteJob: Job? = null
        private var messagesJob: Job? = null
        private var deliveryJob: Job? = null

        /** `26-80`: this instance's own "already told the server this" watermark for the conversation
         * currently open - `ago-console`'s own `lastMarked` ref, restated. `null` until the first call,
         * and reset in [open] for every new conversation - never carried from one conversation to the
         * next, which would let a high sequence from a *previous* thread suppress the first real
         * mark-read of this one. */
        private var lastMarkedReadSequence: Long? = null
        private var markReadJob: Job? = null

        /** The conversation this instance is currently open on - `null` before the first [open].
         * `ago-console`'s own `joinedConversationId` ref, restated: the guard that stops a
         * `LaunchedEffect(conversationId)` re-running on an unrelated recomposition from re-joining a
         * conversation already open. */
        private var openConversationId: String? = null

        init {
            // Relayed for exactly the reason `ConversationListScreen`'s own `HubConnectionDebugRow`
            // exists - this screen is the one place a dropped/retried send is actually observed, so
            // seeing the connection flap while a `pendingRetry` banner is up is not a coincidence an
            // operator should have to guess at.
            viewModelScope.launch {
                hubEvents.state.collect { connectionState ->
                    mutableState.update { it.copy(hubConnectionState = connectionState) }
                }
            }
        }

        /**
         * Opens (or, called again with the identical id, does nothing to) one conversation. Resets
         * every piece of this class's own state before joining - a screen reused for a second, different
         * conversation must never show the first one's messages or draft even for a frame.
         */
        public fun open(conversationId: String) {
            if (openConversationId == conversationId) return
            if (openConversationId != null) hubEvents.leaveConversation()

            openConversationId = conversationId
            byId.clear()
            nextBeforeSequence = null
            failedSend = null
            draftWriteJob?.cancel()
            markReadJob?.cancel()
            markReadJob = null
            lastMarkedReadSequence = null
            mutableState.value = ThreadUiState(conversationId = conversationId, hubConnectionState = mutableState.value.hubConnectionState)

            messagesJob?.cancel()
            messagesJob =
                viewModelScope.launch {
                    hubEvents.messages.collect { message -> mergeAndRender(listOf(message)) }
                }

            // `26-42`: the live half of the second delivery tick - unscoped on the wire
            // ([OperatorHubEvents.messageDelivered]'s own doc comment), so [applyDelivery] is what
            // filters to the conversation actually open before touching [byId].
            deliveryJob?.cancel()
            deliveryJob =
                viewModelScope.launch {
                    hubEvents.messageDelivered.collect { delivered -> applyDelivery(delivered) }
                }

            viewModelScope.launch {
                val saved = withContext(ioDispatcher) { draftStore.read(conversationId) }
                if (saved != null && openConversationId == conversationId) {
                    mutableState.update { it.copy(draft = saved) }
                }
            }

            launchJoin(conversationId)
        }

        /**
         * Retries the initial `JoinConversationAsync` call after it failed - distinct from calling
         * [open] again, which would be a no-op ([open]'s own same-id guard treats "already the
         * conversation this instance is open on" as done, joined or not). A no-op if nothing is open,
         * or if the join already succeeded (there is nothing to retry).
         */
        public fun retryJoin() {
            val conversationId = openConversationId ?: return
            if (mutableState.value.historyError == null) return
            mutableState.update { it.copy(joining = true, historyError = null) }
            launchJoin(conversationId)
        }

        private fun launchJoin(conversationId: String) {
            viewModelScope.launch {
                try {
                    val page = hubEvents.joinConversation(conversationId)
                    if (openConversationId != conversationId) return@launch
                    nextBeforeSequence = page.nextBeforeSequence
                    mergeAndRender(page.messages)
                    mutableState.update { it.copy(joining = false, canLoadOlder = page.nextBeforeSequence != null) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    if (openConversationId != conversationId) return@launch
                    mutableState.update { it.copy(joining = false, historyError = NetworkFailure.from(failure)) }
                }
            }
        }

        /** Leaves the hub's own subscription and flushes the draft - called when the operator
         * navigates back to the list. Does not clear [state]; there is nothing left observing it once
         * the screen is gone, and clearing would only cost a frame of stale content if it briefly were. */
        public fun close() {
            flushDraft()
            messagesJob?.cancel()
            messagesJob = null
            deliveryJob?.cancel()
            deliveryJob = null
            // `26-80`: a pending debounced mark-read is abandoned, not flushed early, the same way a
            // pending debounced draft write is flushed *early* rather than abandoned above - the two
            // pull in opposite directions on purpose. A draft is this operator's own words; losing one
            // because the timer had not fired yet would be a real loss `flushDraft` exists to prevent.
            // A mark-read the debounce window has not yet confirmed has, by definition, not dwelled on
            // screen long enough to be confidently "read" - `ago-console`'s own `tabVisible` guard makes
            // the identical call for a backgrounded tab (this file's own doc comment on why an Android
            // equivalent of that guard is not needed does not extend to "leaving the thread entirely").
            markReadJob?.cancel()
            markReadJob = null
            hubEvents.leaveConversation()
            openConversationId = null
        }

        /**
         * `26-80`: tells the server how far the operator has actually read, and does so honestly rather
         * than by assuming this screen's newest *loaded* message is its newest *rendered* one.
         *
         * `ago-console`'s own `ConversationPage.tsx` gets to make that assumption - its doc comment
         * states why: `Thread` auto-scrolls to the newest arrival whenever the operator is already at
         * the bottom, which they are on open, so "loaded" and "on screen" are the same fact there.
         * **That does not transfer here.** `ThreadScreen`'s `MessageList` starts at the bottom on open
         * for the identical reason (`reverseLayout = true` with `LazyListState`'s own default
         * `firstVisibleItemIndex = 0`), but nothing in this app re-scrolls to a new arrival while the
         * conversation stays open - checked directly against `ThreadScreen.kt`, which has no
         * `scrollToItem`/`animateScrollToItem` call anywhere in it. An operator scrolled up into history
         * when a new message lands has not seen it, and claiming its sequence here would mute it. So
         * [sequence] is never read off [ThreadUiState.messages] directly; it is what `MessageList`
         * itself reports as the newest message its `LazyListState` currently has visible - the one thing
         * this screen can actually prove was on screen, scrolled-to-the-bottom or not.
         *
         * **The `tabVisible` guard `WorkspaceLayout.markRead`'s own effect carries has no call here for
         * a different reason than "porting the bottom check already covers it": a backgrounded tab
         * still receives live pushes in a browser, which is exactly why the console needs the guard;
         * this app's own `OperatorHubConnectionLifecycle` (see `SignInViewModel.routeNow`'s doc comment)
         * ties the hub connection to process foreground, so a backgrounded app is not receiving the
         * pushes that would move [sequence] in the first place. If that ever changes - a background
         * push channel, say - this guard would need to be added for real, not assumed away a second
         * time.**
         *
         * Debounced and de-duplicated the same shape `ago-console`'s own `lastMarked` ref plus
         * `setTimeout` pair uses: [lastMarkedReadSequence] is the high-watermark already confirmed sent
         * for this conversation, so a [sequence] at or below it returns before a timer is even started,
         * and a newer one cancels whatever timer was still waiting rather than racing it - one settled
         * position sent, never a burst of intermediate ones.
         */
        public fun markReadUpTo(sequence: Long) {
            val conversationId = openConversationId ?: return
            val alreadySent = lastMarkedReadSequence
            if (alreadySent != null && alreadySent >= sequence) return

            markReadJob?.cancel()
            markReadJob =
                viewModelScope.launch {
                    delay(MARK_READ_DEBOUNCE_MILLIS)
                    lastMarkedReadSequence = sequence
                    // Fire-and-forget, matching `SignInViewModel.routeNow`'s own `hubConnection.connect()`
                    // call for a comparable non-critical background write: `ConversationsApi.markRead`
                    // never throws (it classifies its own transport/status failures internally, see its
                    // own doc comment), and its `Boolean` is deliberately not read here - a `false` is a
                    // cosmetic staleness the next debounced call or the next `open` corrects, never
                    // something worth an error an operator is not waiting on anyway (this item's own
                    // Scope: "fire-and-forget... never shown to the operator as an error"). `upToSequence`
                    // is `Int` on the wire (`MarkConversationReadRequest`, `ago-chat`) because the server
                    // assigns sequences as `int`; this app carries them as `Long` only because
                    // `MessageDto.sequence` does, so the narrowing here is exact, never a real truncation.
                    conversationsApi.markRead(conversationId, sequence.toInt())
                }
        }

        /** The manual "load older messages" affordance - a no-op while already loading, and a no-op
         * once the cursor is exhausted (`canLoadOlder == false`), the same double-guard
         * `ConversationListViewModel.claim`'s own in-flight check already establishes for a different
         * action. */
        public fun loadOlder() {
            val conversationId = openConversationId ?: return
            val cursor = nextBeforeSequence ?: return
            if (mutableState.value.loadingOlder) return

            mutableState.update { it.copy(loadingOlder = true, historyError = null) }
            viewModelScope.launch {
                try {
                    val page = withContext(ioDispatcher) { hubEvents.loadOlderHistory(conversationId, cursor, HISTORY_PAGE_SIZE) }
                    if (openConversationId != conversationId) return@launch
                    nextBeforeSequence = page.nextBeforeSequence
                    mergeAndRender(page.messages)
                    mutableState.update { it.copy(loadingOlder = false, canLoadOlder = page.nextBeforeSequence != null) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    if (openConversationId != conversationId) return@launch
                    mutableState.update { it.copy(loadingOlder = false, historyError = NetworkFailure.from(failure)) }
                }
            }
        }

        /**
         * Every keystroke. Editing the draft after a failed send clears [ThreadUiState.pendingRetry] -
         * `ago-console` has no equivalent of this because its composer is never unmounted, so a failed
         * send's own banner simply sits beside whatever the operator types next; here, once the operator
         * has changed their mind about the text, retrying the *old* body with the *old* id would resend
         * words they have since edited away, so the pending retry is abandoned instead.
         */
        public fun onDraftChanged(text: String) {
            failedSend = null
            mutableState.update { it.copy(draft = text, pendingRetry = false) }

            val conversationId = openConversationId ?: return
            draftWriteJob?.cancel()
            draftWriteJob =
                viewModelScope.launch {
                    delay(DRAFT_WRITE_DEBOUNCE_MILLIS)
                    persistDraft(conversationId, text)
                }
        }

        /** Writes whatever draft is currently on screen immediately, cancelling any pending debounced
         * write - see this class's own doc comment for why [ThreadRoute] calls this from `ON_STOP`. */
        public fun flushDraft() {
            val conversationId = openConversationId ?: return
            draftWriteJob?.cancel()
            draftWriteJob = null
            viewModelScope.launch { persistDraft(conversationId, mutableState.value.draft) }
        }

        private suspend fun persistDraft(
            conversationId: String,
            text: String,
        ) {
            withContext(ioDispatcher) {
                if (text.isEmpty()) draftStore.clear(conversationId) else draftStore.write(conversationId, text)
            }
        }

        /**
         * The send button (or the keyboard's own send action). A fresh
         * [ago.chat.android.core.network.realtime.newClientMessageId] every time an operator
         * deliberately starts a *new* send - [retrySend] is the only path that ever reuses one.
         */
        public fun sendClicked() {
            val conversationId = openConversationId ?: return
            val body = mutableState.value.draft.trim()
            if (body.isEmpty()) return

            draftWriteJob?.cancel()
            mutableState.update { it.copy(draft = "", pendingRetry = false, sendRefusedMessage = null) }
            viewModelScope.launch { persistDraft(conversationId, "") }
            send(conversationId, body, newClientMessageId())
        }

        /**
         * Retries the one pending send, if there is one - a no-op otherwise, so a stray tap on a
         * banner that already cleared itself (a reconnect resolved it, say) does nothing. Reuses
         * [FailedSend]'s own `clientMessageId`/body exactly, per [SendMessageResult]'s own doc comment
         * on which id is safe to reuse and why.
         */
        public fun retrySend() {
            val conversationId = openConversationId ?: return
            val pending = failedSend ?: return
            send(conversationId, pending.body, pending.clientMessageId)
        }

        public fun dismissSendRefusal() {
            mutableState.update { it.copy(sendRefusedMessage = null) }
        }

        private fun send(
            conversationId: String,
            body: String,
            clientMessageId: String,
        ) {
            mutableState.update { it.copy(sending = true) }
            viewModelScope.launch {
                when (val result = hubEvents.sendMessage(conversationId, body, clientMessageId)) {
                    is SendMessageResult.Sent -> {
                        // `3-02`: local echo only - the sent message itself reaches [state] through
                        // [messages]'s own live push, never appended from this return value directly
                        // (`OperatorHubEvents.sendMessage`'s own doc comment).
                        failedSend = null
                        mutableState.update { it.copy(sending = false, pendingRetry = false) }
                    }

                    SendMessageResult.NotConnected -> {
                        // Nothing was sent - safe to retry with a fresh id once reconnected.
                        failedSend = FailedSend(body, newClientMessageId())
                        mutableState.update { it.copy(sending = false, pendingRetry = true) }
                    }

                    is SendMessageResult.OutcomeUnknown -> {
                        // An invoke was genuinely in flight - retry-safe only with the *same* id
                        // (`Conversation.AddMessage`'s own dedup, `newClientMessageId`'s doc comment).
                        failedSend = FailedSend(body, clientMessageId)
                        mutableState.update { it.copy(sending = false, pendingRetry = true) }
                    }

                    is SendMessageResult.Refused -> {
                        // A real, definitive refusal - never retried automatically.
                        failedSend = null
                        mutableState.update { it.copy(sending = false, pendingRetry = false, sendRefusedMessage = result.message) }
                    }
                }
            }
        }

        private fun mergeAndRender(newOnes: List<MessageDto>) {
            for (message in newOnes) byId[message.id] = message
            val sorted = byId.values.sortedBy { it.sequence }
            mutableState.update { it.copy(messages = sorted) }
        }

        /**
         * `26-42`: one `MessageDelivered` push, applied to the matching entry already in [byId] - never
         * a second fetch, and never a message this class does not already hold (a push for a message
         * this instance has not seen - a different conversation's fan-out, or a race with the initial
         * join page - is silently ignored rather than synthesising a bubble from three fields).
         *
         * **Idempotent by construction, not by accident.** [existing]'s own [MessageDto.deliveredAt]
         * being non-null already is the one guard this needs: at-least-once delivery is assumed
         * everywhere (`CLAUDE.md` rule 5), so a repeated push for a message already marked delivered
         * returns before [mergeAndRender] runs at all - never a third tick (there is no third state to
         * reach), and never a redundant [state] emission for a change that already happened.
         */
        private fun applyDelivery(delivered: MessageDeliveredDto) {
            if (openConversationId != delivered.conversationId) return
            val existing = byId[delivered.messageId] ?: return
            if (existing.deliveredAt != null) return
            mergeAndRender(listOf(existing.copy(deliveredAt = delivered.deliveredAt)))
        }

        /**
         * A backstop, not the primary path - [close] (called from [ThreadRoute]'s own `onBack`, the
         * real "the operator left this thread" signal) is what ordinarily releases the hub's
         * subscription. This only matters for a teardown that skips `onBack` entirely - the Activity
         * finishing outright rather than backing out of it - and is deliberately synchronous:
         * `viewModelScope` may already be in the process of being cancelled by the time `onCleared`
         * runs, so this does no coroutine work, only the plain, non-suspending
         * [OperatorHubEvents.leaveConversation] call.
         */
        override fun onCleared() {
            messagesJob?.cancel()
            deliveryJob?.cancel()
            hubEvents.leaveConversation()
        }

        private data class FailedSend(
            val body: String,
            val clientMessageId: String,
        )

        private companion object {
            const val DRAFT_WRITE_DEBOUNCE_MILLIS = 400L

            /** `ago-console`'s own `MARK_READ_DEBOUNCE_MS` (`ConversationPage.tsx`) - the identical
             * value, for the identical reason: there is no technical basis for the two clients to
             * disagree about how long to wait before confirming a read position neither of them chose
             * for a load-bearing reason. */
            const val MARK_READ_DEBOUNCE_MILLIS = 500L
        }
    }

/** `26-15`: `ago-console`'s own `HISTORY_PAGE_SIZE` (`ConversationPage.tsx`), same value - there is no
 * reason for the two clients to disagree about a number neither of them chose for a technical reason
 * (`ago-chat`'s `GetHistoryAsync` accepts whatever a caller sends). `internal`, not `private`: shared
 * with `ThreadViewModelTest`'s own fixtures so a keyset-boundary test can build a fixture sized in
 * terms of this exact constant rather than a copy-pasted magic number that could silently drift from
 * it. */
internal const val HISTORY_PAGE_SIZE = 50
