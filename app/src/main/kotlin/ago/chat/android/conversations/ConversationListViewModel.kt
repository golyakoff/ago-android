package ago.chat.android.conversations

import ago.chat.android.core.domain.conversations.ClaimResult
import ago.chat.android.core.domain.conversations.ConversationListCache
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.ConversationSummary
import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.domain.conversations.QueueResult
import ago.chat.android.core.domain.conversations.oldestFirst
import ago.chat.android.core.network.realtime.MessageDto
import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-14`: the conversation list's whole state machine — one screen, one segmented control, fed from
 * three genuinely different sources: [ConversationListCache] (instant, possibly stale), [ConversationsApi]
 * (slow, authoritative) and [OperatorHubEvents] (live, partial).
 *
 * ## Why the hub events are an *overlay*, never the source of truth for a whole row
 *
 * Neither a `ConversationAssigned` nor a `MessageReceived` push carries enough to render a full row —
 * `ConversationAssignedDto`'s own doc comment names the same gap `ago-console`'s
 * `WorkspaceLayout.tsx` found first: no visitor, no emoji pair, no name. So a push here does exactly what
 * it does there: marks *which* conversation changed and re-asks [ConversationsApi.fetchQueue] for the
 * truth, rather than assembling a synthetic row from three GUIDs. [newlyAssignedIds] and [unreadBumps]
 * are the only state that survives *between* one queue answer and the next; every row's own displayed
 * fields always come from the most recent [lastQueue] a real fetch or a real cache read produced.
 *
 * ## Why this needs no lock
 *
 * Every mutation below runs on `viewModelScope`'s own dispatcher (`Dispatchers.Main.immediate`) — the
 * `withContext(ioDispatcher)` blocks only ever wrap the suspending I/O call itself and hand back a
 * plain value; they never touch [lastQueue]/[newlyAssignedIds]/[unreadBumps]. Two coroutines launched
 * from `viewModelScope` interleave but never run their own bodies concurrently with each other on that
 * dispatcher, so these fields need nothing more than being plain `var`s.
 */
@HiltViewModel
public class ConversationListViewModel
    @Inject
    constructor(
        private val api: ConversationsApi,
        private val cache: ConversationListCache,
        private val hubEvents: OperatorHubEvents,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(ConversationListUiState())
        public val state: StateFlow<ConversationListUiState> = mutableState.asStateFlow()

        private var lastQueue: ConversationQueue? = null
        private var newlyAssignedIds: Set<String> = emptySet()
        private var unreadBumps: Map<String, Int> = emptyMap()
        private var claimingIds: Set<String> = emptySet()
        private var claimErrors: Map<String, String> = emptyMap()

        private var waitingPollJob: Job? = null

        init {
            viewModelScope.launch {
                val cached = withContext(ioDispatcher) { cache.read() }
                if (cached != null) {
                    lastQueue = cached
                    render(stale = true)
                }
            }
            refresh()

            viewModelScope.launch {
                hubEvents.assignments.collect { dto -> onAssigned(dto.conversationId) }
            }
            viewModelScope.launch {
                hubEvents.allMessages.collect { dto -> onMessage(dto) }
            }
        }

        /**
         * Switches which half of the queue is showing. `docs/backlog/26-14-*.md`'s own poll-interval
         * decision lives here: the periodic re-fetch below only ever runs while «Ожидают» is the
         * selected tab **and** the screen itself is visible ([onScreenStarted]/[onScreenStopped]) — a
         * deliberate divergence from `ago-console`'s own always-on 15-second timer, stated in full in
         * this class's own KDoc on [startWaitingPollIfNeeded].
         */
        public fun onTabSelected(tab: ConversationListTab) {
            mutableState.update { it.copy(selectedTab = tab) }
            startWaitingPollIfNeeded()
        }

        /** Called from the screen's own `DisposableEffect`/`LifecycleEventObserver` on `ON_START` —
         * resumes the «Ожидают» poll if that is still the selected tab, the same "screen visible, tab
         * relevant" gate [onTabSelected] applies. */
        public fun onScreenStarted() {
            startWaitingPollIfNeeded()
        }

        /** The `ON_STOP` half of the pair above. Idempotent — cancelling a job that is not running is
         * a no-op, so this is safe to call from `onPause` as well as `onStop` without tracking which
         * one last ran. */
        public fun onScreenStopped() {
            waitingPollJob?.cancel()
            waitingPollJob = null
        }

        /** The row was tapped. `26-15` is what this eventually opens; this item's own job is only the
         * one side effect that belongs to *this* screen regardless of where `26-15` sends the operator
         * next — clearing the "New" badge, `ConversationList.tsx`'s own `"opened"` event, restated. */
        public fun onRowOpened(conversationId: String) {
            if (conversationId !in newlyAssignedIds) return
            newlyAssignedIds = newlyAssignedIds - conversationId
            render(stale = mutableState.value.isStale)
        }

        /** The manual pull-to-refresh / retry action, and also this class's own first fetch. Never
         * throws into the caller — every [QueueResult] arm is handled completely here. */
        public fun refresh() {
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.fetchQueue() }) {
                    is QueueResult.Loaded -> {
                        lastQueue = result.queue
                        val stillMine = result.queue.assignedToMe.map { it.conversationId }.toSet()
                        // `5-15`'s own reasoning, restated: a fresh snapshot already reflects every
                        // arrival the server knows about, so the local overlay retires for anything
                        // this snapshot actually re-read.
                        newlyAssignedIds = newlyAssignedIds.intersect(stillMine)
                        unreadBumps = unreadBumps.filterKeys { it in stillMine }
                        val stillWaiting = result.queue.waiting.map { it.conversationId }.toSet()
                        claimErrors = claimErrors.filterKeys { it in stillWaiting }
                        claimingIds = claimingIds.intersect(stillWaiting)
                        render(stale = false)
                        mutableState.update { it.copy(loadError = null) }
                        withContext(ioDispatcher) { cache.write(result.queue) }
                    }

                    is QueueResult.Failed -> {
                        // The cache (or the previous fetch's own answer) stays on screen exactly as it
                        // was - only the error banner changes. Never cleared to empty on a failure: a
                        // network blip must not make a real list disappear.
                        mutableState.update { it.copy(loadError = result.message) }
                    }
                }
            }
        }

        /**
         * Claims one waiting conversation. A real server call, always — `docs/backlog/26-14-*.md`'s
         * own Scope, and rule 8 read from the client side: whether a claim succeeds is exactly the
         * kind of write decision this class never answers from [cache] or from [lastQueue].
         */
        public fun claim(conversationId: String) {
            if (conversationId in claimingIds) return
            claimingIds = claimingIds + conversationId
            claimErrors = claimErrors - conversationId
            render(stale = mutableState.value.isStale)

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.claim(conversationId) }) {
                    ClaimResult.Claimed -> {
                        claimingIds = claimingIds - conversationId
                        // The row's own move into "Мои" is observed by re-fetching, never assumed from
                        // this `204` alone - the same "re-fetch rather than trust a partial write
                        // result" choice `onAssigned` below makes for a hub push.
                        refresh()
                    }

                    is ClaimResult.Refused -> {
                        claimingIds = claimingIds - conversationId
                        claimErrors = claimErrors + (conversationId to result.detail)
                        render(stale = mutableState.value.isStale)
                        // Deliberately no retry of any kind, automatic or scheduled - `result.detail`
                        // is shown once, inline, and the row stays exactly where the server left it,
                        // in «Ожидают».
                    }
                }
            }
        }

        /** Dismisses a shown claim refusal without attempting the claim again — a plain acknowledgement,
         * never a retry trigger. */
        public fun dismissClaimError(conversationId: String) {
            if (conversationId !in claimErrors) return
            claimErrors = claimErrors - conversationId
            render(stale = mutableState.value.isStale)
        }

        private fun onAssigned(conversationId: String) {
            newlyAssignedIds = newlyAssignedIds + conversationId
            render(stale = mutableState.value.isStale)
            // `WorkspaceLayout.tsx`'s own `onConversationAssigned` handler: re-fetch the whole queue
            // rather than merge one row in - see this class's own doc comment for why a push alone is
            // never enough to render a row. Deliberately **not** followed by any navigation of any
            // kind - `docs/navigation.md`: "a new assignment arriving never navigates" - proven by
            // `ConversationListViewModelTest`'s own "never navigates" case, which asserts nothing about
            // [state] beyond the badge and the row list changes.
            refresh()
        }

        private fun onMessage(message: MessageDto) {
            val conversationId = message.conversationId ?: return
            if (message.authorKind != VISITOR_AUTHOR_KIND) return

            val isAssignedToMe = lastQueue?.assignedToMe?.any { it.conversationId == conversationId } ?: false
            if (!isAssignedToMe) return

            unreadBumps = unreadBumps + (conversationId to ((unreadBumps[conversationId] ?: 0) + 1))
            render(stale = mutableState.value.isStale)
        }

        private fun render(stale: Boolean) {
            val queue = lastQueue ?: return
            mutableState.update { current ->
                current.copy(
                    mine = oldestFirst(queue.assignedToMe).map { it.toRowUi() },
                    waiting = oldestFirst(queue.waiting).map { it.toRowUi() },
                    isStale = stale,
                    hasData = true,
                )
            }
        }

        private fun ConversationSummary.toRowUi() =
            ConversationRowUi(
                conversationId = conversationId,
                visitorId = visitorId,
                emojiCreature = emojiCreature,
                emojiFood = emojiFood,
                visitorName = visitorName,
                createdAt = createdAt,
                unreadCount = operatorUnreadCount + (unreadBumps[conversationId] ?: 0),
                isNewlyAssigned = conversationId in newlyAssignedIds,
                isClaiming = conversationId in claimingIds,
                claimError = claimErrors[conversationId],
            )

        /**
         * `docs/backlog/26-14-*.md`'s own poll-interval decision. **Same 15-second cadence as
         * `ago-console`'s `WAITING_REFRESH_INTERVAL_MS`** (`WorkspaceLayout.tsx:33`) — this item's own
         * Verified note corrects `push-notifications.md`'s stale "10-second" claim, and there is no
         * reason for the two clients to disagree about a number neither of them chose (it is simply how
         * often the console's author decided a human should wait to see a new walk-in).
         *
         * **Deliberately narrower than the console's own timer.** `WorkspaceLayout.tsx` refreshes *both*
         * halves of the queue unconditionally, every 15 seconds, for as long as the tab is open - a
         * background browser tab a desktop leaves open costs the same battery and the same (unmetered,
         * in the ordinary case) network whether or not anyone is looking at it. A phone is not the same
         * machine: it is frequently on a metered connection, and this screen already has a genuinely
         * live channel for "Мои" (`onAssigned`/`onMessage` above) that the console's own 15-second timer
         * exists only to back up for «Ожидают», the one half with no server-side push at all
         * (`docs/backlog/26-14-*.md`'s own Verified note: "nothing broadcasts 'a new conversation
         * started waiting'"). So the poll here runs **only while «Ожидают» is the selected tab and the
         * screen is in the foreground** ([onScreenStarted]/[onScreenStopped]) — never while «Мои» is
         * showing, and never while the app is backgrounded, because in both cases it would spend a
         * phone's own metered data on a list nobody is currently looking at.
         */
        private fun startWaitingPollIfNeeded() {
            val shouldPoll = mutableState.value.selectedTab == ConversationListTab.Waiting
            if (!shouldPoll) {
                waitingPollJob?.cancel()
                waitingPollJob = null
                return
            }
            if (waitingPollJob?.isActive == true) return

            waitingPollJob =
                viewModelScope.launch {
                    while (isActive) {
                        delay(WAITING_POLL_INTERVAL_MILLIS)
                        refresh()
                    }
                }
        }

        private companion object {
            const val WAITING_POLL_INTERVAL_MILLIS = 15_000L
            const val VISITOR_AUTHOR_KIND = "Visitor"
        }
    }
