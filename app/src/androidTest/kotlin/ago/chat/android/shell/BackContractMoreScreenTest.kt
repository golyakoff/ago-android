package ago.chat.android.shell

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.testing.triggerBackPress
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-16`, back-button contract clause 2: "back from any Ещё screen returns to the Ещё list, not to
 * the previous bottom-bar destination." Drives the real [AppShellScreen] with a marker substituted for
 * Диалоги's own content (that file's own doc comment) — the property under test is entirely inside
 * [MoreScreen]'s own `openRowId` state, which needs no Hilt component to exercise.
 */
@RunWith(AndroidJUnit4::class)
class BackContractMoreScreenTest {
    // `25-214`: the v2 rule — see `BackContractBottomBarTest` for why the original is no longer
    // usable under `allWarningsAsErrors`, and what changes underneath.
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun clause2_backFromASettingsScreenReturnsToTheMoreListNotToThePreviousTab() {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = OperatorPermissions.Known(emptySet()),
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                conversationsTab = { Text("DIALOGI_MARKER") },
                // `26-54`: the real `TeamChatRoute` needs a Hilt component this suite deliberately has
                // none of - the identical substitution `conversationsTab` above already makes.
                teamTab = { Text("TEAM_MARKER") },
                // `26-17`: the real `SettingsRoute` needs a Hilt component this suite deliberately has
                // none of (this file's own doc comment) - a trivial marker stands in, the identical
                // substitution `conversationsTab` above already makes for Диалоги's own Hilt-backed
                // content. Clause 2 is about `MoreScreen`'s own back-stack, not about Settings' content.
                settingsScreen = { _, _ -> Text("SETTINGS_MARKER") },
            )
        }

        // Visit Команда first, then Ещё, then open the one real row Ещё has this wave (Настройки) -
        // exactly the "previous bottom-bar destination" the clause distinguishes from.
        composeTestRule.onNodeWithText("Команда").performClick()
        composeTestRule.onNodeWithText("Ещё").performClick()
        composeTestRule.onNodeWithText("Настройки").performClick()
        composeTestRule.waitForIdle()

        triggerBackPress(composeTestRule)
        composeTestRule.waitForIdle()

        // Back landed on the Ещё list - its own row is showing again - not on Команда, the tab that
        // was current immediately before Ещё.
        composeTestRule.onNodeWithText("Настройки").assertExists()
        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertDoesNotExist()
    }

    /**
     * `26-16`'s own brief named this row's requirement before `26-17` existed to fulfil it: "never a
     * dead tap, never hidden". `26-17` is the item that landed a real screen in its place — this test
     * now proves the row opens *something real* (via the substituted marker, for the identical
     * Hilt-avoidance reason the other two tests in this file use one) rather than merely a placeholder,
     * without re-testing Settings' own content here (that belongs to a suite that can afford a Hilt
     * component, or to `SettingsScreen`'s own stateless-composable tests).
     */
    @Test
    fun clause2_theSettingsRowOpensARealScreenRatherThanBeingHiddenOrDead() {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = OperatorPermissions.Known(emptySet()),
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                conversationsTab = { Text("DIALOGI_MARKER") },
                // `26-54`: the real `TeamChatRoute` needs a Hilt component this suite deliberately has
                // none of - the identical substitution `conversationsTab` above already makes.
                teamTab = { Text("TEAM_MARKER") },
                settingsScreen = { _, _ -> Text("SETTINGS_MARKER") },
            )
        }

        composeTestRule.onNodeWithText("Ещё").performClick()
        composeTestRule.onNodeWithText("Настройки").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("SETTINGS_MARKER").assertExists()
        assertFalse(composeTestRule.activity.isFinishing)
    }

    /** A second back press, from the Ещё list itself (no row open), falls through to clause 3's own
     * rule and lands on Диалоги - proven here as the boundary case that keeps clauses 2 and 3 from
     * silently overlapping. */
    @Test
    fun clause2_backFromTheMoreListItselfFallsThroughToClause3() {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = OperatorPermissions.Known(emptySet()),
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                conversationsTab = { Text("DIALOGI_MARKER") },
                // `26-54`: the real `TeamChatRoute` needs a Hilt component this suite deliberately has
                // none of - the identical substitution `conversationsTab` above already makes.
                teamTab = { Text("TEAM_MARKER") },
            )
        }

        composeTestRule.onNodeWithText("Ещё").performClick()
        composeTestRule.onNodeWithText("Настройки").assertExists()

        triggerBackPress(composeTestRule)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertExists()
    }
}
