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
 * `26-225` (`docs/design/tenant-canned-tags-android.md` §3.3): Автоматизация → «Метки» is gated on
 * `site:configure`, hide-not-disable — the identical UX-only client gate
 * [ago.chat.android.automation.CannedResponsesGatingTest] already proves for its own sibling row, driving
 * the real [AppShellScreen]/[ago.chat.android.shell.MoreScreen] with a marker substituted for
 * Диалоги/Команда so [TagsRoute]'s own `hiltViewModel()` is never reached (the property under test is
 * entirely inside [ago.chat.android.shell.buildMoreRows]'s own gate).
 *
 * `26-91`/`26-94`: the assertions are plain Russian literals - safe because `LocaleForcingTestRunner`
 * pins every instrumented test's own locale to `ru` (`docs/architecture.md`).
 */
@RunWith(AndroidJUnit4::class)
class TagsGatingTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun withSiteConfigure_theTagsRowAppearsUnderAutomation() {
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
        composeTestRule.onNodeWithText("Метки").assertExists()
    }

    @Test
    fun withoutSiteConfigure_theTagsRowIsHidden() {
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

        // No `site:configure` - «Метки» is gone, and (the identical reasoning
        // `CannedResponsesGatingTest`'s own doc comment gives) Автоматизация itself is gone too, since
        // every row in that section shares this gate.
        composeTestRule.onNodeWithText("Метки").assertDoesNotExist()
        composeTestRule.onNodeWithText("Автоматизация", ignoreCase = true).assertDoesNotExist()
    }

    // Deliberately no "opening the row shows the real screen" case here - the identical boundary
    // `CannedResponsesGatingTest`'s own doc comment states: opening the row reaches [TagsRoute]'s own
    // `hiltViewModel()`, which needs a real Hilt component this suite's plain `ComponentActivity` does not
    // provide.
}
