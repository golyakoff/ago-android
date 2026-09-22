package ago.chat.android.conversations

import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-32`: the conversation list's top bar, which the author reported as "still a debug bar".
 *
 * Drives the stateless [ConversationListScreen] directly, with no Hilt component and no view model —
 * the same "route wires, screen renders, a test substitutes its own state" split every other screen in
 * this app follows, and the reason that function is `internal` rather than private.
 *
 * These four cases are deliberately about what the bar *is*, not about how it is drawn: no pixel
 * assertions, no screenshot comparison. The three facts worth locking down are that the debug line is
 * gone, that the connection state still says something a screen reader can read, and that sign-out is
 * no longer one stray tap away on the busiest screen in the app.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ConversationListTopBarTest {
    // `25-214`: the v2 rule — see `BackContractBottomBarTest` for why the original is no longer usable
    // under `allWarningsAsErrors`.
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var signOuts = 0

    private fun renderList(state: OperatorHubConnectionState = OperatorHubConnectionState.Connected) {
        composeTestRule.setContent {
            ConversationListScreen(
                state = ConversationListUiState(hasData = true),
                hubConnectionState = state,
                onTabSelected = {},
                onRefresh = {},
                onClaim = {},
                onDismissClaimError = {},
                onOpenConversation = {},
                onSignOut = { signOuts++ },
            )
        }
    }

    /** The whole of the author's "эта строка занимает ценное вертикальное место": the words are gone. */
    @Test
    fun theConnectionStateNoLongerPrintsItselfOnScreen() {
        renderList()

        composeTestRule.onNodeWithText("Соединение: Подключено").assertDoesNotExist()
        composeTestRule.onNodeWithText("Подключено").assertDoesNotExist()
    }

    /** …but it is still *said*, which is the only reason the `hub_connection_*` strings survived. */
    @Test
    fun theConnectionStateIsStillReadableByAScreenReader() {
        renderList(OperatorHubConnectionState.Disconnected)

        composeTestRule.onNodeWithContentDescription("Соединение: Отключено").assertExists()
    }

    /** Sign-out was the most prominent control on the busiest screen. Now it is behind the ⋮. */
    @Test
    fun signOutIsNotOnTheBarUntilTheOverflowIsOpened() {
        renderList()

        composeTestRule.onNodeWithText("Выйти").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Ещё").performClick()
        composeTestRule.onNodeWithText("Выйти").assertIsDisplayed()
    }

    @Test
    fun choosingSignOutFromTheOverflowSignsOut() {
        renderList()

        composeTestRule.onNodeWithContentDescription("Ещё").performClick()
        composeTestRule.onNodeWithText("Выйти").performClick()

        composeTestRule.waitForIdle()
        assertEquals(1, signOuts)
    }
}
