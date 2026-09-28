package ago.chat.android.automation

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.Permission
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.shell.AppShellScreen
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-246`: Автоматизация → «ИИ-подсказки» is gated on `site:configure`, hide-not-disable — the identical
 * UX-only client gate every other permission in this app applies (`Permission`'s own doc comment: the
 * server's `IPermissionChecker` is the real refusal, hiding a row here is UX only). Proven by driving the
 * real [AppShellScreen]/[ago.chat.android.shell.MoreScreen] with a marker substituted for Диалоги/Команда
 * — the identical Hilt-free shape [OfflineAutoReplyGatingTest] establishes, since the property under test
 * is entirely inside [ago.chat.android.shell.buildMoreRows]' own gate and never opens the row, so
 * [AiReplyDraftRoute]'s own `hiltViewModel()` is never reached.
 *
 * `26-91`/`26-94`: the assertions are plain Russian literals - safe because `LocaleForcingTestRunner`
 * pins every instrumented test's own locale to `ru` (`docs/architecture.md`).
 */
@RunWith(AndroidJUnit4::class)
class AiSuggestionsGatingTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun withSiteConfigure_theAiSuggestionsRowAppearsUnderAutomation() {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = OperatorPermissions.Known(setOf(Permission.SITE_CONFIGURE)),
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                conversationsTab = { Text("DIALOGI_MARKER") },
                teamTab = { Text("TEAM_MARKER") },
            )
        }

        composeTestRule.onNodeWithText("Ещё").performClick()

        composeTestRule.onNodeWithText("Автоматизация", ignoreCase = true).assertExists()
        composeTestRule.onNodeWithText("ИИ-подсказки").assertExists()
    }

    @Test
    fun withoutSiteConfigure_theAiSuggestionsRowIsHidden() {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = OperatorPermissions.Known(emptySet()),
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                conversationsTab = { Text("DIALOGI_MARKER") },
                teamTab = { Text("TEAM_MARKER") },
            )
        }

        composeTestRule.onNodeWithText("Ещё").performClick()

        // No `site:configure` - the row is gone, and (since every Автоматизация row is gated) the section
        // header is gone too, exactly as `OfflineAutoReplyGatingTest` proves for its own sibling row.
        composeTestRule.onNodeWithText("ИИ-подсказки").assertDoesNotExist()
        composeTestRule.onNodeWithText("Автоматизация", ignoreCase = true).assertDoesNotExist()
    }

    // Deliberately no "opening the row shows the real screen" case here: opening the row reaches
    // `AiReplyDraftRoute`'s own `hiltViewModel()`, which needs a real Hilt component this suite's plain
    // `ComponentActivity` does not provide - the identical boundary `OfflineAutoReplyGatingTest`'s own doc
    // comment states for why its suite never opens its row either.
}
