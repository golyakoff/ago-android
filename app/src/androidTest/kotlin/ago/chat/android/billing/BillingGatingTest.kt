package ago.chat.android.billing

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
 * `26-301`: Администрирование → «Тариф и оплата» is gated on `site:configure`, hide-not-disable - the
 * identical UX-only client gate every other Администрирование/Автоматизация row applies
 * ([ago.chat.android.automation.AiSuggestionsGatingTest]'s own doc comment: the server's own
 * `IPermissionChecker` is the real refusal, hiding a row here is UX only). Proven by driving the real
 * [AppShellScreen]/[ago.chat.android.shell.MoreScreen] with a marker substituted for Диалоги/Команда - the
 * identical Hilt-free shape [ago.chat.android.automation.OfflineAutoReplyGatingTest] establishes, since
 * the property under test is entirely inside [ago.chat.android.shell.buildMoreRows]'s own gate and never
 * opens the row, so [BillingRoute]'s own `hiltViewModel()` is never reached.
 *
 * `26-91`/`26-94`: the assertions are plain Russian literals - safe because `LocaleForcingTestRunner`
 * pins every instrumented test's own locale to `ru` (`docs/architecture.md`).
 */
@RunWith(AndroidJUnit4::class)
class BillingGatingTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun withSiteConfigure_theBillingRowAppearsUnderAdministration() {
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

        composeTestRule.onNodeWithText("Администрирование", ignoreCase = true).assertExists()
        composeTestRule.onNodeWithText("Тариф и оплата").assertExists()
    }

    @Test
    fun withoutSiteConfigure_theBillingRowIsHidden() {
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

        // No `site:configure` - the row is gone, exactly as `BackContractMoreScreenTest` proves alongside
        // it (that suite's own `theMoreListShowsTheNewAutomationAndAdministrationRowsAndNoSettingsRow`).
        composeTestRule.onNodeWithText("Тариф и оплата").assertDoesNotExist()
    }

    // Deliberately no "opening the row shows the real screen" case here: opening the row reaches
    // `BillingRoute`'s own `hiltViewModel()`, which needs a real Hilt component this suite's plain
    // `ComponentActivity` does not provide - the identical boundary `AiSuggestionsGatingTest`'s own doc
    // comment states for why its suite never opens its row either.
}
