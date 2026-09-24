package ago.chat.android.conversations

import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.testing.FlakyOnCi
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
 * `26-77` replaced the dot+kebab pair this suite originally proved with the shared
 * [ago.chat.android.ui.components.AccountAvatarAction] — this file is updated in place rather than
 * retired, since its four underlying facts (no debug line, the connection state stays readable, sign-out
 * stays behind a tap rather than sitting on the bar, and that tap actually signs out) are exactly the
 * same four facts `26-77`'s own Done-when still requires, just reached through the new composable.
 *
 * Drives the stateless [ConversationListScreen] directly, with no Hilt component and no view model —
 * the same "route wires, screen renders, a test substitutes its own state" split every other screen in
 * this app follows, and the reason that function is `internal` rather than private.
 */
@OptIn(ExperimentalTestApi::class)
// `26-91`: this class asserts Russian text as a literal; the CI emulator boots English and cannot be
// forced to `ru-RU` by any mechanism found so far (`ci.yml`'s own comment has the detail). `26-94`
// tracks the real fix.
@FlakyOnCi
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
                operatorDisplayName = "Андрей Голяков",
                operatorEmail = "andrey@example.com",
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

    /** …but it is still *said*, which is the only reason the `hub_connection_*` strings survived - now
     * spoken by the avatar's own presence dot rather than by a free-standing [ago.chat.android.ui.components.HubConnectionDot]. */
    @Test
    fun theConnectionStateIsStillReadableByAScreenReader() {
        renderList(OperatorHubConnectionState.Disconnected)

        composeTestRule.onNodeWithContentDescription("Соединение: Отключено").assertExists()
    }

    /** Sign-out was the most prominent control on the busiest screen. Now it is behind the avatar. */
    @Test
    fun signOutIsNotOnTheBarUntilTheAccountMenuIsOpened() {
        renderList()

        composeTestRule.onNodeWithText("Выйти").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Меню аккаунта").performClick()
        composeTestRule.onNodeWithText("Выйти").assertIsDisplayed()
    }

    @Test
    fun choosingSignOutFromTheAccountMenuSignsOut() {
        renderList()

        composeTestRule.onNodeWithContentDescription("Меню аккаунта").performClick()
        composeTestRule.onNodeWithText("Выйти").performClick()

        composeTestRule.waitForIdle()
        assertEquals(1, signOuts)
    }

    /** `26-77`'s own new promise for this bar: Настройки is reachable from it, with a chevron, and
     * calls back through [ConversationListScreen]'s own `onOpenSettings` - never a second sign-out
     * pathway invented beside it. */
    @Test
    fun choosingSettingsFromTheAccountMenuCallsOnOpenSettings() {
        var settingsOpened = false
        composeTestRule.setContent {
            ConversationListScreen(
                state = ConversationListUiState(hasData = true),
                hubConnectionState = OperatorHubConnectionState.Connected,
                onTabSelected = {},
                onRefresh = {},
                onClaim = {},
                onDismissClaimError = {},
                onOpenConversation = {},
                onSignOut = {},
                onOpenSettings = { settingsOpened = true },
            )
        }

        composeTestRule.onNodeWithContentDescription("Меню аккаунта").performClick()
        composeTestRule.onNodeWithText("Настройки").performClick()

        composeTestRule.waitForIdle()
        assertEquals(true, settingsOpened)
    }
}
