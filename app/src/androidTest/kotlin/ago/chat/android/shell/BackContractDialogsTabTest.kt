package ago.chat.android.shell

import ago.chat.android.conversations.ConversationListViewModel
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.QueueResult
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-16`, back-button contract clauses 1 and 6 — both live inside the Диалоги tab's own
 * [ConversationsTabHost], so they share one rig: a real [ConversationListViewModel] over fakes and a
 * real [ThreadViewModel] over a real, in-memory Room database ([RoomComposerDraftStore]), composed
 * exactly the way [ago.chat.android.shell.AppShellScreen] wires them in production — no Hilt component
 * anywhere in this file, per [ConversationsTabHost]'s own doc comment on why both its view-model
 * parameters are overridable.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BackContractDialogsTabTest {
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
     * Clause 1: "back from a thread returns to the list, keeping scroll position and filters."
     * Proven here as: opening «Иван»'s thread and pressing back does not trigger a second
     * [FakeConversationsApi.fetchQueue] call — the list's own [ConversationListViewModel] instance,
     * and everything it already fetched, survives the round trip through the thread rather than being
     * torn down and rebuilt, which is the property [rememberSaveableStateHolder] exists to guarantee
     * ([ConversationsTabHost]'s own doc comment).
     */
    @Test
    fun clause1_backFromThreadReturnsToTheListWithoutRefetching() {
        val api =
            FakeConversationsApi(
                queueResult = QueueResult.Loaded(ConversationQueue(waiting = emptyList(), assignedToMe = listOf(summary("c1", "Иван")))),
            )
        val listViewModel =
            ConversationListViewModel(
                api = api,
                cache = FakeConversationListCache(),
                hubEvents = FakeListHubEvents(),
                ioDispatcher = Dispatchers.IO,
            )
        val threadViewModel =
            ThreadViewModel(
                hubEvents = FakeThreadHubEvents(),
                draftStore = RoomComposerDraftStore(database.composerDraftDao()),
                ioDispatcher = Dispatchers.IO,
            )

        composeTestRule.setContent {
            ConversationsTabHost(
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onSignOut = {},
                viewModel = listViewModel,
                threadViewModel = { rememberDisposableThreadViewModel { threadViewModel } },
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { api.fetchCalls >= 1 }
        val fetchesBeforeOpening = api.fetchCalls
        composeTestRule.onNodeWithText("Иван").performClick()
        composeTestRule.waitForIdle()

        triggerBackPress(composeTestRule)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Иван").assertExists()
        assertEquals(
            "back from the thread must not have re-fetched the queue - the same list state is what's showing again",
            fetchesBeforeOpening,
            api.fetchCalls,
        )
    }

    /**
     * Clause 6: "back never discards a composer draft silently." The draft is typed, back is pressed
     * (not the app bar's own arrow — the system gesture, to prove the *system* back path also flushes
     * it, not only an explicit tap), and the same conversation is re-opened to read the draft back from
     * the real, on-disk [RoomComposerDraftStore] — proving `ThreadViewModel.close()`'s own
     * `flushDraft()` call actually ran rather than being skipped by whichever path system back takes.
     */
    @Test
    fun clause6_backNeverDiscardsANonEmptyComposerDraft() {
        val api =
            FakeConversationsApi(
                queueResult = QueueResult.Loaded(ConversationQueue(waiting = emptyList(), assignedToMe = listOf(summary("c1", "Мария")))),
            )
        val listViewModel =
            ConversationListViewModel(
                api = api,
                cache = FakeConversationListCache(),
                hubEvents = FakeListHubEvents(),
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
                        ThreadViewModel(hubEvents = FakeThreadHubEvents(), draftStore = draftStore, ioDispatcher = Dispatchers.IO)
                    }
                },
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { api.fetchCalls >= 1 }
        composeTestRule.onNodeWithText("Мария").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Напишите сообщение…").performTextInput("Уже смотрю ваш заказ")
        composeTestRule.waitForIdle()

        triggerBackPress(composeTestRule)
        composeTestRule.waitForIdle()

        // `flushDraft()`'s own write runs on `viewModelScope` - fire-and-forget from this test's own
        // point of view - and Room's suspend DAO methods hop onto Room's *own* internal executor
        // beneath whatever dispatcher wraps the call, so `waitForIdle()` above (which only settles
        // Compose's recomposition, not an arbitrary background write) is not a guarantee the write has
        // actually landed yet. Polling the store directly - the real source of truth clause 6 is about
        // - is what makes the next step deterministic instead of racing this test's own `tearDown()`
        // against a write that has not committed yet.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { draftStore.read("c1") } == "Уже смотрю ваш заказ"
        }

        // Re-open the identical conversation and read the draft back through the real screen, exactly
        // as a fresh `ThreadViewModel.open("c1")` does in production.
        composeTestRule.onNodeWithText("Мария").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Уже смотрю ваш заказ").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Уже смотрю ваш заказ").assertExists()

        // Leave the thread open at the end of a test method is what caused the crash this fix answers:
        // `composeTestRule`'s own `@Rule` teardown runs *after* this class's `@After tearDown()` - so
        // closing the Activity for real afterwards drives it through a genuine `onStop()`, which
        // `ThreadRoute`'s own lifecycle observer reads as "flush the draft" for whichever
        // `ThreadViewModel` is still mounted. With a thread left open, that flush fired against this
        // test's own `AgoChatDatabase` *after* it was already closed
        // (`java.lang.IllegalStateException: ... connection pool has been closed`, found running this
        // exact test on `ago-test`). Closing the thread here, inside the test itself, is what makes
        // this test's own database lifecycle actually contain everything that touches it.
        triggerBackPress(composeTestRule)
        composeTestRule.waitForIdle()
        // The same "wait for the real write, not just for Compose to settle" reasoning as above -
        // this closing flush must also be allowed to land before `tearDown()` closes the database.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { draftStore.read("c1") } == "Уже смотрю ваш заказ"
        }
    }
}
