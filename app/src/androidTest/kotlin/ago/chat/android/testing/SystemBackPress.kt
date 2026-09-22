package ago.chat.android.testing

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry

/**
 * `26-25`→`26-27`→`26-33`→`26-35`→`26-36`: four items in a row correctly diagnosed and fixed a real
 * problem with driving back-press through `UiDevice` (a real system-level key event, dispatched
 * through UiAutomator's accessibility-event pipeline) and were all fixing the wrong layer -
 * `docs/backlog/26-36-*.md` has the full account. Every back-contract test in this source set exists
 * to prove **this app's own [androidx.activity.compose.BackHandler] logic** - not "does Android's OS
 * back button work", which is Android's own concern, proven by Android's own test suite. `BackHandler`
 * is registered on the hosting Activity's [androidx.activity.OnBackPressedDispatcher], and that
 * dispatcher can be invoked directly, in-process, synchronously - no system input event, no
 * UiAutomator, no accessibility service, and therefore nothing for a headless CI emulator's
 * flakiness to ever touch.
 *
 * Unlike `UiDevice.pressBack()`, invoking the dispatcher with no enabled callback still reaches the
 * real production fallback - [ComponentActivity]'s own default back behaviour, which finishes the
 * Activity - because that fallback *is* the dispatcher's own construction-time default, the same one
 * `BackHandler(enabled = false)` falls through to in production. The one test that needs to prove
 * exactly that (`BackContractBottomBarTest.clause3_backOnDialogiExitsTheApp`) still reads the hosting
 * `ActivityScenario`'s own lifecycle state after calling this function, for the same reason
 * `SystemBackPress.kt`'s previous revision gave: there is no exception thrown here either.
 */
fun triggerBackPress(composeTestRule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>) {
    composeTestRule.activityRule.scenario.onActivity { activity ->
        activity.onBackPressedDispatcher.onBackPressed()
    }
}

/**
 * `26-36`'s own real, different finding for [ago.chat.android.ui.components.BackContractSheetDismissTest]
 * alone: [triggerBackPress] cannot drive that test, and the reason is architectural, not a mistake in
 * this rewrite. `ModalBottomSheet` renders its content inside a genuine platform
 * `Dialog` - Material3 1.4.0's internal `ModalBottomSheetDialogWrapper` extends
 * [androidx.activity.ComponentDialog], which - exactly like [ComponentActivity] - owns *its own*
 * [androidx.activity.OnBackPressedDispatcher], entirely separate from the hosting Activity's.
 * Decompiling that wrapper confirms it registers its dismiss-on-back callback on
 * `this.getOnBackPressedDispatcher()` - the dialog's own - never on the Activity's; calling
 * [triggerBackPress] against this test's `composeTestRule` was verified, by actually running it, to
 * skip straight past the sheet and finish the whole Activity, because the Activity's own dispatcher
 * has no enabled callback of its own while the sheet is open (`BackHandler(enabled = !sheetOpen)`
 * in the test, disabled) and never sees the sheet's own callback at all.
 *
 * That is a genuinely different property than every other back-contract test in this source set
 * proves: those all register [androidx.activity.compose.BackHandler] directly inside the Activity's
 * own composition, so they share its one dispatcher. This one is about *which window* a back signal
 * reaches first when a real platform Dialog is on top - a real Android window-focus routing concern,
 * not "this app's own `BackHandler` logic" - and no single in-process dispatcher call can stand in for
 * it, because there is no supported way to obtain Material3's own, non-public dialog instance from a
 * test. A real back signal is unavoidable here, so this presses back through [android.app.Instrumentation]
 * directly - `sendKeyDownUpSync`, which delivers the key event straight into the window that currently
 * has focus without ever going through `UiAutomator`'s accessibility-event pipeline (the actual,
 * confirmed-unreliable link `26-25`→`26-33` chased) or Espresso's window-focus-requiring
 * `ViewInteraction` machinery (`26-25`'s own `RootViewWithoutFocusException`). It is a third, narrower
 * mechanism, used only where the other one provably cannot reach - not a reversion to either of the
 * first two.
 */
fun pressBackOnFocusedWindow() {
    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
}
