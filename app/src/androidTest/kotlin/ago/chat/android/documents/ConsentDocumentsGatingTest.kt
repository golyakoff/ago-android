package ago.chat.android.documents

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
 * `26-228` (`docs/design/tenant-consent-android.md` §4.1): Администрирование → «Документы согласий» is
 * gated on `site:configure`, hide-not-disable — the identical UX-only client gate
 * [ago.chat.android.automation.CannedResponsesGatingTest]/[ago.chat.android.automation.TagsGatingTest]
 * already prove for their own sibling rows, driving the real
 * [AppShellScreen]/[ago.chat.android.shell.MoreScreen] with a marker substituted for Диалоги/Команда — the
 * property under test is entirely inside [ago.chat.android.shell.buildMoreRows]'s own gate, which needs no
 * Hilt component to exercise, and the test never opens the row, so [ConsentDocumentsRoute]'s own
 * `hiltViewModel()` is never reached.
 *
 * **Unlike its two siblings, Администрирование itself never disappears without `site:configure`** —
 * `ADMINISTRATION_OPERATORS_ROW_ID`/`ADMINISTRATION_BILLING_ROW_ID` are unconditional rows in that same
 * section (`MoreScreen.kt`'s own `buildMoreRows`), so only «Документы согласий» itself is hidden, never
 * the section header. The negative case below asserts the header (and one of its unconditional siblings)
 * survives specifically to keep that distinct from `CannedResponsesGatingTest`'s/`TagsGatingTest`'s own
 * "the whole section disappears" assertion — Автоматизация has no unconditional row of its own, so it
 * disappears entirely once its last gated row does; Администрирование does not.
 *
 * `26-91`/`26-94`: the assertions are plain Russian literals - safe because `LocaleForcingTestRunner`
 * pins every instrumented test's own locale to `ru` (`docs/architecture.md`).
 */
@RunWith(AndroidJUnit4::class)
class ConsentDocumentsGatingTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun withSiteConfigure_theConsentDocumentsRowAppearsUnderAdministration() {
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
        composeTestRule.onNodeWithText("Документы согласий").assertExists()
    }

    @Test
    fun withoutSiteConfigure_theConsentDocumentsRowIsHiddenButAdministrationStays() {
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

        // No `site:configure` - «Документы согласий» is gone, but Администрирование itself stays: its
        // other two rows (Операторы и роли, Тариф и оплата) are unconditional, this file's own doc
        // comment states why this case cannot mirror `CannedResponsesGatingTest`'s/`TagsGatingTest`'s own
        // "the whole section disappears" assertion.
        composeTestRule.onNodeWithText("Документы согласий").assertDoesNotExist()
        composeTestRule.onNodeWithText("Администрирование", ignoreCase = true).assertExists()
        composeTestRule.onNodeWithText("Операторы и роли").assertExists()
    }

    // Deliberately no "opening the row shows the real screen" case here - the identical boundary
    // `CannedResponsesGatingTest`'s own doc comment states: opening the row reaches
    // [ConsentDocumentsRoute]'s own `hiltViewModel()`, which needs a real Hilt component this suite's
    // plain `ComponentActivity` does not provide.
}
