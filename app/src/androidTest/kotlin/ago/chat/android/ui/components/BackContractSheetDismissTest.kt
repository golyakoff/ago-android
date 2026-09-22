package ago.chat.android.ui.components

import ago.chat.android.testing.BACK_CONTRACT_WAIT_TIMEOUT_MS
import ago.chat.android.testing.pressSystemBack
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
import androidx.compose.ui.test.onAllNodesWithText
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
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

        pressSystemBack()
        // `26-33`: poll for the specific expected condition rather than a blocking idle signal - see
        // `SystemBackPress.kt`'s own doc comment.
        composeTestRule.waitUntil(timeoutMillis = BACK_CONTRACT_WAIT_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("SHEET_CONTENT").fetchSemanticsNodes().isEmpty() &&
                composeTestRule.onAllNodesWithText("SCREEN_MARKER").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("SHEET_CONTENT").assertDoesNotExist()
        composeTestRule.onNodeWithText("SCREEN_MARKER").assertExists()
        assertFalse("back must be consumed by the sheet's own dismissal, never reach the screen underneath it", leftScreen)

        // A second back press, with the sheet already gone, is free to reach the screen's own handler.
        pressSystemBack()
        // `leftScreen` is a plain callback flag, not something visible in the semantics tree - `waitUntil`
        // polls it directly instead of a synthetic node lookup.
        composeTestRule.waitUntil(timeoutMillis = BACK_CONTRACT_WAIT_TIMEOUT_MS) { leftScreen }
        assertTrue("once the sheet is gone, the next back press is the screen's own to answer", leftScreen)
    }
}
