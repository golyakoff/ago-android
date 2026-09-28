package ago.chat.android.devicestorage

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
 * `26-256`: Администрирование → «Справка» is gated on `site:configure`, hide-not-disable — the same gate
 * every other `site:configure` Администрирование row uses (`ago-console`'s own `DeviceStorageDisclosurePage`
 * gates `/account/device-storage` on `site:configure`). The gating cases drive the real
 * [AppShellScreen]/[ago.chat.android.shell.MoreScreen] with a marker substituted for Диалоги/Команда — the
 * property under test is entirely inside [ago.chat.android.shell.buildMoreRows]'s own gate, which needs no
 * Hilt component (the identical boundary [ago.chat.android.siteexport.SiteExportGatingTest] draws).
 *
 * Unlike the site-export/consent gating tests, [DeviceStorageRoute] fetches nothing — it is a static
 * reference screen with no view model — so this test *can* open the row and assert the screen renders its
 * disclosure rows and the ready-made privacy snippet, which the data-screen gating tests deliberately do not
 * do (their routes reach a `hiltViewModel()`).
 *
 * `26-91`/`26-94`: the assertions are plain Russian literals - safe because `LocaleForcingTestRunner` pins
 * every instrumented test's own locale to `ru` (`docs/architecture.md`).
 */
@RunWith(AndroidJUnit4::class)
class DeviceStorageDisclosureTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun setShell(permissions: OperatorPermissions) {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = permissions,
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                conversationsTab = { Text("DIALOGI_MARKER") },
                teamTab = { Text("TEAM_MARKER") },
            )
        }
    }

    @Test
    fun withSiteConfigure_theReferenceRowAppearsUnderAdministration() {
        setShell(OperatorPermissions.Known(setOf(Permission.SITE_CONFIGURE)))

        composeTestRule.onNodeWithText("Ещё").performClick()

        composeTestRule.onNodeWithText("Администрирование", ignoreCase = true).assertExists()
        // A `LazyColumn` never composes a semantics node for an item it has not laid out; drive the scroll
        // container until the row is composed rather than looking it up before it exists.
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Справка"))
        composeTestRule.onNodeWithText("Справка").assertExists()
    }

    @Test
    fun withoutSiteConfigure_theReferenceRowIsHidden() {
        setShell(OperatorPermissions.Known(emptySet()))

        composeTestRule.onNodeWithText("Ещё").performClick()

        // No `site:configure` - «Справка» is gone, but Администрирование itself stays: its unconditional
        // rows (Операторы и роли, Тариф и оплата) keep the section header on screen.
        composeTestRule.onNodeWithText("Справка").assertDoesNotExist()
        composeTestRule.onNodeWithText("Администрирование", ignoreCase = true).assertExists()
        composeTestRule.onNodeWithText("Операторы и роли").assertExists()
    }

    @Test
    fun openingTheRow_rendersTheDisclosureRowsAndPrivacySnippet() {
        setShell(OperatorPermissions.Known(setOf(Permission.SITE_CONFIGURE)))

        composeTestRule.onNodeWithText("Ещё").performClick()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Справка"))
        composeTestRule.onNodeWithText("Справка").performClick()

        // The disclosure heading and the not-cookies warning are the top of the static screen.
        composeTestRule.onNodeWithText("Что виджет сохраняет на устройстве посетителя").assertExists()
        composeTestRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText("Рекомендуем вставить этот текст в раздел о приватности вашего сайта."))
        composeTestRule
            .onNodeWithText("Рекомендуем вставить этот текст в раздел о приватности вашего сайта.")
            .assertExists()

        // A disclosure row's own content: the visitor-token key and the fact of what it holds.
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("visitor-token"))
        composeTestRule.onNodeWithText("visitor-token").assertExists()
    }
}
