package ago.chat.android.team

import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.core.network.realtime.SendMessageResult
import ago.chat.android.core.network.realtime.TeamMessageDto
import ago.chat.android.core.network.realtime.newClientMessageId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * `26-54`: the team room's whole state machine — one tenant-wide room, no join, no conversation id.
 * Modelled on [ago.chat.android.thread.ThreadViewModel] wherever the two are genuinely the same problem
 * (the [byId] merge-by-id store, [SendMessageResult]'s reused retry trichotomy) and deliberately
 * different where the room itself is different (no [ago.chat.android.core.network.realtime.OperatorHubEvents.joinConversation]
 * analogue exists, so there is nothing to open/leave, and this class holds no `conversationId` at all).
 *
 * ## The one connection-state effect that both loads the room and catches it up after a reconnect
 *
 * `ago-console`'s `TeamChatPage.tsx` needs a `previousConnectionStateRef` to tell "the very first
 * connected transition" from "a later reconnect" apart, because React re-runs its effect on every
 * `connectionState` change including ones that are not a fresh transition into `"connected"`. A Kotlin
 * [kotlinx.coroutines.flow.StateFlow] does not have that problem — it already suppresses a consecutive
 * emission of an equal value — so [everConnectedOnce] alone is enough: [state] is collected once here,
 * and every emission of [OperatorHubConnectionState.Connected] this class ever sees is, by construction,
 * a **transition into** that state (never a repeat of it), which is exactly "the room just became
 * reachable" whether that is the very first time or the Nth reconnect. The first one loads history
 * ([loadHistory]); every one after it asks for the delta after [lastRenderedSequence] instead
 * ([catchUpByDelta]) — `TeamChatPage.tsx`'s own two branches, reached here with one flag instead of one
 * ref.
 *
 * This is also what answers this item's own load-bearing Done-when box: landing on Команда before the
 * hub has connected must show a loading state and then the room, never a crash. Nothing in this class
 * ever calls a hub method before the first [OperatorHubConnectionState.Connected] arrives — unlike
 * `ago-console`'s first cut, which called `getTeamHistory` unconditionally on mount and crashed on
 * `OperatorHubConnection.requireConnection()`'s own precondition the instant a reload landed straight on
 * `/team/chat` (`TeamChatPage.tsx`'s own doc comment; Android's identical hazard is
 * `OperatorHubConnection.requireConnection()`, which throws before the first `connect()`).
 */
@HiltViewModel
public class TeamChatViewModel
    @Inject
    constructor(
        private val hubEvents: OperatorHubEvents,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(TeamChatUiState())
        public val state: StateFlow<TeamChatUiState> = mutableState.asStateFlow()

        private val byId = LinkedHashMap<String, TeamMessageDto>()
        private var nextBeforeSequence: Long? = null

        /** The highest [TeamMessageDto.sequence] this instance has ever rendered — [catchUpByDelta]'s
         * own cursor, the identical role `lastSequenceRef` plays in `TeamChatPage.tsx`. Never regresses:
         * [mergeAndRender] only ever raises it, since a merge only ever adds messages this instance has
         * not already seen a *higher* sequence than. */
        private var lastRenderedSequence: Long = 0

        /** `false` until [state] has reported [OperatorHubConnectionState.Connected] once — see this
         * class's own doc comment for why this single flag is enough to tell the first connect from
         * every later reconnect apart. */
        private var everConnectedOnce = false

        private var failedSend: FailedSend? = null

        init {
            viewModelScope.launch {
                hubEvents.teamMessages.collect { message -> mergeAndRender(listOf(message)) }
            }
            // `26-54`'s own Out of scope leaves no remove button on this screen, but a removal by
            // another operator must still stop showing content this connection already rendered — see
            // [OperatorHubEvents.teamMessageRemovals]'s own doc comment.
            viewModelScope.launch {
                hubEvents.teamMessageRemovals.collect { removed -> applyRemoval(removed) }
            }
            viewModelScope.launch {
                hubEvents.state.collect { connectionState ->
                    mutableState.update { it.copy(hubConnectionState = connectionState) }
                    if (connectionState != OperatorHubConnectionState.Connected) return@collect

                    if (!everConnectedOnce) {
                        everConnectedOnce = true
                        loadHistory()
                    } else {
                        catchUpByDelta()
                    }
                }
            }
        }

        /** The manual retry affordance for a failed history load — a no-op unless the previous attempt
         * actually failed. */
        public fun retryLoad() {
            if (mutableState.value.historyError == null) return
            loadHistory()
        }

        /** The "load older messages" affordance — a no-op while already loading, and a no-op once the
         * cursor is exhausted, [ago.chat.android.thread.ThreadViewModel.loadOlder]'s own double-guard,
         * restated. */
        public fun loadOlder() {
            val cursor = nextBeforeSequence ?: return
            if (mutableState.value.loadingOlder) return

            mutableState.update { it.copy(loadingOlder = true, historyError = null) }
            viewModelScope.launch {
                try {
                    val page = hubEvents.getTeamHistory(cursor, HISTORY_PAGE_SIZE)
                    nextBeforeSequence = page.nextBeforeSequence
                    mergeAndRender(page.messages)
                    mutableState.update { it.copy(loadingOlder = false, canLoadOlder = page.nextBeforeSequence != null) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    mutableState.update { it.copy(loadingOlder = false, historyError = failure.describe()) }
                }
            }
        }

        public fun onDraftChanged(text: String) {
            failedSend = null
            mutableState.update { it.copy(draft = text, pendingRetry = false) }
        }

        /** The send button. A fresh [newClientMessageId] every time an operator deliberately starts a
         * *new* send — [retrySend] is the only path that ever reuses one. */
        public fun sendClicked() {
            val body = mutableState.value.draft.trim()
            if (body.isEmpty()) return

            mutableState.update { it.copy(draft = "", pendingRetry = false, sendRefusedMessage = null) }
            send(body, newClientMessageId())
        }

        /** Retries the one pending send, if there is one — a no-op otherwise. Reuses [FailedSend]'s own
         * `clientMessageId`/body exactly, per [SendMessageResult]'s own doc comment on which id is safe
         * to reuse and why. */
        public fun retrySend() {
            val pending = failedSend ?: return
            send(pending.body, pending.clientMessageId)
        }

        public fun dismissSendRefusal() {
            mutableState.update { it.copy(sendRefusedMessage = null) }
        }

        private fun loadHistory() {
            mutableState.update { it.copy(loading = true, historyError = null) }
            viewModelScope.launch {
                try {
                    val page = hubEvents.getTeamHistory(null, HISTORY_PAGE_SIZE)
                    nextBeforeSequence = page.nextBeforeSequence
                    mergeAndRender(page.messages)
                    mutableState.update { it.copy(loading = false, canLoadOlder = page.nextBeforeSequence != null) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    mutableState.update { it.copy(loading = false, historyError = failure.describe()) }
                }
            }
        }

        /**
         * `26-54`'s own reconnect catch-up — `ago-console`'s own `getTeamDelta` call, restated.
         * Best-effort on failure, exactly as `TeamChatPage.tsx` states for its own identical call: a
         * live push, or the next reconnect's own catch-up, still arrives, so there is nothing here worth
         * surfacing a second error banner for over whatever [TeamChatUiState.historyError] already
         * shows.
         */
        private fun catchUpByDelta() {
            viewModelScope.launch {
                try {
                    val page = hubEvents.getTeamDelta(lastRenderedSequence)
                    mergeAndRender(page.messages)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // Best-effort — see this function's own doc comment.
                }
            }
        }

        private fun send(
            body: String,
            clientMessageId: String,
        ) {
            mutableState.update { it.copy(sending = true) }
            viewModelScope.launch {
                when (val result = hubEvents.sendTeamMessage(body, clientMessageId)) {
                    is SendMessageResult.Sent -> {
                        // Local echo only - the sent message reaches [state] through [hubEvents.teamMessages]'s
                        // own live push, never appended from this return value directly.
                        failedSend = null
                        mutableState.update { it.copy(sending = false, pendingRetry = false) }
                    }

                    SendMessageResult.NotConnected -> {
                        failedSend = FailedSend(body, newClientMessageId())
                        mutableState.update { it.copy(sending = false, pendingRetry = true) }
                    }

                    is SendMessageResult.OutcomeUnknown -> {
                        failedSend = FailedSend(body, clientMessageId)
                        mutableState.update { it.copy(sending = false, pendingRetry = true) }
                    }

                    is SendMessageResult.Refused -> {
                        failedSend = null
                        mutableState.update { it.copy(sending = false, pendingRetry = false, sendRefusedMessage = result.message) }
                    }
                }
            }
        }

        private fun mergeAndRender(newOnes: List<TeamMessageDto>) {
            if (newOnes.isEmpty()) return
            for (message in newOnes) byId[message.id] = message
            val sorted = byId.values.sortedBy { it.sequence }
            lastRenderedSequence = sorted.last().sequence
            mutableState.update { it.copy(messages = sorted) }
        }

        /** `26-54`: [OperatorHubEvents.teamMessageRemovals], restated on this instance's own state — a
         * removal for a message this instance has not rendered (older than its own first page) is
         * silently ignored, the identical tolerance `TeamChatPage.tsx`'s own `applyRemoval` states. */
        private fun applyRemoval(removed: TeamMessageDto) {
            if (!byId.containsKey(removed.id)) return
            mergeAndRender(listOf(removed))
        }

        private data class FailedSend(
            val body: String,
            val clientMessageId: String,
        )

        private companion object {
            const val HISTORY_PAGE_SIZE = 50
        }
    }

private fun Exception.describe(): String = "${this::class.simpleName}: ${message ?: "no detail"}"
