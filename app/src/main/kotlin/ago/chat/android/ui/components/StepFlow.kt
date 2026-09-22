package ago.chat.android.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * `26-16`, back-button contract clause 4: "back inside a multi-step flow returns to the previous
 * step". `navigation.md` names the worker re-cut (`Записи`) as the flow this rule was written for and
 * is explicit that it is not in this wave — but the rule itself is not the re-cut's, and this item's
 * own brief is equally explicit that the mechanism is built now regardless, so that whichever item
 * builds the first real multi-step flow (the re-cut, or anything else Записи/Ещё later needs) reaches
 * for this rather than re-deriving the same three-line state machine under a different name.
 *
 * A flow of [stepCount] steps, indexed from `0`. [StepFlowState.step] survives rotation and process
 * death the ordinary `rememberSaveable` way; it does **not** survive leaving the flow and returning —
 * a fresh [rememberStepFlowState] call is what "leaving the flow" means for the caller that hosts it,
 * exactly as `navigation.md`'s own worker-re-cut section states for that specific flow ("leaving the
 * flow entirely discards the decisions").
 */
public class StepFlowState(
    initialStep: Int,
    private val stepCount: Int,
) {
    private var current by mutableIntStateOf(initialStep.coerceIn(0, (stepCount - 1).coerceAtLeast(0)))

    public val step: Int get() = current

    public val isFirstStep: Boolean get() = current == 0

    public val isLastStep: Boolean get() = current == stepCount - 1

    /** Moves forward one step. A no-op past the last step — a caller that wants "finish" as its own
     * event should read [isLastStep] rather than calling this an extra time. */
    public fun advance() {
        if (!isLastStep) current += 1
    }

    /** Moves back one step. Returns `false` on the first step, meaning "this state has nothing left to
     * consume" — the signal a caller's own [BackHandler] uses to know the *flow itself* should be left
     * rather than one more step inside it. */
    public fun retreat(): Boolean {
        if (isFirstStep) return false
        current -= 1
        return true
    }

    public companion object {
        internal fun saver(stepCount: Int): Saver<StepFlowState, Int> =
            Saver(
                save = { it.step },
                restore = { StepFlowState(it, stepCount) },
            )
    }
}

@Composable
public fun rememberStepFlowState(
    stepCount: Int,
    initialStep: Int = 0,
): StepFlowState = rememberSaveable(stepCount, saver = StepFlowState.saver(stepCount)) { StepFlowState(initialStep, stepCount) }

/**
 * Wires system back to [StepFlowState.retreat] — enabled only past the first step, so back on step 0
 * is never consumed here and instead reaches whatever the host screen's own back handling is (leaving
 * the flow, per this file's own doc comment). A thin wrapper over [BackHandler] rather than something
 * a caller inlines by hand, so every multi-step flow this app ever builds wires clause 4 identically.
 */
@Composable
public fun StepFlowBackHandler(state: StepFlowState) {
    BackHandler(enabled = !state.isFirstStep) { state.retreat() }
}
