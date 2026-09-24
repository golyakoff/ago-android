package ago.chat.android.conversations

import ago.chat.android.core.domain.conversations.AllConversationsResult
import ago.chat.android.core.domain.conversations.ClaimResult
import ago.chat.android.core.domain.conversations.ConversationListCache
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.ConversationSummary
import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.domain.conversations.ErasureResult
import ago.chat.android.core.domain.conversations.QueueResult
import ago.chat.android.core.domain.conversations.oldestFirst
import ago.chat.android.core.network.realtime.MessageDto
import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.devices.ConversationRefreshSignal
import ago.chat.android.devices.NoOpConversationRefreshSignal
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
        // `26-18`: `onDeletedMessages()`'s own recovery hook - see [ConversationRefreshSignal]'s own
        // doc comment for why a `Service` reaches this class through a singleton event rather than a
        // direct reference. Defaulted to [NoOpConversationRefreshSignal] for the identical
        // "this app's own back-contract instrumented tests construct this class directly, for reasons
        // unrelated to push" reason [ThreadViewModel][ago.chat.android.thread.ThreadViewModel]'s own
        // `openConversationTracker` parameter states in full.
        private val refreshSignal: ConversationRefreshSignal = NoOpConversationRefreshSignal,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(ConversationListUiState())
        public val state: StateFlow<ConversationListUiState> = mutableState.asStateFlow()

        private var lastQueue: ConversationQueue? = null
        private var newlyAssignedIds: Set<String> = emptySet()
        private var unreadBumps: Map<String, Int> = emptyMap()
        private var claimingIds: Set<String> = emptySet()
        private var claimErrors: Map<String, ClaimErrorUi> = emptyMap()

        /** `26-90`: the «Все» tab's own accumulated rows, in server order (newest first), appended to
         * one page at a time. A plain `List`, not a `Map` - this list is *ordered* and the order is the
         * server's keyset order, which no client-side re-sort is allowed to second-guess. */
        private var allRows: List<ConversationSummary> = emptyList()
        private var allNextBeforeId: String? = null

        /**
         * `26-90`: conversations whose erasure has been requested and which the server is still
         * returning. In memory only, never written to [cache] - a held "erasing" row is a fact about
         * *this* screen's last few seconds, not about the conversation, and a restored-from-disk row
         * marked erasing after a process death would be asserting something this app never verified.
         *
         * Cleared for any id a fresh *first* page no longer contains (see [reloadAll]) - which is the
         * whole mechanism: the row leaves because the server stopped sending it, not because this class
         * decided it was gone.
         */
        private var erasingIds: Set<String> = emptySet()

        private var waitingPollJob: Job? = null

        /** `26-90`: the second, narrower poll - see [startErasurePollIfNeeded]. Kept as its own job
         * rather than folded into [waitingPollJob] because the two have different conditions and
         * different targets, and one variable holding whichever poll happened to start last would make
         * "«Ожидают» is showing" silently cancel an erasure watch that is still needed. */
        private var erasurePollJob: Job? = null

        /** `26-75`: a one-shot event — a successful [claim] takes the operator straight into the thread,
         * matching `ago-console`'s own primary-workspace behaviour (`23-04`, `ConversationList.tsx`'s
         * `NavLink` row). Not a `StateFlow`, for the identical reason
         * [ago.chat.android.shell.SettingsViewModel.siteSwitched] gives for its own `Channel`: a
         * `StateFlow` re-delivering the same conversationId to a screen recreated after rotation would
         * navigate a second time for free, into a thread the operator had already opened and possibly
         * already left. */
        private val claimedEvents = Channel<String>(Channel.BUFFERED)
        public val claimedConversations: Flow<String> = claimedEvents.receiveAsFlow()

        /** [onActiveSiteChanged]'s own memory of the last site it was told about — `null` is a real,
         * distinct value ("no site known yet"), so [knownActiveSite] is not itself enough to tell
         * "never called" from "called once with null"; [hasSeenActiveSite] is what actually gates the
         * very first call from ever counting as a change. */
        private var hasSeenActiveSite = false
        private var knownActiveSite: String? = null

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
            // `26-18`: `AgoPushMessagingService.onDeletedMessages()`'s own recovery hook, wired to this
            // class's own established "something changed, re-ask for the truth" answer - the identical
            // `refresh()` call [onAssigned]/[onMessage] above already make for a live hub push.
            viewModelScope.launch {
                refreshSignal.refreshRequests.collect { refresh() }
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
            startErasurePollIfNeeded()
            // `26-90`: «Все» is fetched lazily - the first time it is actually selected, never on
            // screen start. An operator who never opens this tab never pays for the site-wide read,
            // which on a real site is the expensive one.
            if (tab == ConversationListTab.All && !mutableState.value.allHasData && !mutableState.value.isLoadingAll) {
                reloadAll()
            }
        }

        /** Called from the screen's own `DisposableEffect`/`LifecycleEventObserver` on `ON_START` —
         * resumes the «Ожидают» poll if that is still the selected tab, the same "screen visible, tab
         * relevant" gate [onTabSelected] applies. */
        public fun onScreenStarted() {
            startWaitingPollIfNeeded()
            startErasurePollIfNeeded()
        }

        /** The `ON_STOP` half of the pair above. Idempotent — cancelling a job that is not running is
         * a no-op, so this is safe to call from `onPause` as well as `onStop` without tracking which
         * one last ran. */
        public fun onScreenStopped() {
            waitingPollJob?.cancel()
            waitingPollJob = null
            erasurePollJob?.cancel()
            erasurePollJob = null
        }

        /** The row was tapped. `26-15` is what this eventually opens; this item's own job is only the
         * one side effect that belongs to *this* screen regardless of where `26-15` sends the operator
         * next — clearing the "New" badge, `ConversationList.tsx`'s own `"opened"` event, restated. */
        public fun onRowOpened(conversationId: String) {
            if (conversationId !in newlyAssignedIds) return
            newlyAssignedIds = newlyAssignedIds - conversationId
            render(stale = mutableState.value.isStale)
        }

        /**
         * `26-17`: told the active site on every composition of [ago.chat.android.conversations.ConversationListRoute]
         * (mount, recomposition, and every remount `ConversationsTabHost`'s own list/thread toggle or a
         * bottom-tab switch causes) — deliberately tolerant of being called far more often than the site
         * actually changes, and deliberately keyed on **this instance's own memory** of the last value
         * rather than on anything Compose remembers, because a plain `LaunchedEffect` re-fires on every
         * fresh mount regardless of whether its key's *value* repeats. Without that instance-level
         * memory, this would re-[refresh] on the identical round trip
         * `ConversationListViewModelTest`'s own back-button-contract sibling proves must **not** re-fetch
         * (`BackContractDialogsTabTest.clause1_backFromThreadReturnsToTheListWithoutRefetching`) —
         * [hasSeenActiveSite]/[knownActiveSite] are what make this call a no-op on that exact path while
         * still catching a genuine switch, which is the one thing `26-17`'s own Done-when needs from this
         * class: "an operator... switches between them and the conversation list changes accordingly".
         */
        public fun onActiveSiteChanged(siteId: String?) {
            val isRealChange = hasSeenActiveSite && siteId != knownActiveSite
            hasSeenActiveSite = true
            knownActiveSite = siteId
            if (isRealChange) refresh()
        }

        /** `26-60`: the same in-flight guard [claim] already keeps per-row in `claimingIds` - one
         * request out at a time, checked here rather than in `claimingIds` because this call has no id
         * of its own to key a set on. Read and written only from `viewModelScope`'s own dispatcher, the
         * same "no lock needed" argument this class's own class-level doc comment makes for every other
         * plain `var` here. */
        private var isRefreshing = false

        /** The manual pull-to-refresh / retry action, and also this class's own first fetch. Never
         * throws into the caller — every [QueueResult] arm is handled completely here.
         *
         * `26-60`: a no-op while a call is already out ([isRefreshing]) - without this, tapping the
         * screen's own retry control twice in the time it takes the first request to answer sent two
         * identical fetches, and the Done-when this item ships against says exactly one must go out.
         * [isRefreshing] always returns to `false` - success and failure alike - in a `finally`, so a
         * failed request never leaves the control permanently disabled. */
        public fun refresh() {
            if (isRefreshing) return
            isRefreshing = true
            mutableState.update { it.copy(isRefreshing = true) }

            viewModelScope.launch {
                try {
                    when (val result = withContext(ioDispatcher) { api.fetchQueue() }) {
                        is QueueResult.Loaded -> {
                            lastQueue = result.queue
                            val stillMine =
                                result.queue.assignedToMe
                                    .map { it.conversationId }
                                    .toSet()
                            // `5-15`'s own reasoning, restated: a fresh snapshot already reflects every
                            // arrival the server knows about, so the local overlay retires for anything
                            // this snapshot actually re-read.
                            newlyAssignedIds = newlyAssignedIds.intersect(stillMine)
                            unreadBumps = unreadBumps.filterKeys { it in stillMine }
                            val stillWaiting =
                                result.queue.waiting
                                    .map { it.conversationId }
                                    .toSet()
                            claimErrors = claimErrors.filterKeys { it in stillWaiting }
                            claimingIds = claimingIds.intersect(stillWaiting)
                            render(stale = false)
                            mutableState.update { it.copy(loadError = null) }
                            withContext(ioDispatcher) { cache.write(result.queue) }
                        }

                        is QueueResult.Failed -> {
                            // The cache (or the previous fetch's own answer) stays on screen exactly as
                            // it was - only the error banner changes. Never cleared to empty on a
                            // failure: a network blip must not make a real list disappear. When there
                            // is no cache and no previous answer either, [lastQueue] stays `null` and
                            // [render] never runs - `26-60`'s own no-data branch is what
                            // [ConversationListScreen] draws for exactly that combination, rather than
                            // the indefinite spinner it used to.
                            mutableState.update { it.copy(loadError = result.reason) }
                        }
                    }
                } finally {
                    isRefreshing = false
                    mutableState.update { it.copy(isRefreshing = false) }
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
                        // `26-75`: fired directly off this successful response, not after `refresh()`
                        // below confirms anything - the same synchronous guarantee `ago-console`'s own
                        // `ClaimConversationButtonProps.onClaimed` documents: "there is no completion
                        // poll to wait on first". By the time the server answered `Claimed` the claim
                        // already happened; waiting for this screen's own re-fetch to catch up before
                        // moving the operator would only add a round trip's worth of visible delay to a
                        // fact that is already true. `onTabSelected` (not a bare state mutation) so the
                        // switch also stops the «Ожидают» poll the same way a manual tab tap would.
                        onTabSelected(ConversationListTab.Mine)
                        claimedEvents.send(conversationId)
                        // The row's own move into "Мои" is observed by re-fetching, never assumed from
                        // this `204` alone - the same "re-fetch rather than trust a partial write
                        // result" choice `onAssigned` below makes for a hub push.
                        refresh()
                    }

                    is ClaimResult.Refused -> {
                        claimingIds = claimingIds - conversationId
                        claimErrors = claimErrors + (conversationId to ClaimErrorUi.ServerRefusal(result.detail))
                        render(stale = mutableState.value.isStale)
                        // Deliberately no retry of any kind, automatic or scheduled - `result.detail`
                        // is shown once, inline, and the row stays exactly where the server left it,
                        // in «Ожидают».
                    }

                    is ClaimResult.Failed -> {
                        // `26-59`: this call never reached a genuine server refusal at all - a dropped
                        // connection, or a non-2xx with no `detail` to show. Rendered from
                        // [result.reason]'s own classification, never a fabricated `detail` string.
                        claimingIds = claimingIds - conversationId
                        claimErrors = claimErrors + (conversationId to ClaimErrorUi.Unavailable(result.reason))
                        render(stale = mutableState.value.isStale)
                    }
                }
            }
        }

        /**
         * `26-90`: told which permissions this operator actually holds, by the one caller
         * ([ConversationListRoute]) that already has them in hand - the same "the caller who already
         * has the permission set computes the Boolean" split `AppShellScreen`'s own `teamTab`/
         * `bookingsTab` slots already draw, rather than a second
         * [ago.chat.android.core.domain.permissions.OperatorPermissionsApi] read from this class.
         *
         * Only `conversation:erase` reaches this class at all. `site:configure` - the one that decides
         * whether «Все» exists - never does: a tab this operator cannot see is one this class must
         * never fetch for, and the cleanest way to guarantee that is for the screen simply not to offer
         * the segment ([visibleConversationListTabs]) and for [onTabSelected] to be the only thing that
         * ever starts the fetch.
         */
        public fun onEraseCapabilityChanged(canErase: Boolean) {
            if (mutableState.value.canErase == canErase) return
            mutableState.update { it.copy(canErase = canErase) }
        }

        /**
         * `26-90`: ticks or unticks one status checkbox and re-asks the server. **Re-asks** - the
         * filter is a request parameter, not a predicate over rows already in hand: this list is
         * keyset-paginated, so narrowing a page that was already cut would show an empty tab while more
         * matching rows sat one page further down, with no way for the operator to tell that apart from
         * "there are none" (`IConversationReadStore.GetAllForSiteAsync`'s own remarks).
         *
         * Unticking the **last** ticked box is refused rather than allowed through. An empty set means
         * "unfiltered" to the server (`GetAllConversationsForSiteHandler`: no states is not a filter
         * that matches nothing), so letting the operator clear every box would answer with *every*
         * conversation - the exact opposite of what clearing a filter looks like it should do.
         */
        public fun onStateFilterToggled(filter: ConversationStateFilter) {
            val current = mutableState.value.allFilter
            val next = if (filter in current) current - filter else current + filter
            if (next.isEmpty()) return

            mutableState.update { it.copy(allFilter = next) }
            reloadAll()
        }

        /**
         * `26-90`: the next keyset page, asked for when the list scrolls near its end. Guarded on both
         * [ConversationListUiState.isLoadingAll] and [ConversationListUiState.allHasMore], because the
         * scroll trigger fires on composition and can fire again during the same fetch - without the
         * first guard that would put two identical requests in flight and append the same page twice.
         */
        public fun loadMoreAll() {
            val current = mutableState.value
            if (current.isLoadingAll || !current.allHasMore || allNextBeforeId == null) return
            fetchAllPage(beforeId = allNextBeforeId)
        }

        /** `26-90`: re-reads the «Все» tab from its first page, discarding whatever was accumulated.
         * The one entry point for "the answer this tab is showing may be wrong now" - a filter change,
         * a manual refresh, and the erasure poll all go through it rather than each inventing its own
         * reset. */
        public fun reloadAll() {
            allNextBeforeId = null
            fetchAllPage(beforeId = null)
        }

        /**
         * `26-90`: asks the server to erase one conversation, after the screen's own confirmation
         * dialog has already been accepted. **The row is not removed.**
         *
         * `POST /api/v1/conversations/{id}/erase` answers `202 Accepted`
         * (`RequestConversationErasureHandler`): the request is *recorded*, and a separate job carries
         * the erasure out later. So an optimistic removal here would be a lie with a visible
         * consequence - the row comes back on the next page load, and an operator who watched it vanish
         * reads its return as a bug in the product rather than as the truth about an asynchronous job.
         *
         * What happens instead, and why this is the shape rather than a spinner or a toast:
         *  1. the id joins [erasingIds], and the row renders in a held "стирается" state - visibly not
         *     gone, visibly not ordinary, and no longer openable;
         *  2. the list is re-read immediately, and then every [WAITING_POLL_INTERVAL_MILLIS] while the
         *     tab is showing and at least one row is still held ([startErasurePollIfNeeded]);
         *  3. the row leaves the list on the first answer that no longer contains it - i.e. **when the
         *     server stops returning it**, which is the only moment at which "erased" is actually true.
         *
         * A refusal or a transport failure clears the held state again and surfaces
         * [ConversationListUiState.eraseFailure]; nothing is retried automatically, the same posture
         * [claim] takes for the identical reason.
         */
        public fun confirmErasure(conversationId: String) {
            if (conversationId in erasingIds) return
            erasingIds = erasingIds + conversationId
            mutableState.update { it.copy(eraseFailure = null) }
            renderAll()
            startErasurePollIfNeeded()

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.requestErasure(conversationId) }) {
                    ErasureResult.Accepted -> reloadAll()

                    is ErasureResult.Refused -> {
                        erasingIds = erasingIds - conversationId
                        mutableState.update { it.copy(eraseFailure = EraseFailureUi.ServerRefusal(result.detail)) }
                        renderAll()
                    }

                    is ErasureResult.Failed -> {
                        // The request may or may not have reached the server - but holding the row as
                        // "erasing" on an answer this app never got would be asserting more than it
                        // knows. Released, and the operator is told; the next list answer is the truth
                        // either way.
                        erasingIds = erasingIds - conversationId
                        mutableState.update { it.copy(eraseFailure = EraseFailureUi.Unavailable(result.reason)) }
                        renderAll()
                    }
                }
            }
        }

        /** Dismisses a shown erasure failure. A plain acknowledgement, never a retry - the same rule
         * [dismissClaimError] states for its own half. */
        public fun dismissEraseFailure() {
            if (mutableState.value.eraseFailure == null) return
            mutableState.update { it.copy(eraseFailure = null) }
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

            val isAssignedToMe = lastQueue?.assignedToMe?.any { it.conversationId == conversationId } ?: false
            if (!isAssignedToMe) return

            // `26-30`: found live, after this item's own row rebuild shipped a snippet line that never
            // updated - an operator sent a reply, returned to the list, and read their own words from
            // before the send. The bug was here: this function used to return immediately for anything
            // that was not `VISITOR_AUTHOR_KIND`, which is correct for the unread bump below (an
            // operator's own echoed-back send must never count as unread, `MessageDto.authorKind`'s own
            // doc comment) but wrong for the row's snippet, which has to move for *any* new message -
            // the operator's own included, exactly the way `26-29`'s backend field is defined
            // ("the latest message", not "the latest visitor message"). `refresh()` is this class's own
            // established answer for "something changed, re-ask for the truth" (`onAssigned`'s identical
            // call, right above) rather than hand-rolling a client-side patch of `lastQueue` that would
            // have to duplicate `26-29`'s own truncation/null-for-attachment rules to stay correct.
            if (message.authorKind == VISITOR_AUTHOR_KIND) {
                unreadBumps = unreadBumps + (conversationId to ((unreadBumps[conversationId] ?: 0) + 1))
            }
            refresh()
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

        /**
         * `26-90`: one page of `GET /api/v1/conversations/all`. [beforeId] `null` means the first page,
         * which *replaces* [allRows]; any other value appends.
         *
         * The first-page branch is also where a held "erasing" row is released: every id in
         * [erasingIds] that this answer no longer contains has actually been erased, so it stops being
         * held at the same moment it stops being in the list. Deliberately only on the first page - a
         * *later* page not containing an id says nothing at all about that id (it may simply be on an
         * earlier one), and intersecting against a partial answer would release a row that is still
         * very much there.
         */
        private fun fetchAllPage(beforeId: String?) {
            mutableState.update { it.copy(isLoadingAll = true) }
            val states = mutableState.value.allFilter.map { it.wireState }

            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        api.fetchAllConversations(beforeId = beforeId, pageSize = ALL_PAGE_SIZE, states = states)
                    }

                when (result) {
                    is AllConversationsResult.Loaded -> {
                        allRows =
                            if (beforeId == null) {
                                result.page.conversations
                            } else {
                                allRows + result.page.conversations
                            }
                        allNextBeforeId = result.page.nextBeforeId
                        if (beforeId == null) {
                            val stillListed =
                                result.page.conversations
                                    .map { it.conversationId }
                                    .toSet()
                            erasingIds = erasingIds.intersect(stillListed)
                        }
                        mutableState.update {
                            it.copy(
                                isLoadingAll = false,
                                allHasData = true,
                                allHasMore = result.page.nextBeforeId != null,
                                allLoadError = null,
                            )
                        }
                        renderAll()
                        startErasurePollIfNeeded()
                    }

                    is AllConversationsResult.Failed ->
                        // Whatever is already on screen stays there, exactly as `refresh()` above keeps
                        // a queue that failed to re-read - a network blip must not empty a real list.
                        mutableState.update { it.copy(isLoadingAll = false, allLoadError = result.reason) }
                }
            }
        }

        /** `26-90`: the «Все» list's own projection, kept apart from [render] because the two lists
         * are refreshed by different answers - folding them into one function would make every queue
         * answer re-render a site-wide list it knows nothing about, and vice versa. */
        private fun renderAll() {
            mutableState.update { current -> current.copy(all = allRows.map { it.toRowUi() }) }
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
                hasAttachmentUploadGrant = hasAttachmentUploadGrant,
                lastMessagePreview = lastMessagePreview,
                lastMessageAt = lastMessageAt,
                state = state,
                lastMessageContentKind = lastMessageContentKind,
                messageCount = messageCount,
                operatorName = operatorName,
                isErasing = conversationId in erasingIds,
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

        /**
         * `26-90`: the only poll this item adds, and it is deliberately the narrowest one that can
         * keep [confirmErasure]'s promise. It runs **only** while all three of these hold: «Все» is the
         * selected tab, the screen is in the foreground ([onScreenStarted]/[onScreenStopped]), and at
         * least one row is actually held in the erasing state. The moment the last held row leaves the
         * list, this job stops on its own - there is nothing left to watch for.
         *
         * That is a strictly smaller footprint than [startWaitingPollIfNeeded]'s, which is already the
         * deliberately-narrow one (that method's own doc comment on why a phone does not get the
         * console's always-on timer): this one additionally needs a pending erasure to exist at all,
         * which in ordinary use is a few seconds a few times a day. Same 15-second cadence, reusing
         * that constant rather than choosing a second number - nothing about an erasure job argues for
         * a different one, and two unexplained intervals would be two things to keep in step.
         */
        private fun startErasurePollIfNeeded() {
            val shouldPoll =
                mutableState.value.selectedTab == ConversationListTab.All && erasingIds.isNotEmpty()
            if (!shouldPoll) {
                erasurePollJob?.cancel()
                erasurePollJob = null
                return
            }
            if (erasurePollJob?.isActive == true) return

            erasurePollJob =
                viewModelScope.launch {
                    while (isActive && erasingIds.isNotEmpty()) {
                        delay(WAITING_POLL_INTERVAL_MILLIS)
                        reloadAll()
                    }
                    erasurePollJob = null
                }
        }

        private companion object {
            const val WAITING_POLL_INTERVAL_MILLIS = 15_000L
            const val VISITOR_AUTHOR_KIND = "Visitor"

            /** `26-90`: the same page size `GET /api/v1/conversations/all` defaults to server-side
             * (`ConversationsEndpoints.HandleGetAllForSiteAsync`'s own `pageSize ?? 50`) - sent
             * explicitly rather than left to that default, so this client's own paging arithmetic and
             * the server's agree by construction rather than by a default nobody here can see. */
            const val ALL_PAGE_SIZE = 50
        }
    }
