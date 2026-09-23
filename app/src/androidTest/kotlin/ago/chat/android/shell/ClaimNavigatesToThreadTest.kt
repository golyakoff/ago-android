package ago.chat.android.shell

import ago.chat.android.conversations.ConversationListTab
import ago.chat.android.conversations.ConversationListViewModel
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.QueueResult
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.data.AgoChatDatabase
import ago.chat.android.data.thread.RoomComposerDraftStore
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-75`: "claiming a waiting conversation takes the operator straight into it" — the real
 * [ConversationsTabHost], driven the identical way [AssignmentNeverNavigatesTest] drives it (a real
 * [ConversationListViewModel] over fakes, no Hilt component anywhere in this file). That test proves a
 * *live push* never reaches [ConversationsTabHost]'s own `openConversationId`; this one proves the other
 * half of the same property — a *claim*, the operator's own deliberate act, does, through the identical
 * `onOpenConversation` path [ConversationListRoute] already uses for a tapped row
 * ([ConversationListViewModel.claimedConversations]'s own doc comment).
 */
@RunWith(AndroidJUnit4::class)
class ClaimNavigatesToThreadTest {
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
     * "Иван" starts waiting, unclaimed. The operator switches to «Ожидают», taps **Забрать**, and the
     * test proves three things together: the claim actually happened (`api.claimCalls`), the thread
     * actually opened (a real `ThreadViewModel` was constructed and its composer placeholder is on
     * screen — the same `threadOpened` counter and the same `"Сообщение…"` assertion
     * [AssignmentNeverNavigatesTest] uses to prove the opposite for a live push), and the list
     * underneath switched to «Мои», so a back from this thread lands there rather than back on
     * «Ожидают» where the row no longer belongs.
     */
    @Test
    fun claimingAWaitingConversationSwitchesToMineAndOpensTheThread() {
        val api =
            FakeConversationsApi(
                queueResult =
                    QueueResult.Loaded(
                        ConversationQueue(waiting = listOf(summary("c1", "Иван")), assignedToMe = emptyList()),
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
                        ThreadViewModel(hubEvents = FakeThreadHubEvents(), draftStore = draftStore, ioDispatcher = Dispatchers.IO)
                    }
                },
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { api.fetchCalls >= 1 }
        // The list opens on «Мои» by default (`ConversationListUiState`'s own default) - «Иван» is only
        // waiting so far, so this switch is the same tap an operator would make first in production.
        composeTestRule.onNodeWithText("Ожидают", substring = true).performClick()
        composeTestRule.onNodeWithText("Иван").assertExists()

        composeTestRule.onNodeWithText("Забрать").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { threadOpened > 0 }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Сообщение…").assertExists()

        assertEquals("exactly one claim call was made", listOf("c1"), api.claimCalls)
        assertEquals(
            "the list underneath switched to Мои as part of the same claim, not left on «Ожидают»",
            ConversationListTab.Mine,
            listViewModel.state.value.selectedTab,
        )
    }
}
