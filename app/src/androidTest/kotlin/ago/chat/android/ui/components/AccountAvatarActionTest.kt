package ago.chat.android.ui.components

import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-77`: [AccountAvatarAction] on its own — initials derivation, the presence dot's own accessible
 * description, and both menu items firing their own callback and nothing else. The header's own
 * name/email rendering and the initials-fallback rule are proven here too, since none of the five
 * screens this composable now sits on have their own test for it — the same "route wires, screen
 * renders" split means the shared composable itself is where this belongs, once, rather than once per
 * screen that calls it.
 */
@RunWith(AndroidJUnit4::class)
class AccountAvatarActionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(
        displayName: String? = "Андрей Голяков",
        email: String? = "andrey@example.com",
        hubConnectionState: OperatorHubConnectionState = OperatorHubConnectionState.Connected,
        onOpenSettings: () -> Unit = {},
        onSignOut: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            AccountAvatarAction(
                displayName = displayName,
                email = email,
                hubConnectionState = hubConnectionState,
                onOpenSettings = onOpenSettings,
                onSignOut = onSignOut,
            )
        }
    }

    /** `docs/backlog/26-77-*.md`'s own derivation rule: first letter of each of the first two words,
     * uppercased. */
    @Test
    fun aTwoWordNameRendersItsTwoInitials() {
        render(displayName = "Андрей Голяков")

        composeTestRule.onNodeWithText("АГ").assertExists()
    }

    /** A single-word name uses its own first two letters rather than one, or the whole word. */
    @Test
    fun aSingleWordNameRendersItsOwnFirstTwoLetters() {
        render(displayName = "Иван")

        composeTestRule.onNodeWithText("ИВ").assertExists()
    }

    /** `docs/backlog/26-77-*.md`'s own Scope: "empty/unset name falls back to something honest" - a
     * blank name renders the fallback glyph, never a blank circle, and the header line falls back to
     * the generic word rather than to blank text either. */
    @Test
    fun aBlankNameFallsBackToAnHonestInitialAndHeaderName() {
        render(displayName = " ")

        composeTestRule.onNodeWithText("?").assertExists()

        composeTestRule.onNodeWithContentDescription("Меню аккаунта").performClick()
        composeTestRule.onNodeWithText("Оператор").assertExists()
    }

    /** A `null` name is the identical case a blank one is - [ago.chat.android.session.OperatorIdentity]
     * itself states both are genuinely possible outcomes of reading the ID token. */
    @Test
    fun aNullNameFallsBackTheSameWayABlankOneDoes() {
        render(displayName = null, email = null)

        composeTestRule.onNodeWithText("?").assertExists()
    }

    /** [HubConnectionDot]'s own accessibility contract, preserved: a coloured circle says its state out
     * loud, composed here through the identical `colorFor`/`labelFor` pair rather than a second, drifted
     * copy of either. */
    @Test
    fun thePresenceDotStillSpeaksItsConnectionState() {
        render(hubConnectionState = OperatorHubConnectionState.Disconnected)

        composeTestRule.onNodeWithContentDescription("Соединение: Отключено").assertExists()
    }

    @Test
    fun theMenuIsClosedUntilTheAvatarIsTapped() {
        render()

        composeTestRule.onNodeWithText("Настройки").assertDoesNotExist()
        composeTestRule.onNodeWithText("Выйти").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Меню аккаунта").performClick()

        composeTestRule.onNodeWithText("Настройки").assertExists()
        composeTestRule.onNodeWithText("Выйти").assertExists()
    }

    /** The header's own email line is drawn only when there is one - never an empty line
     * ([ago.chat.android.conversations.ConversationListScreen]'s own `ConversationRowSnippetLine`
     * states the identical "never invented, rendered honestly" rule this reuses). */
    @Test
    fun theHeaderShowsTheEmailOnlyWhenThereIsOne() {
        render(email = null)

        composeTestRule.onNodeWithContentDescription("Меню аккаунта").performClick()

        composeTestRule.onNodeWithText("andrey@example.com").assertDoesNotExist()
    }

    @Test
    fun tappingSettingsClosesTheMenuAndCallsOnOpenSettings() {
        var opened = 0
        render(onOpenSettings = { opened++ })

        composeTestRule.onNodeWithContentDescription("Меню аккаунта").performClick()
        composeTestRule.onNodeWithText("Настройки").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, opened)
        composeTestRule.onNodeWithText("Выйти").assertDoesNotExist()
    }

    @Test
    fun tappingSignOutClosesTheMenuAndCallsOnSignOut() {
        var signedOut = 0
        render(onSignOut = { signedOut++ })

        composeTestRule.onNodeWithContentDescription("Меню аккаунта").performClick()
        composeTestRule.onNodeWithText("Выйти").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, signedOut)
        composeTestRule.onNodeWithText("Настройки").assertDoesNotExist()
    }
}
