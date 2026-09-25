package ago.chat.android.channels

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
 * `26-159`: the Каналы → «Установка виджета» row is gated on `site:configure`, hide-not-disable — the
 * identical UX-only client gate every other permission in this app applies (`Permission`'s own doc
 * comment: the server's `IPermissionChecker` is the real refusal, hiding a control here is UX only).
 * Proven by driving the real [AppShellScreen]/[ago.chat.android.shell.MoreScreen] with a marker
 * substituted for Диалоги/Команда (the identical Hilt-free shape `BackContractMoreScreenTest` uses) — the
 * property under test is entirely inside `buildMoreRows`' own gate, which needs no Hilt component to
 * exercise, and the test never opens the row, so [InstallWidgetRoute]'s own `hiltViewModel()` is never
 * reached.
 *
 * `26-91`/`26-94`: the assertions are plain Russian literals - safe because `LocaleForcingTestRunner`
 * pins every instrumented test's own locale to `ru` (`docs/architecture.md`). «Каналы» is a `SectionLabel`,
 * which uppercases at render time, so it is matched `ignoreCase = true`; «Установка виджета» is a plain
 * `Text` row and matches verbatim.
 */
@RunWith(AndroidJUnit4::class)
class WidgetInstallGatingTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun withSiteConfigure_theInstallWidgetRowAppearsUnderChannels() {
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

        composeTestRule.onNodeWithText("Каналы", ignoreCase = true).assertExists()
        composeTestRule.onNodeWithText("Установка виджета").assertExists()
    }

    /**
     * The stateless [InstallWidgetScreen] rendered with a fake [InstallWidgetUiState.Loaded] - no
     * [InstallWidgetViewModel], no `hiltViewModel()`, no Hilt component at all. This is the "provide a
     * fake, not rely on Hilt" half: the gating tests never open the route (so they never reach the
     * Route's own `hiltViewModel()` default), and this one exercises the screen's body directly, keeping
     * the whole suite renderable under a plain `ComponentActivity`.
     */
    @Test
    fun theLoadedScreenRendersTheEmbedSnippetAndTheAllowedOrigins() {
        composeTestRule.setContent {
            InstallWidgetScreen(
                state =
                    InstallWidgetUiState.Loaded(
                        embedSnippet = "<script src=\"https://api.example/widget/widget.js\" data-site=\"pk_abc\" async></script>",
                        allowedOrigins = listOf("https://shop.example"),
                    ),
                onRetry = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("data-site=\"pk_abc\"", substring = true).assertExists()
        composeTestRule.onNodeWithText("https://shop.example").assertExists()
    }

    @Test
    fun withoutSiteConfigure_theInstallWidgetRowIsHidden() {
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

        // No `site:configure` - neither the Каналы header nor its one row is drawn (a section with no
        // rows is not returned at all, `buildMoreSections`).
        composeTestRule.onNodeWithText("Установка виджета").assertDoesNotExist()
        composeTestRule.onNodeWithText("Каналы", ignoreCase = true).assertDoesNotExist()
    }
}
