package ago.chat.android.shell

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
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
            )
        }

        // Visit Команда first, then Ещё, then open the one real row Ещё has this wave (Настройки) -
        // exactly the "previous bottom-bar destination" the clause distinguishes from.
        composeTestRule.onNodeWithText("Команда").performClick()
        composeTestRule.onNodeWithText("Ещё").performClick()
        composeTestRule.onNodeWithText("Настройки").performClick()
        composeTestRule.waitForIdle()

        Espresso.pressBack()
        composeTestRule.waitForIdle()

        // Back landed on the Ещё list - its own row is showing again - not on Команда, the tab that
        // was current immediately before Ещё.
        composeTestRule.onNodeWithText("Настройки").assertExists()
        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertDoesNotExist()
    }

    @Test
    fun clause2_theSettingsRowOpensAPlaceholderRatherThanBeingHiddenPending2617() {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = OperatorPermissions.Known(emptySet()),
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                conversationsTab = { Text("DIALOGI_MARKER") },
            )
        }

        composeTestRule.onNodeWithText("Ещё").performClick()
        composeTestRule.onNodeWithText("Настройки").performClick()
        composeTestRule.waitForIdle()

        // `26-16`'s own brief: the row exists and opens a placeholder, even though `26-17` has not
        // landed - never a dead tap, never hidden.
        composeTestRule.onNodeWithText("Экран настроек появится вместе со следующей версией.").assertExists()
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
            )
        }

        composeTestRule.onNodeWithText("Ещё").performClick()
        composeTestRule.onNodeWithText("Настройки").assertExists()

        Espresso.pressBack()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertExists()
    }
}
