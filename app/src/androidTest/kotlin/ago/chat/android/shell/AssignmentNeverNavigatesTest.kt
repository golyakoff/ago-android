package ago.chat.android.shell

import ago.chat.android.conversations.ConversationListViewModel
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.QueueResult
import ago.chat.android.core.network.realtime.ConversationAssignedDto
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.data.AgoChatDatabase
import ago.chat.android.data.thread.RoomComposerDraftStore
import ago.chat.android.testing.FlakyOnCi
import ago.chat.android.thread.ThreadViewModel
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-20`: `docs/navigation.md`'s "a new assignment arriving never navigates" — a badge and a
 * re-fetch, never a screen moving under the operator. `ConversationListViewModelTest`'s own
 * `a ConversationAssigned push marks the row New and re-fetches, and never navigates` case already
 * proves this at the [ConversationListViewModel] level, by asserting `selectedTab` is unchanged - the
 * only navigation-shaped field that class exposes. This is the same rule proven one layer up, driving
 * the real [ConversationsTabHost] the way `BackContractDialogsTabTest` drives it for back-contract
 * clauses 1 and 6: a real [ConversationListViewModel] over fakes, composed exactly the way
 * [AppShellScreen] wires it in production, no Hilt component anywhere in this file. What clause-1/6's
 * own rig cannot show is the property this test is for: [ConversationsTabHost] is the composable that
 * actually *owns* the "list or thread" decision (its own `openConversationId`), and the only path that
 * ever sets it is [ConversationListRoute]'s `onOpenConversation` - a live hub push never calls it.
 *
 * `26-91`: this class asserts Russian text as a literal; the CI emulator boots English and cannot be
 * forced to `ru-RU` by any mechanism found so far (`ci.yml`'s own comment has the detail). `26-94`
 * tracks the real fix.
 */
@FlakyOnCi
@RunWith(AndroidJUnit4::class)
class AssignmentNeverNavigatesTest {
    // `25-214`: the v2 rule — see `BackContractBottomBarTest` for why the original is no longer
    // usable under `allWarningsAsErrors`, and what changes underneath.
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
     * "Иван" starts already assigned and on screen. A live `ConversationAssigned` push naming the
     * same conversation arrives - the shape a re-assignment or a reconnect replay takes in production
     * ([ConversationListViewModel.onAssigned]'s own doc comment) - and the test proves three things
     * together: the push was real (it re-fetched and the row now carries the "Новое" badge, so this is
     * not a false negative from the event never having been observed), the thread never opened (no
     * composer text, and [threadOpened] - counting how many times [ConversationsTabHost]'s own
     * `threadViewModel` factory ran - stays zero), and the rig genuinely *can* navigate (a real click on
     * the same row afterwards does open the thread), so the negative result above is not merely because
     * nothing here is capable of it.
     */
    @Test
    fun aLiveAssignmentNeverOpensTheThread() {
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
        var threadOpened = 0

        composeTestRule.setContent {
            ConversationsTabHost(
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onSignOut = {},
                viewModel = listViewModel,
                threadViewModel = {
                    rememberDisposableThreadViewModel {
                        threadOpened++
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

        val fetchesBeforePush = api.fetchCalls
        hubEvents.assignments.tryEmit(ConversationAssignedDto("c1", "op-1", "2026-09-22T10:00:00Z"))

        composeTestRule.waitUntil(timeoutMillis = 5_000) { api.fetchCalls > fetchesBeforePush }
        composeTestRule.waitForIdle()

        // The push actually happened and was actually handled - otherwise the next two assertions
        // would pass for the wrong reason (nothing occurred at all).
        composeTestRule.onNodeWithText("Новое").assertExists()

        // The whole point: still the list, never the thread.
        composeTestRule.onNodeWithText("Сообщение…").assertDoesNotExist()
        assertEquals(
            "a live assignment must never construct a ThreadViewModel - that only ever happens by opening a row",
            0,
            threadOpened,
        )

        // Proof the rig is not simply incapable of navigating: an actual click on the same row does
        // open the thread, right after the push that must not have.
        composeTestRule.onNodeWithText("Иван").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Сообщение…").assertExists()
        assertTrue("clicking the row does open the thread, unlike the live push above", threadOpened > 0)
    }
}
