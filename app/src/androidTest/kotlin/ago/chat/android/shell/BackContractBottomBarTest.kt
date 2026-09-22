package ago.chat.android.shell

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.Permission
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.testing.BACK_CONTRACT_WAIT_TIMEOUT_MS
import ago.chat.android.testing.pressSystemBack
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-16`, back-button contract clause 3: "back on a bottom-bar destination other than Диалоги
 * returns to Диалоги; back on Диалоги exits." Drives the real [AppShellScreen] — the real `NavHost`,
 * the real bottom-navigation `NavigationBarItem`s — with [AppShellScreen]'s own `conversationsTab`
 * slot substituted for a trivial marker `Text`, since this clause is about the navigation graph
 * itself, not about Диалоги's own Hilt-backed content (that file's own doc comment on why the slot
 * exists).
 */
@RunWith(AndroidJUnit4::class)
class BackContractBottomBarTest {
    // `25-214`: `...junit4.v2.createAndroidComposeRule`, not the original in `...junit4`. The
    // 2026.09.00 Compose BOM deprecates the latter, and this project builds with
    // `allWarningsAsErrors`, so a deprecated API is a build failure here rather than a warning. The
    // v2 factory returns the identical `AndroidComposeTestRule` type — the one real difference is
    // that the test clock runs on a `StandardTestDispatcher` instead of an
    // `UnconfinedTestDispatcher`, i.e. work launched inside composition is queued rather than run
    // eagerly at the launch point. Every back-contract test in this source set was re-run on a
    // device against the v2 rule and none needed explicit synchronisation added, because they all
    // already assert through `composeTestRule`'s own idle-synchronising matchers rather than
    // reading state straight after an event.
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val allFiveVisible = OperatorPermissions.Known(setOf(Permission.CALENDAR_CONFIGURE))

    @Test
    fun clause3_backOffAnotherTabLandsOnDialogi() {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = allFiveVisible,
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                conversationsTab = { Text("DIALOGI_MARKER") },
            )
        }

        composeTestRule.onNodeWithText("Команда").performClick()
        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertDoesNotExist()

        pressSystemBack()
        // `26-33`: a polling wait for the specific expected condition, not a blocking "nothing is
        // currently churning" signal that already proved insufficient here (this file's own doc
        // comment on `SystemBackPress.kt`). The `assertExists()` below is now a formality - it will
        // already be true - but stays as the assertion that gives a readable failure message.
        composeTestRule.waitUntil(timeoutMillis = BACK_CONTRACT_WAIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("DIALOGI_MARKER").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertExists()
        assertTrue("the Activity must still be alive - only the tab changed", !composeTestRule.activity.isFinishing)
    }

    @Test
    fun clause3_backOnDialogiExitsTheApp() {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = allFiveVisible,
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                conversationsTab = { Text("DIALOGI_MARKER") },
            )
        }
        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertExists()

        // `Espresso.pressBack()` used to throw `NoActivityResumedException` here — Espresso's own
        // documented signal that the press was *not* consumed by anything and the system's default
        // behaviour (finishing the task) ran instead. `pressSystemBack()` (`26-25`) has no equivalent
        // exception (`SystemBackPress.kt`'s own doc comment), so this proves the identical fact a
        // different, equally real way: the hosting `ActivityScenario` — the same one
        // `createAndroidComposeRule` drives this whole rule with — only ever reaches
        // `Lifecycle.State.DESTROYED` once the Activity has genuinely finished. If some callback
        // swallowed the press instead, the Activity stays `RESUMED` and never gets there, so
        // `waitUntil` times out and fails the test below — the exact failure this test always existed
        // to catch, just detected by polling a lifecycle state instead of catching an exception.
        pressSystemBack()
        // `26-33`: this was already the textbook-correct polling pattern - and it still timed out on
        // real CI at a 5-second budget (`ago-android` PR #34, run `35734381435`). Raised to the same
        // `BACK_CONTRACT_WAIT_TIMEOUT_MS` every other call site now uses, for consistency and because a
        // full `ActivityScenario` lifecycle transition is a slower thing than a Compose recomposition.
        composeTestRule.waitUntil(timeoutMillis = BACK_CONTRACT_WAIT_TIMEOUT_MS) {
            composeTestRule.activityRule.scenario.state == Lifecycle.State.DESTROYED
        }
    }

    /** The other half of clause 3, restated for a second tab so the rule is proven to be general
     * rather than special-cased for one destination: every non-Диалоги tab's own back stack is exactly
     * one entry deep, so back from any of them lands on Диалоги, never on whichever tab was visited
     * immediately before it. */
    @Test
    fun clause3_backNeverWalksThroughPreviouslyVisitedTabs() {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = allFiveVisible,
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                conversationsTab = { Text("DIALOGI_MARKER") },
            )
        }

        composeTestRule.onNodeWithText("Записи").performClick()
        composeTestRule.onNodeWithText("Команда").performClick()
        composeTestRule.onNodeWithText("Аналитика").performClick()

        pressSystemBack()
        // `26-33`: poll for the specific expected condition rather than a blocking idle signal - see
        // `SystemBackPress.kt`'s own doc comment.
        composeTestRule.waitUntil(timeoutMillis = BACK_CONTRACT_WAIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("DIALOGI_MARKER").fetchSemanticsNodes().isNotEmpty()
        }

        // One back press from the third tab visited lands directly on Диалоги - not on Записи, not on
        // Команда, whichever order they were visited in.
        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertExists()
        assertEquals(false, composeTestRule.activity.isFinishing)
    }
}
