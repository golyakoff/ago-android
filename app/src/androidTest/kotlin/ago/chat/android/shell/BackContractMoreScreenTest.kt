package ago.chat.android.shell

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.testing.triggerBackPress
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 *
 * `26-77`: Настройки left this screen entirely — [MoreScreen] no longer has a settings row or a
 * `settingsScreen` parameter of its own ([MoreScreen]'s own doc comment). Clause 2 is proven here
 * instead against one of the four real placeholder rows [buildMoreRows] now returns, and a new case
 * (`theRealAccountMenuOnEshoOpensSettingsAndBackReturnsToTheMoreList`) proves the *replacement*
 * pathway end to end through [MoreScreen]'s own real, Hilt-free [ago.chat.android.ui.components.AccountAvatarAction] —
 * the one screen in this file's own suite that can drive the real avatar with no Hilt component at all.
 */
@RunWith(AndroidJUnit4::class)
class BackContractMoreScreenTest {
    // `25-214`: the v2 rule — see `BackContractBottomBarTest` for why the original is no longer
    // usable under `allWarningsAsErrors`, and what changes underneath.
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun clause2_backFromAPlaceholderRowReturnsToTheMoreListNotToThePreviousTab() {
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

        // Visit Команда first, then Ещё, then open one of Автоматизация's own real rows - the identical
        // "previous bottom-bar destination" the clause distinguishes from, restated for a real row now
        // that Настройки is no longer one of them.
        composeTestRule.onNodeWithText("Команда").performClick()
        composeTestRule.onNodeWithText("Ещё").performClick()
        composeTestRule.onNodeWithText("Готовые ответы").performClick()
        composeTestRule.waitForIdle()

        triggerBackPress(composeTestRule)
        composeTestRule.waitForIdle()

        // Back landed on the Ещё list - its own row is showing again - not on Команда, the tab that
        // was current immediately before Ещё.
        composeTestRule.onNodeWithText("Готовые ответы").assertExists()
        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertDoesNotExist()
    }

    /** `26-77`'s own replacement for this file's now-retired settings-row test: Настройки no longer
     * has a row of its own in Ещё at all — proven directly by its absence — and is reached instead
     * through the real account menu, exercised here end to end with no Hilt component in play (the
     * identical Hilt-free property every other test in this file already relies on). */
    @Test
    fun clause2_theRealAccountMenuOnEshoOpensSettingsAndBackReturnsToTheMoreList() {
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
                settingsScreen = { _, _ -> Text("SETTINGS_MARKER") },
            )
        }

        composeTestRule.onNodeWithText("Ещё").performClick()
        composeTestRule.onNodeWithText("Настройки").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Меню аккаунта").performClick()
        composeTestRule.onNodeWithText("Настройки").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("SETTINGS_MARKER").assertExists()
        assertFalse(composeTestRule.activity.isFinishing)

        triggerBackPress(composeTestRule)
        composeTestRule.waitForIdle()

        // Back from Настройки lands on the Ещё list it was opened from, not on Диалоги -
        // `AppShellContent`'s own doc comment states why this is an ordinary `NavHost` pop rather than
        // a mechanism special-cased for this destination.
        composeTestRule.onNodeWithText("SETTINGS_MARKER").assertDoesNotExist()
        composeTestRule.onNodeWithText("Готовые ответы").assertExists()
        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertDoesNotExist()
    }

    /** Ещё's own list-of-lists no longer carries a Настройки row at all - `26-77`'s own Done-when
     * ("the old single 'Настройки' row is gone"), and Автоматизация/Администрирование now show real
     * rows rather than nothing. */
    @Test
    fun theMoreListShowsTheNewAutomationAndAdministrationRowsAndNoSettingsRow() {
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

        composeTestRule.onNodeWithText("Настройки").assertDoesNotExist()
        composeTestRule.onNodeWithText("Автоматизация").assertExists()
        composeTestRule.onNodeWithText("Готовые ответы").assertExists()
        composeTestRule.onNodeWithText("Автоответ вне смены").assertExists()
        composeTestRule.onNodeWithText("Администрирование").assertExists()
        composeTestRule.onNodeWithText("Операторы и роли").assertExists()
        composeTestRule.onNodeWithText("Тариф и оплата").assertExists()
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
        composeTestRule.onNodeWithText("Готовые ответы").assertExists()

        triggerBackPress(composeTestRule)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertExists()
    }
}
