package ago.chat.android.testing

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/**
 * `26-25`: the one place naming why every back-contract test drives back through [UiDevice]
 * rather than `androidx.test.espresso.Espresso.pressBack()`.
 *
 * `Espresso.pressBack()` requires the app's window to hold input focus at the instant it runs, and
 * throws `RootViewWithoutFocusException` when it does not - a real race, independent of anything
 * wrong with the app under test, that the CI emulator (`reactivecircus/android-emulator-runner`,
 * headless, no hardware acceleration) intermittently loses. That race is what made
 * `instrumented-tests` fail roughly every other run this session
 * (`ago-root/docs/backlog/26-25-*.md`) - three of those failures were re-run to green by hand before
 * being confirmed against real evidence, because the job did not yet upload its own test report.
 *
 * [UiDevice]'s back press operates at the system/UiAutomator level instead of through Espresso's own
 * `ViewInteraction` machinery, so it does not require the app window to hold input focus and does not
 * race the thing that was failing here. This is the standard, widely-documented fix for exactly this
 * `RootViewWithoutFocusException` flake class, not a workaround invented for this codebase.
 *
 * One consequence worth stating once, here, rather than at each call site: unlike
 * `Espresso.pressBack()`, [UiDevice.pressBack] never throws `NoActivityResumedException` - it has no
 * equivalent signal for "nothing consumed the press, the system finished the task instead". A test
 * that needs to prove *that* fact (there is exactly one, `BackContractBottomBarTest
 * .clause3_backOnDialogiExitsTheApp`) cannot use this helper for its assertion and instead reads the
 * hosting `ActivityScenario`'s own lifecycle state after calling this function.
 */
fun pressSystemBack() {
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
}
