package ago.chat.android.ui.components

import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
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
 *
 * `26-91`/`26-94`: this class's assertions are plain Russian literals - safe because
 * `LocaleForcingTestRunner` pins every instrumented test's own locale to `ru` before any of them run
 * (`docs/architecture.md`, "Pinning the locale instrumented UI tests render against").
 *
 * `26-309`: [render]'s own `hubConnectionState` default is [OperatorHubConnectionState.Connected], which
 * used to speak «Соединение: Подключено» and now speaks bare «Онлайн» (`isAway` also defaults `false`) -
 * see `docs/backlog/26-309-*.md` §6's own caution, and [theConnectedDotSpeaksOnlineWhenNotAway]/
 * [theConnectedDotSpeaksAwayWhenAway] below for the new assertions this change is exactly the kind of
 * stale-instrumented-assertion risk that item warns every worker brief to check for.
 * [thePresenceDotStillSpeaksItsConnectionState] below is untouched on purpose: `Disconnected` keeps its
 * pre-existing wording verbatim, the "connection trouble outranks availability" half of §1's own table.
 */
@RunWith(AndroidJUnit4::class)
class AccountAvatarActionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(
        displayName: String? = "Андрей Голяков",
        email: String? = "andrey@example.com",
        hubConnectionState: OperatorHubConnectionState = OperatorHubConnectionState.Connected,
        isAway: Boolean = false,
        onSetAway: suspend (Boolean) -> Boolean = { false },
        onOpenSettings: () -> Unit = {},
        onSignOut: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            AccountAvatarAction(
                displayName = displayName,
                email = email,
                hubConnectionState = hubConnectionState,
                isAway = isAway,
                onSetAway = onSetAway,
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
     * copy of either. `26-309`: unchanged on purpose - a disconnected socket keeps the pre-existing
     * `"Соединение: <state>"` wording verbatim, "connection trouble outranks availability"
     * (`docs/backlog/26-309-*.md` §1). */
    @Test
    fun thePresenceDotStillSpeaksItsConnectionState() {
        render(hubConnectionState = OperatorHubConnectionState.Disconnected)

        composeTestRule.onNodeWithContentDescription("Соединение: Отключено").assertExists()
    }

    /** `26-309`: once connected, the dot's own spoken sentence changes shape - no more "Соединение:
     * Подключено", just the bare availability word, since connection is fine and no longer the
     * interesting fact (§1's own Notes). This is exactly the kind of assertion `docs/backlog/26-309-*.md`
     * §6 warns every worker brief to update in lockstep with `colorFor`/`labelFor`'s new signature. */
    @Test
    fun theConnectedDotSpeaksOnlineWhenNotAway() {
        render(hubConnectionState = OperatorHubConnectionState.Connected, isAway = false)

        composeTestRule.onNodeWithContentDescription("Онлайн").assertExists()
        composeTestRule.onNodeWithContentDescription("Соединение: Подключено").assertDoesNotExist()
    }

    /** `26-309`: the other half of the same table row - connected and away speaks «Отошёл», not
     * «Онлайн» and not a "Соединение:"-prefixed sentence either. */
    @Test
    fun theConnectedDotSpeaksAwayWhenAway() {
        render(hubConnectionState = OperatorHubConnectionState.Connected, isAway = true)

        composeTestRule.onNodeWithContentDescription("Отошёл").assertExists()
    }

    /** `26-309`/§2: the account-menu row shows the *current* availability and the action that flips it -
     * «Отойти» while online, restated here as the row an operator sees the moment the menu opens. */
    @Test
    fun theAvailabilityRowOffersToGoAwayWhileOnline() {
        render(isAway = false)

        composeTestRule.onNodeWithContentDescription("Меню аккаунта").performClick()

        composeTestRule.onNodeWithText("Онлайн").assertExists()
        composeTestRule.onNodeWithText("Отойти").assertExists()
    }

    /** The other state of the same row - «Вернуться» while away, plus the persistent in-words notice
     * §2 asks for so the active-away fact is never colour-only. */
    @Test
    fun theAvailabilityRowOffersToComeBackWhileAwayAndNamesTheActiveNotice() {
        render(isAway = true)

        composeTestRule.onNodeWithContentDescription("Меню аккаунта").performClick()

        composeTestRule.onNodeWithText("Отошёл").assertExists()
        composeTestRule.onNodeWithText("Вернуться").assertExists()
        composeTestRule.onNodeWithText("Вы отошли — новые диалоги не назначаются").assertExists()
    }

    /** `26-309`/§2: tapping «Отойти» calls [AccountAvatarAction]'s own `onSetAway` with the target
     * value - `true` (go away), not a bare toggle-and-forget the caller has to reverse-engineer. */
    @Test
    fun tappingGoAwayCallsOnSetAwayWithTrue() {
        var requested: Boolean? = null
        render(
            isAway = false,
            onSetAway = { away ->
                requested = away
                true
            },
        )

        composeTestRule.onNodeWithContentDescription("Меню аккаунта").performClick()
        composeTestRule.onNodeWithText("Отойти").performClick()
        composeTestRule.waitForIdle()

        assertEquals(true, requested)
    }

    /** §2: "disabled while not Connected... the control never lies about a click that could not have
     * reached the server". */
    @Test
    fun theAvailabilityActionIsDisabledWhileNotConnected() {
        var calls = 0
        render(
            hubConnectionState = OperatorHubConnectionState.Disconnected,
            onSetAway = {
                calls++
                true
            },
        )

        composeTestRule.onNodeWithContentDescription("Меню аккаунта").performClick()

        composeTestRule.onNodeWithText("Отойти").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Управление статусом доступно при активном соединении").assertExists()
        assertEquals(0, calls)
    }

    /** §2: "surfaced, not swallowed" - a failed call shows its own inline error line, and the menu stays
     * open (never closed the way a plain `DropdownMenuItem` tap would close it). */
    @Test
    fun aFailedToggleSurfacesAnInlineErrorAndKeepsTheMenuOpen() {
        render(isAway = false, onSetAway = { false })

        composeTestRule.onNodeWithContentDescription("Меню аккаунта").performClick()
        composeTestRule.onNodeWithText("Отойти").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Не удалось изменить статус").assertExists()
        composeTestRule.onNodeWithText("Настройки").assertExists()
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
