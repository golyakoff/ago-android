package ago.chat.android.shell

import ago.chat.android.conversations.ConversationListViewModel
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.QueueResult
import ago.chat.android.core.network.realtime.ConversationAssignedDto
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.data.AgoChatDatabase
import ago.chat.android.data.thread.RoomComposerDraftStore
import ago.chat.android.testing.triggerBackPress
import ago.chat.android.thread.ThreadViewModel
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-174`: opening a conversation from a push tap must clear the list's own «Новое» pill the identical
 * way an in-app row tap does. [AssignmentNeverNavigatesTest] already proves a *live push notification of
 * assignment* (`ConversationAssignedDto`, over [FakeListHubEvents]) sets [ConversationListViewModel]'s own
 * `isNewlyAssigned` badge and never navigates; this test starts from that identical state — a row already
 * carrying «Новое» — and drives the *other* kind of push this app has: a **notification tap**, delivered
 * exactly how `MainActivity.handleIntent` delivers it in production, through the real
 * [PendingConversationOpener] singleton rather than a fake. [PendingConversationOpenerEntryPoint]'s own
 * doc comment states this resolves against the real, live `AgoChatApplication` Hilt graph regardless of
 * this file constructing every other collaborator directly — the same "no Hilt component in this file"
 * rig [AssignmentNeverNavigatesTest]/[BackContractDialogsTabTest] already use, extended to the one
 * component this rig had never actually driven before this item.
 *
 * Before `26-174`'s fix, [ConversationsTabHost]'s own push-open `LaunchedEffect` set `openConversationId`
 * directly and never called [ConversationListViewModel.onRowOpened] — the call
 * [ConversationListRoute]'s wrapped `onOpenConversation` makes for every ordinary row tap
 * (`ConversationListScreen.kt`'s own doc comment) — so a push-opened conversation's «Новое» pill (and its
 * unread count, the identical [ConversationListViewModel.locallyReadIds] overlay) survived the round trip
 * to the thread and back. This test fails against that old behaviour (the pill is still there after
 * [triggerBackPress]) and passes once the push path calls [ConversationListViewModel.onRowOpened] too.
 *
 * `26-91`/`26-94`: this class's assertions are plain Russian literals - safe because
 * `LocaleForcingTestRunner` pins every instrumented test's own locale to `ru` before any of them run
 * (`docs/architecture.md`, "Pinning the locale instrumented UI tests render against").
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PushOpenAppliesListSideEffectsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: AgoChatDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AgoChatDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * "Иван" starts already assigned. A live `ConversationAssigned` push marks the row «Новое», exactly
     * [AssignmentNeverNavigatesTest] proves. Then, instead of a click, a **notification tap** is simulated
     * by writing straight into [PendingConversationOpener] — the identical call `MainActivity.handleIntent`
     * makes — and the test asserts the thread this app already knows how to open (via
     * [PendingConversationOpener]'s own `LaunchedEffect` in [ConversationsTabHost]) also carries the list's
     * on-open side effect: once the operator backs out, «Новое» is gone, not merely deferred to a re-fetch
     * that may or may not happen to land before the operator looks again.
     */
    @Test
    fun pushOpenClearsTheNewBadgeTheSameWayARowTapDoes() {
        val api =
            FakeConversationsApi(
                queueResult =
                    QueueResult.Loaded(
                        ConversationQueue(waiting = emptyList(), assignedToMe = listOf(summary("c1", "Иван"))),
                    ),
            )
        val hubEvents = FakeListHubEvents()
        val listViewModel =
            ConversationListViewModel(
                api = api,
                cache = FakeConversationListCache(),
                hubEvents = hubEvents,
                ioDispatcher = Dispatchers.IO,
            )
        val draftStore = RoomComposerDraftStore(database.composerDraftDao())

        composeTestRule.setContent {
            ConversationsTabHost(
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onSignOut = {},
                viewModel = listViewModel,
                threadViewModel = {
                    rememberDisposableThreadViewModel {
                        ThreadViewModel(
                            hubEvents = FakeThreadHubEvents(),
                            draftStore = draftStore,
                            conversationsApi = api,
                            ioDispatcher = Dispatchers.IO,
                        )
                    }
                },
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { api.fetchCalls >= 1 }
        composeTestRule.onNodeWithText("Иван").assertExists()

        // The live assignment push - the identical event `AssignmentNeverNavigatesTest` drives - is what
        // puts «Новое» on this row in the first place, and re-fetches the queue the same way a real
        // reconnect/re-assignment would.
        val fetchesBeforeAssignment = api.fetchCalls
        hubEvents.assignments.tryEmit(ConversationAssignedDto("c1", "op-1", "2026-09-22T10:00:00Z"))
        composeTestRule.waitUntil(timeoutMillis = 5_000) { api.fetchCalls > fetchesBeforeAssignment }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Новое").assertExists()

        // The notification tap itself - `MainActivity.handleIntent`'s own call, reached here through the
        // real singleton rather than a fake, per this file's own doc comment.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pendingConversationOpener =
            EntryPointAccessors
                .fromApplication(context, PendingConversationOpenerEntryPoint::class.java)
                .pendingConversationOpener()
        pendingConversationOpener.open("c1")

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Сообщение…").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()

        triggerBackPress(composeTestRule)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Иван").assertExists()
        composeTestRule.onNodeWithText("Новое").assertDoesNotExist()
    }
}
