package ago.chat.android.conversations

import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `docs/backlog/26-67-*.md`'s own Found: scroll down «Мои», tap «Ожидают», tap «Мои» again — before
 * this item, that returned to the top. Driven through the stateless [ConversationListScreen] directly,
 * the same "route wires, screen renders, a test substitutes its own state" split
 * `ConversationListTopBarTest`/`ConversationListRowTest` already establish — except `selectedTab` is
 * held in a local `remember { mutableStateOf(...) }` here rather than fixed for the test's lifetime,
 * because [ConversationListRoute] itself only ever hands this screen a value and a callback
 * ([ConversationListViewModel.onTabSelected]); a test that actually exercises a tab switch has to be
 * the thing that plays the view model's part.
 *
 * This exercises the fix at the seam it actually lives at — [ConversationListScreen]'s own
 * `listStateHolder` (a [androidx.compose.runtime.saveable.SaveableStateHolder], one
 * `SaveableStateProvider` key per [ConversationListTab]) — rather than the full
 * `ConversationsTabHost` + `ConversationListViewModel` stack `BackContractDialogsTabTest` uses for the
 * sibling "list vs. thread" case: nothing about *that* case changed here, and this fix does not touch the
 * view model at all.
 */
@OptIn(ExperimentalTestApi::class)
// `26-91`/`26-94`: this class's assertions are plain Russian literals - safe because
// `LocaleForcingTestRunner` pins every instrumented test's own locale to `ru` before any of them run
// (`docs/architecture.md`, "Pinning the locale instrumented UI tests render against").
@RunWith(AndroidJUnit4::class)
class ConversationListTabScrollPositionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // 40 rows on each tab - comfortably more than a phone screen shows at once, so scrolling to the
    // 30th genuinely moves the first row off screen rather than merely being a no-op on a short list.
    private fun rows(prefix: String): List<ConversationRowUi> =
        (0 until ROW_COUNT).map { index ->
            ConversationRowUi(
                conversationId = "$prefix-$index",
                visitorId = "visitor-$prefix-$index",
                emojiCreature = null,
                emojiFood = null,
                visitorName = "$prefix row $index",
                createdAt = "2026-09-22T09:00:00Z",
                unreadCount = 0,
            )
        }

    @Test
    fun switchingTabsAndBackKeepsEachTabsOwnScrollPosition() {
        val mineRows = rows("mine")
        val waitingRows = rows("waiting")

        composeTestRule.setContent {
            var selectedTab by remember { mutableStateOf(ConversationListTab.Mine) }
            ConversationListScreen(
                state =
                    ConversationListUiState(
                        hasData = true,
                        selectedTab = selectedTab,
                        mine = mineRows,
                        waiting = waitingRows,
                    ),
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onTabSelected = { selectedTab = it },
                onRefresh = {},
                onClaim = {},
                onDismissClaimError = {},
                onOpenConversation = {},
                onSignOut = {},
            )
        }

        // Scroll «Мои» well past its top.
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(SCROLLED_TO_INDEX)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("mine row 0").assertDoesNotExist()
        composeTestRule.onNodeWithText("mine row $SCROLLED_TO_INDEX").assertExists()

        // Switch to «Ожидают» and back - the segmented control's own label carries a trailing count
        // (`segmentedTabLabel`), hence `substring = true` rather than an exact match.
        composeTestRule.onNodeWithText("Ожидают", substring = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Мои", substring = true).performClick()
        composeTestRule.waitForIdle()

        // «Мои» is back exactly where it was left, not reset to the top.
        composeTestRule.onNodeWithText("mine row 0").assertDoesNotExist()
        composeTestRule.onNodeWithText("mine row $SCROLLED_TO_INDEX").assertExists()
    }

    private companion object {
        const val ROW_COUNT = 40
        const val SCROLLED_TO_INDEX = 30
    }
}
