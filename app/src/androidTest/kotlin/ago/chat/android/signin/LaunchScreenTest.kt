package ago.chat.android.signin

import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-45`: the launch screen's own promise, driven through `SignInHost` directly - the same
 * "route wires, screen renders" split `ConversationListRowTest` already establishes. This checks
 * text content only, never pixel position - `ConversationListRowTest`'s own doc comment states why
 * (Compose UI tests have no reliable way to assert "this sits left of that" without a screenshot
 * harness this project does not have, `docs/conventions/testing.md`); the left-aligned, full-width
 * layout is instead checked against the mockup on a real device, this item's own Done-when.
 */
@RunWith(AndroidJUnit4::class)
class LaunchScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun renderLaunchScreen() {
        composeTestRule.setContent {
            SignInHost(
                state = SignInUiState.SignedOut,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                consoleUrl = "",
                onSignIn = {},
                onChooseSite = {},
                onRetry = {},
                onSignOut = {},
                onOpenConsole = {},
            )
        }
    }

    /** The wordmark is the new, shorter display string - never the full launcher label. */
    @Test
    fun theWordmarkRendersNotTheFullAppName() {
        renderLaunchScreen()

        composeTestRule.onNodeWithText("AGO").assertExists()
        composeTestRule.onNodeWithText("AGO Chat").assertDoesNotExist()
    }

    /** The tagline is the mockup's copy - never the string it replaced. */
    @Test
    fun theTaglineReadsTheMockupsCopy() {
        renderLaunchScreen()

        composeTestRule.onNodeWithText("Чат и записи для вашего сайта").assertExists()
        composeTestRule.onNodeWithText("Отвечайте посетителям, где бы вы ни были").assertDoesNotExist()
    }

    /** `26-45`'s own Out of scope: no second button, and no deployment name anywhere on the screen. */
    @Test
    fun thereIsNoRegistrationButtonAndNoDeploymentName() {
        renderLaunchScreen()

        composeTestRule.onNodeWithText("Создать аккаунт").assertDoesNotExist()
        composeTestRule.onNodeWithText("http", substring = true).assertDoesNotExist()
    }

    /** The single button is still the sign-in action. */
    @Test
    fun theSignInButtonStillRenders() {
        renderLaunchScreen()

        composeTestRule.onNodeWithText("Войти").assertExists()
    }
}
