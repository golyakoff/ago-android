package ago.chat.android.shell

import ago.chat.android.core.domain.conversations.AllConversationsPage
import ago.chat.android.core.domain.conversations.AllConversationsResult
import ago.chat.android.core.domain.conversations.ClaimResult
import ago.chat.android.core.domain.conversations.ConversationListCache
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.ConversationSummary
import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.domain.conversations.ErasureResult
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
import ago.chat.android.thread.ThreadViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * `26-16`: the back-button-contract UI tests' own fakes — the identical shapes
 * `ConversationListViewModelTest`/`ThreadViewModelTest` already use as plain JVM unit tests, reused
 * here so an instrumented Compose test can drive the *real* [ConversationsTabHost] with no Hilt
 * component, no network and no real hub connection in play at all.
 */
internal class FakeConversationsApi(
    var queueResult: QueueResult = QueueResult.Failed(NetworkFailure.Unexpected),
    var claimResult: (String) -> ClaimResult = { ClaimResult.Claimed },
) : ConversationsApi {
    var fetchCalls: Int = 0
        private set
    val claimCalls: MutableList<String> = mutableListOf()

    // `26-80`: recorded, not asserted on by every test that happens to construct a `ThreadViewModel`
    // through this fake - only a test that cares whether mark-read fired needs to read this.
    val markReadCalls: MutableList<Pair<String, Int>> = mutableListOf()

    override suspend fun fetchQueue(): QueueResult {
        fetchCalls++
        return queueResult
    }

    override suspend fun claim(conversationId: String): ClaimResult {
        claimCalls.add(conversationId)
        return claimResult(conversationId)
    }

    override suspend fun markRead(
        conversationId: String,
        upToSequence: Int,
    ): Boolean {
        markReadCalls.add(conversationId to upToSequence)
        return true
    }

    // `26-90`: answers an empty, last page by default. The back-contract tests that use this fake
    // never open the «Все» tab (they have no `site:configure` to be offered it with), so a real
    // page here would be data no assertion reads - but an `error(...)` would turn a future test that
    // does visit that tab into a crash instead of an empty list, which is the wrong failure.
    var allConversationsResult: AllConversationsResult =
        AllConversationsResult.Loaded(AllConversationsPage(conversations = emptyList(), nextBeforeId = null))

    val erasureRequests: MutableList<String> = mutableListOf()

    override suspend fun fetchAllConversations(
        beforeId: String?,
        pageSize: Int,
        states: List<String>,
    ): AllConversationsResult = allConversationsResult

    override suspend fun requestErasure(conversationId: String): ErasureResult {
        erasureRequests.add(conversationId)
        return ErasureResult.Accepted
    }
}

internal class FakeConversationListCache(
    private var cached: ConversationQueue? = null,
) : ConversationListCache {
    override suspend fun read(): ConversationQueue? = cached

    override suspend fun write(queue: ConversationQueue) {
        cached = queue
    }
}

internal class FakeListHubEvents : OperatorHubEvents {
    override val state = MutableStateFlow<OperatorHubConnectionState>(OperatorHubConnectionState.Disconnected)
    override val messages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 16)
    override val allMessages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 16)
    override val assignments = MutableSharedFlow<ConversationAssignedDto>(extraBufferCapacity = 16)
    override val messageDelivered = MutableSharedFlow<MessageDeliveredDto>(extraBufferCapacity = 16)
    override val teamMessages = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 16)
    override val teamMessageRemovals = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 16)

    override suspend fun joinConversation(conversationId: String): HistoryPage = error("not used by the list screen")

    override fun leaveConversation() = error("not used by the list screen")

    override suspend fun loadOlderHistory(
        conversationId: String,
        beforeSequence: Long,
        pageSize: Int,
    ): HistoryPage = error("not used by the list screen")

    override suspend fun getVisitorHistoryConversation(
        conversationId: String,
        historicalConversationId: String,
        beforeSequence: Long?,
        pageSize: Int,
    ): HistoryPage = error("not used by the list screen")

    override suspend fun getConversationHistoryAsSiteConfigureHolder(
        conversationId: String,
        beforeSequence: Long?,
        pageSize: Int,
    ): HistoryPage = error("not used by the list screen")

    override suspend fun sendMessage(
        conversationId: String,
        body: String,
        clientMessageId: String,
        attachmentId: String?,
    ): SendMessageResult = error("not used by the list screen")

    override suspend fun reconnectToActiveSite() = error("not used by the list screen")

    override suspend fun getTeamHistory(
        beforeSequence: Long?,
        pageSize: Int,
    ): TeamHistoryPage = error("not used by the list screen")

    override suspend fun getTeamDelta(afterSequence: Long): TeamHistoryPage = error("not used by the list screen")

    override suspend fun sendTeamMessage(
        body: String,
        clientMessageId: String,
    ): SendMessageResult = error("not used by the list screen")

    override suspend fun removeTeamMessage(teamMessageId: String) = error("not used by the list screen")
}

