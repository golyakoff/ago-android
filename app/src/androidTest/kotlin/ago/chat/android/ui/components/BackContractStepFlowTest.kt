package ago.chat.android.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-16`, back-button contract clause 4: "back inside a multi-step flow returns to the previous
 * step." [StepFlowState]/[StepFlowBackHandler]'s own doc comment says why this is tested against a
 * throwaway three-step demo rather than a real flow: `navigation.md`'s worker re-cut, the flow this
 * rule was written for, is not built in this wave, but the item's own brief is explicit that the
 * mechanism is proven now regardless, so whichever item builds the first real multi-step flow reaches
 * for an already-tested primitive rather than a new one.
 */
@RunWith(AndroidJUnit4::class)
class BackContractStepFlowTest {
    // `25-214`: the v2 rule — see `BackContractBottomBarTest` for why the original is no longer
    // usable under `allWarningsAsErrors`, and what changes underneath.
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun clause4_backMovesOneStepAtATimeThenLeavesTheFlow() {
        var leftFlow = false

        composeTestRule.setContent {
            // The host screen's own back handling for "leaving the flow entirely" - registered
            // *before* the flow's own composable below, so `StepFlowBackHandler`'s more-recently-added
            // callback is the one that consumes back for as long as it is enabled (`!isFirstStep`),
            // exactly the ordering `AppShellScreen`'s own doc comment states for `MoreScreen`.
            BackHandler { leftFlow = true }

            val state = rememberStepFlowState(stepCount = 3)
            StepFlowBackHandler(state)
            Text(text = "STEP_${state.step}", modifier = Modifier.clickable { state.advance() })
        }

        composeTestRule.onNodeWithText("STEP_0").assertExists()

        composeTestRule.onNodeWithText("STEP_0").performClick()
        composeTestRule.onNodeWithText("STEP_1").performClick()
        composeTestRule.onNodeWithText("STEP_2").assertExists()

        Espresso.pressBack()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("STEP_1").assertExists()

        Espresso.pressBack()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("STEP_0").assertExists()

        // One more back, from the first step - `StepFlowBackHandler` is disabled here, so this press
        // is not consumed by the flow at all and reaches the host's own handler instead.
        Espresso.pressBack()
        composeTestRule.waitForIdle()

        assertTrue("back on the first step must propagate to the host screen's own back handling", leftFlow)
    }
}
