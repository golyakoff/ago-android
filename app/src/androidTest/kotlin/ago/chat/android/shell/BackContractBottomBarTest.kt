package ago.chat.android.shell

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.Permission
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.testing.FlakyOnCi
import ago.chat.android.testing.triggerBackPress
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
 *
 * `26-91`: this class asserts Russian text as a literal; the CI emulator boots English and cannot be
 * forced to `ru-RU` by any mechanism found so far (`ci.yml`'s own comment has the detail). `26-94`
 * tracks the real fix.
 */
@FlakyOnCi
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
                // `26-54`: the real `TeamChatRoute` needs a Hilt component this suite deliberately has
                // none of - the identical substitution `conversationsTab` above already makes.
                teamTab = { Text("TEAM_MARKER") },
            )
        }

        composeTestRule.onNodeWithText("Команда").performClick()
        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertDoesNotExist()

        triggerBackPress(composeTestRule)
        composeTestRule.waitForIdle()

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
                // `26-54`: the real `TeamChatRoute` needs a Hilt component this suite deliberately has
                // none of - the identical substitution `conversationsTab` above already makes.
                teamTab = { Text("TEAM_MARKER") },
            )
        }
        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertExists()

        // `Espresso.pressBack()` used to throw `NoActivityResumedException` here — Espresso's own
        // documented signal that the press was *not* consumed by anything and the system's default
        // behaviour (finishing the task) ran instead. `triggerBackPress()` (`26-36`) drives
        // `onBackPressedDispatcher.onBackPressed()` directly, which has no equivalent exception
        // (`SystemBackPress.kt`'s own doc comment), so this proves the identical fact a different,
        // equally real way: the hosting `ActivityScenario` — the same one `createAndroidComposeRule`
        // drives this whole rule with — only ever reaches `Lifecycle.State.DESTROYED` once the
        // Activity has genuinely finished. If some callback swallowed the press instead, the Activity
        // stays `RESUMED` and never gets there, so `waitUntil` times out and fails the test below —
        // the exact failure this test always existed to catch, just detected by polling a lifecycle
        // state instead of catching an exception. The wait itself is real: `onBackPressed()` returning
        // does not mean the Activity has finished destroying yet, since `finish()` still hops through
        // the main looper to tear the Activity down - this polls that real, already-triggered
        // transition, not an external event that might never arrive.
        triggerBackPress(composeTestRule)
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
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
                // `26-48`/`26-54`: [ago.chat.android.bookings.BookingsRoute] and
                // [ago.chat.android.team.TeamChatRoute] both need a Hilt component this suite
                // deliberately has none of - the identical Hilt-avoidance substitution `conversationsTab`
                // above already makes for Диалоги's own content. This clause is about the bottom bar's
                // own back stack, not about either tab's content, and this test visits both.
                bookingsTab = { _, _, _ -> Text("BOOKINGS_MARKER") },
                teamTab = { Text("TEAM_MARKER") },
            )
        }

        composeTestRule.onNodeWithText("Записи").performClick()
        composeTestRule.onNodeWithText("Команда").performClick()
        composeTestRule.onNodeWithText("Аналитика").performClick()

        triggerBackPress(composeTestRule)
        composeTestRule.waitForIdle()

        // One back press from the third tab visited lands directly on Диалоги - not on Записи, not on
        // Команда, whichever order they were visited in.
        composeTestRule.onNodeWithText("DIALOGI_MARKER").assertExists()
        assertEquals(false, composeTestRule.activity.isFinishing)
    }
}