/** The thread's own connection fake — a fixed history page, no live pushes, no real send. Enough to
 * open a thread and leave it again, which is all clauses 1 and 6 need. */
internal class FakeThreadHubEvents(
    private val page: HistoryPage = HistoryPage(messages = emptyList(), nextBeforeSequence = null),
) : OperatorHubEvents {
    override val state = MutableStateFlow<OperatorHubConnectionState>(OperatorHubConnectionState.Connected)
    override val messages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 16)
    override val allMessages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 16)
    override val assignments = MutableSharedFlow<ConversationAssignedDto>(extraBufferCapacity = 16)
    override val messageDelivered = MutableSharedFlow<MessageDeliveredDto>(extraBufferCapacity = 16)
    override val teamMessages = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 16)
    override val teamMessageRemovals = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 16)

    var leaveCalls: Int = 0
        private set

    override suspend fun joinConversation(conversationId: String): HistoryPage = page

    override fun leaveConversation() {
        leaveCalls++
    }

    override suspend fun loadOlderHistory(
        conversationId: String,
        beforeSequence: Long,
        pageSize: Int,
    ): HistoryPage = HistoryPage()

    // `26-144` added this to `OperatorHubEvents`; the thread-open/leave clauses never call it, so it stays
    // an unused stub like the rest of this fake.
    override suspend fun getVisitorHistoryConversation(
        conversationId: String,
        historicalConversationId: String,
        beforeSequence: Long?,
        pageSize: Int,
    ): HistoryPage = error("not used by the thread screen")

    // `26-98`: unlike [getVisitorHistoryConversation] right above, this one returns the identical
    // fixed [page] [joinConversation] does rather than an unused stub - a read-only open
    // (`ThreadRoute(readOnly = true)`) reaches this instead of [joinConversation], and this fake backs
    // both shapes identically so a future back-contract test exercising the «Все» list's own open does
    // not need a second thread fake.
    override suspend fun getConversationHistoryAsSiteConfigureHolder(
        conversationId: String,
        beforeSequence: Long?,
        pageSize: Int,
    ): HistoryPage = page

    override suspend fun sendMessage(
        conversationId: String,
        body: String,
        clientMessageId: String,
        attachmentId: String?,
    ): SendMessageResult = SendMessageResult.NotConnected

    override suspend fun reconnectToActiveSite() = error("not used by the thread screen")

    override suspend fun getTeamHistory(
        beforeSequence: Long?,
        pageSize: Int,
    ): TeamHistoryPage = error("not used by the thread screen")

    override suspend fun getTeamDelta(afterSequence: Long): TeamHistoryPage = error("not used by the thread screen")

    override suspend fun sendTeamMessage(
        body: String,
        clientMessageId: String,
    ): SendMessageResult = error("not used by the thread screen")

    override suspend fun removeTeamMessage(teamMessageId: String) = error("not used by the thread screen")
}

/**
 * `hiltViewModel()`'s own default scoping ties a `ThreadViewModel`'s lifetime to a `NavBackStackEntry`,
 * which calls `onCleared()` - and therefore cancels `viewModelScope` - when that entry is actually
 * cleared. A `ThreadViewModel` built directly (as every fake-backed instance any back-contract test
 * constructs is) has no such owner, so nothing ever cancels its `viewModelScope` on its own: `remember`
 * alone forgets the old *value* when the key changes, but calls no cleanup callback, unlike
 * `DisposableEffect`. Without this wrapper, an earlier open's `ThreadViewModel` - including its `init`
 * block's own indefinite `hubEvents.state.collect` and any in-flight `flushDraft()` write - keeps
 * running for the rest of the process, which is what raced `BackContractDialogsTabTest`'s own
 * in-memory database being closed by its `tearDown()` before this fix
 * (`java.lang.IllegalStateException: Cannot perform this operation because the connection pool has
 * been closed`, found running that exact test on `ago-test`). Shared here, rather than duplicated per
 * file, because every back-contract test that constructs its own `ThreadViewModel` needs the identical
 * wrapper - also passing a Lint `ViewModelConstructorInComposable` warning that direct construction
 * inside a `@Composable () -> ThreadViewModel` factory lambda would otherwise trip, since the
 * construction here happens inside a plain (non-composable) `factory` lambda instead.
 */
@Composable
internal fun rememberDisposableThreadViewModel(factory: () -> ThreadViewModel): ThreadViewModel {
    val viewModel = remember { factory() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.viewModelScope.cancel() }
    }
    return viewModel
}

internal fun summary(
    id: String,
    visitorName: String? = null,
) = ConversationSummary(
    conversationId = id,
    visitorId = "visitor-$id",
    emojiCreature = "🦊",
    emojiFood = "🍕",
    visitorName = visitorName,
    createdAt = "2026-09-22T09:00:00Z",
    operatorUnreadCount = 0,
)
