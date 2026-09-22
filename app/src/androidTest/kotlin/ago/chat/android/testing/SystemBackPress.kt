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
 *
 * `26-27`: fixing the focus race above traded away a synchronization guarantee nobody named at the
 * time. `Espresso.pressBack()` is wired into Espresso's own `IdlingResource` machinery - it does not
 * return to the caller until the app's main-thread work queue is genuinely idle, which is exactly why
 * every back-contract test could get away with no explicit wait of its own before `26-25`.
 * [UiDevice.pressBack] has no equivalent: it dispatches a system-level key event through UiAutomator
 * and returns immediately, with no synchronization to Compose's or Espresso's idle state at all. That
 * gap is real, not theoretical - it produced six live CI failures across four test files
 * (`ago-android` PR #33, `instrumented-tests`, run `35729574271`), every one an assertion right after
 * a `pressSystemBack()` call finding stale UI.
 *
 * The fix is *not* `composeTestRule.waitForIdle()` alone. Two of those six failures
 * (`BackContractBottomBarTest.clause3_backOffAnotherTabLandsOnDialogi` and
 * `.clause3_backNeverWalksThroughPreviouslyVisitedTabs`) already called it immediately after
 * `pressSystemBack()` and still failed: Compose's `waitForIdle()` only inspects Compose's *own*
 * current recomposition state, and has nothing to wait for if the system back event has not even been
 * delivered to the app's dispatcher yet. The wait has to happen at the layer the system event actually
 * flows through first - [UiDevice.waitForIdle], a UiAutomator API that blocks until the
 * accessibility-event stream itself goes quiet. That is *why* the call below is a `UiDevice`-level
 * wait rather than a `composeTestRule`-level one: it closes the dispatch race itself, not just the
 * recomposition that follows it. A future reader tempted to "fix" a similar report by reaching back
 * for `Espresso.pressBack()` would undo `26-25`'s real fix for `RootViewWithoutFocusException` - don't;
 * the synchronization gap that reintroduces is exactly what this function's own wait exists to close
 * without it.
 */
fun pressSystemBack() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    device.pressBack()
    device.waitForIdle()
}
