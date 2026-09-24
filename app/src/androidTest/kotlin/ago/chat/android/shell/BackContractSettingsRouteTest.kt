package ago.chat.android.shell

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.testing.triggerBackPress
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-77`: Настройки is now a real, top-level `NavHost` destination (`AppShellContent`'s own doc
 * comment states the back-stack reasoning in full) rather than a row reachable only from inside Ещё.
 * This suite proves that mechanism directly, with `conversationsTab`/`teamTab` substituted for markers
 * that each expose the `onOpenSettings` callback [AppShellScreen] now hands every tab slot - the
 * identical Hilt-avoidance shape [BackContractBottomBarTest] already uses, extended with one button so
 * the marker can actually exercise the one thing under test here.
 *
 * [BackContractMoreScreenTest]'s own new case proves the *real*
 * [ago.chat.android.ui.components.AccountAvatarAction] on the one screen that needs no Hilt component
 * at all (Ещё); this file proves the *general* shape - that any tab's own `onOpenSettings`, wherever it
 * is called from, lands on the same global route and a single back pop returns to that exact tab, not
 * always to Диалоги.
 *
 * `26-91`/`26-94`: this class's assertions are plain Russian literals - safe because
 * `LocaleForcingTestRunner` pins every instrumented test's own locale to `ru` before any of them run
 * (`docs/architecture.md`, "Pinning the locale instrumented UI tests render against").
 */
@RunWith(AndroidJUnit4::class)
class BackContractSettingsRouteTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val allFiveVisible = OperatorPermissions.Known(emptySet())

    @Test
    fun settingsOpenedFromDialogiReturnsToDialogiOnBack() {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = allFiveVisible,
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                conversationsTab = { onOpenSettings ->
                    Column {
                        Text("DIALOGI_MARKER")
                        TextButton(onClick = onOpenSettings) { Text("OPEN_SETTINGS_FROM_DIALOGI") }
                    }
                },
                teamTab = { Text("TEAM_MARKER") },
                settingsScreen = { _, _ -> Text("SETTINGS_MARKER") },
            )
        }

        composeTestRule.onNodeWithText("OPEN_SETTINGS_FROM_DIALOGI").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SETTINGS_MARKER").assertExists()

        triggerBackPress(composeTestRule)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("SETTINGS_MARKER").assertDoesNotExist()
        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertExists()
    }

    /** The general case: Настройки opened from a tab that is *not* the `NavHost`'s own start
     * destination returns, on back, to that same tab - not to Диалоги - and a second back press then
     * runs clause 3's own rule from there, exactly as if Настройки had never been opened. */
    @Test
    fun settingsOpenedFromTeamReturnsToTeamThenClause3RunsNormally() {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = allFiveVisible,
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                conversationsTab = { Text("DIALOGI_MARKER") },
                teamTab = { onOpenSettings ->
                    Column {
                        Text("TEAM_MARKER")
                        TextButton(onClick = onOpenSettings) { Text("OPEN_SETTINGS_FROM_TEAM") }
                    }
                },
                settingsScreen = { _, _ -> Text("SETTINGS_MARKER") },
            )
        }

        composeTestRule.onNodeWithText("Команда").performClick()
        composeTestRule.onNodeWithText("OPEN_SETTINGS_FROM_TEAM").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SETTINGS_MARKER").assertExists()

        triggerBackPress(composeTestRule)
        composeTestRule.waitForIdle()

        // Back from Настройки lands on Команда, the tab it was opened from - not on Диалоги.
        composeTestRule.onNodeWithText("SETTINGS_MARKER").assertDoesNotExist()
        composeTestRule.onNodeWithText("TEAM_MARKER").assertExists()

        triggerBackPress(composeTestRule)
        composeTestRule.waitForIdle()

        // A second back press runs clause 3 exactly as it always has - Настройки having existed on the
        // stack a moment ago changes nothing about it.
        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertExists()
    }
}
