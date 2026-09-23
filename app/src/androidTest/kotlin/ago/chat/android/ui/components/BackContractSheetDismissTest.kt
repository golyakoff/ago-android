package ago.chat.android.ui.components

import ago.chat.android.testing.FlakyOnCi
import ago.chat.android.testing.pressBackOnFocusedWindow
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-16`, back-button contract clause 5: "sheets, filter sheets and confirmation dialogs are
 * dismissed by back before the screen under them." No real sheet exists yet in this wave (Записи's
 * filter sheets and the visitor context sheet both arrive with later items,
 * `docs/backlog/26-16-*.md`'s own Out of scope), so this proves the *mechanism* this app will reach
 * for every time one is built: Material3's [ModalBottomSheet] consumes system back for its own
 * dismissal before whatever `BackHandler` the hosting screen itself registers — proven here inside a
 * real `NavHost`-free host, the same integration a real future sheet will sit inside.
 *
 * `26-36`: this is the one back-contract file that still presses back through a real system key event
 * ([ago.chat.android.testing.pressBackOnFocusedWindow]), not through the Activity's own
 * `onBackPressedDispatcher` the other four files use. [ModalBottomSheet] renders through a genuine
 * platform `Dialog` with its own, separate `OnBackPressedDispatcher` (`SystemBackPress.kt`'s own doc
 * comment has the decompiled proof), so a call against the Activity's dispatcher never reaches the
 * sheet's dismissal at all - confirmed by actually running it, which found a real, different failure
 * (the whole Activity finished on the very first back press). What this test needs to prove - which
 * window a back signal reaches first when a real Dialog is on top - is a window-focus question, not
 * "this app's own `BackHandler` logic", so it is answered with a real signal on purpose.
 *
 * `26-47`: `@FlakyOnCi` - two of the three real CI runs since `26-36` failed on this test's own
 * second press (`docs/backlog/26-47-*.md` has the full evidence), a residual timing risk specific to
 * a genuine cross-window system key press on a loaded CI runner. Still runs locally; no longer a gate
 * on every push.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@FlakyOnCi
class BackContractSheetDismissTest {
    // `25-214`: the v2 rule — see `BackContractBottomBarTest` for why the original is no longer
    // usable under `allWarningsAsErrors`, and what changes underneath.
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun clause5_backDismissesTheSheetBeforeItReachesTheScreenUnderIt() {
        var leftScreen = false

        composeTestRule.setContent {
            var sheetOpen by remember { mutableStateOf(false) }

            // The screen's own back handling - "leave this screen" - must never fire while the sheet
            // is the thing actually on top.
            BackHandler(enabled = !sheetOpen) { leftScreen = true }

            Text(text = "SCREEN_MARKER")
            Button(onClick = { sheetOpen = true }) { Text(text = "Open sheet") }

            if (sheetOpen) {
                ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
                    Text(
                        text = "SHEET_CONTENT",
                        modifier =
                            androidx.compose.ui.Modifier
                                .padding(24.dp),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Open sheet").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("SHEET_CONTENT").assertExists()

        pressBackOnFocusedWindow()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("SHEET_CONTENT").assertDoesNotExist()
        composeTestRule.onNodeWithText("SCREEN_MARKER").assertExists()
        assertFalse("back must be consumed by the sheet's own dismissal, never reach the screen underneath it", leftScreen)

        // The dialog's own window closing and the Activity's own window regaining input focus is a
        // real OS-level transition `waitForIdle()` above does not cover - it only settles Compose's
        // own recomposition, not native window focus. Found by actually running this test repeatedly:
        // without this wait, the second `pressBackOnFocusedWindow()` below intermittently fired before
        // focus had returned to the Activity's window and was delivered nowhere, the same class of
        // window-focus race `26-25`'s own `RootViewWithoutFocusException` was about, just relocated
        // rather than eliminated by pressing a real key. This polls a real, already-triggered
        // transition (the dialog is already closing) rather than an event that might never arrive.
        composeTestRule.waitUntil(timeoutMillis = 5_000) { composeTestRule.activity.hasWindowFocus() }

        // A second back press, with the sheet already gone, is free to reach the screen's own handler.
        pressBackOnFocusedWindow()
        composeTestRule.waitForIdle()
        assertTrue("once the sheet is gone, the next back press is the screen's own to answer", leftScreen)
    }
}
