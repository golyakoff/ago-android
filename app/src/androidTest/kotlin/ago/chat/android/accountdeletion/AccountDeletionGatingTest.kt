package ago.chat.android.accountdeletion

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.Permission
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.shell.AppShellScreen
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-252`: Администрирование → «Удалить аккаунт» is gated on `site:erase`, hide-not-disable — and, unlike
 * most Администрирование rows, not on `site:configure` (`ago-console`'s own `AccountDeletionPage` gates
 * `/account/delete` on its own dedicated `SITE_ERASE_PERMISSION`). Drives the real
 * [AppShellScreen]/[ago.chat.android.shell.MoreScreen] with a marker substituted for Диалоги/Команда — the
 * property under test is entirely inside [ago.chat.android.shell.buildMoreRows]'s own gate, which needs no
 * Hilt component, and the test never opens the row, so [AccountDeletionRoute]'s own `hiltViewModel()` is never
 * reached (the identical boundary [ago.chat.android.siteexport.SiteExportGatingTest]'s own doc comment states).
 *
 * The positive case grants **only** `site:erase` (never `site:configure`) precisely to prove the two gates
 * are independent: the row appears, while none of the `site:configure`-gated rows do.
 *
 * `26-91`/`26-94`: the assertions are plain Russian literals - safe because `LocaleForcingTestRunner` pins
 * every instrumented test's own locale to `ru` (`docs/architecture.md`).
 */
@RunWith(AndroidJUnit4::class)
class AccountDeletionGatingTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun withSiteErase_theDeleteRowAppearsUnderAdministration() {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = OperatorPermissions.Known(setOf(Permission.SITE_ERASE)),
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
        // A `LazyColumn` never composes a semantics node for an item it has not laid out; drive the scroll
        // container until the row is composed rather than looking it up before it exists - the identical
        // shape `SiteExportGatingTest` uses for its own below-the-fold row.
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Удалить аккаунт"))
        composeTestRule.onNodeWithText("Удалить аккаунт").assertExists()
    }

    @Test
    fun withoutSiteErase_theDeleteRowIsHiddenButAdministrationStays() {
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

        // No `site:erase` - «Удалить аккаунт» is gone, but Администрирование itself stays: its two
        // unconditional rows (Операторы и роли, Тариф и оплата) keep the section header on screen, the
        // identical distinction `SiteExportGatingTest`'s own negative case draws.
        composeTestRule.onNodeWithText("Удалить аккаунт").assertDoesNotExist()
        composeTestRule.onNodeWithText("Администрирование", ignoreCase = true).assertExists()
        composeTestRule.onNodeWithText("Операторы и роли").assertExists()
    }
}
