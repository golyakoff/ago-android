package ago.chat.android.faq

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
 * `26-199`/`M1` (`docs/design/tenant-modules-restrictions-android.md` §3.3): Автоматизация → «База
 * знаний» is gated on `site:configure`, hide-not-disable — the identical UX-only client gate
 * [ago.chat.android.automation.TagsGatingTest] already proves for its own sibling row, driving the real
 * [AppShellScreen]/[ago.chat.android.shell.MoreScreen] with a marker substituted for Диалоги/Команда so
 * [ModulesFaqRoute]'s own `hiltViewModel()` is never reached (the property under test is entirely inside
 * [ago.chat.android.shell.buildMoreRows]'s own gate).
 *
 * `26-91`/`26-94`: the assertions are plain Russian literals - safe because `LocaleForcingTestRunner`
 * pins every instrumented test's own locale to `ru` (`docs/architecture.md`).
 */
@RunWith(AndroidJUnit4::class)
class ModulesFaqGatingTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun withSiteConfigure_theKnowledgeBaseRowAppearsUnderAutomation() {
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
        composeTestRule.onNodeWithText("База знаний").assertExists()
    }

    @Test
    fun withoutSiteConfigure_theKnowledgeBaseRowIsHidden() {
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

        // No `site:configure` - «База знаний» is gone, and (the identical reasoning
        // `TagsGatingTest`'s own doc comment gives) Автоматизация itself is gone too, since every row in
        // that section shares this gate.
        composeTestRule.onNodeWithText("База знаний").assertDoesNotExist()
        composeTestRule.onNodeWithText("Автоматизация", ignoreCase = true).assertDoesNotExist()
    }

    // Deliberately no "opening the row shows the real screen" case here - the identical boundary
    // `TagsGatingTest`'s own doc comment states: opening the row reaches [ModulesFaqRoute]'s own
    // `hiltViewModel()`, which needs a real Hilt component this suite's plain `ComponentActivity` does
    // not provide.
}
