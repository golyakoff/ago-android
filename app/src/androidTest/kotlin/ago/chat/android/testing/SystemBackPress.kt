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
 *
 * `26-33`: `26-27`'s own fix - despite nine clean local-device runs - failed identically on real CI
 * (`ago-android` PR #34, `instrumented-tests`, run `35734381435`), the exact fix commit under test. The
 * downloaded JUnit XML from that run added two facts `26-27` didn't have:
 *
 * 1. `BackContractBottomBarTest.clause3_backOffAnotherTabLandsOnDialogi` already called *both*
 *    layers - this function's own [UiDevice.waitForIdle] and then a `composeTestRule.waitForIdle()`
 *    right after it - and still failed. Stacking a second, Compose-level `waitForIdle()` on top of
 *    this function's own wait is therefore already proven insufficient; every call site was rewritten
 *    to replace that stale-UI-prone pattern with a *polling* wait for the specific condition each test
 *    actually cares about (`composeTestRule.waitUntil(timeoutMillis = BACK_CONTRACT_WAIT_TIMEOUT_MS) {
 *    ... }`), not another blocking "nothing is currently churning" signal that can be satisfied without
 *    the real target state ever being reached. `BACK_CONTRACT_WAIT_TIMEOUT_MS` below is 12 seconds -
 *    comfortably above the 5-second budget the next point shows was not enough on a loaded CI runner,
 *    without making a genuinely broken test hang the suite for an unreasonable time.
 * 2. `clause3_backOnDialogiExitsTheApp` - which already polls (`waitUntil(timeoutMillis = 5_000) { ...
 *    Lifecycle.State.DESTROYED }`, not a single blocking wait) - *also* timed out on that same run.
 *    That is a second, independent data point on top of (1): even the textbook-correct polling pattern
 *    can still fail here, at a 5-second budget, on a real CI runner under load. Its own timeout was
 *    raised to `BACK_CONTRACT_WAIT_TIMEOUT_MS` for the same reason and for consistency with every other
 *    call site.
 *
 * Point 2 also raises a question (1) alone doesn't answer: is 5 seconds too short for a *slow but
 * eventually-successful* lifecycle transition, or is [UiDevice.pressBack] occasionally not delivering
 * the press at all? The two failure modes would need different fixes - one is "wait longer", the other
 * is "press again" - so this was actually investigated rather than assumed, by reading what
 * [UiDevice.pressBack] (`androidx.test.uiautomator` 2.4.0) does, not just what it's documented to do:
 * decompiled, its body is `waitForIdle()`, then
 * `InteractionController.sendKeyAndWaitForEvent(KEYCODE_BACK, 0, TYPE_WINDOWS_CHANGED, 1000L)`. That
 * inner call *unconditionally* injects the key-down/key-up events first, via `injectEventSync` inside
 * the very `Runnable` it hands to its own event-wait helper, and only *then* reports whether a
 * `TYPE_WINDOWS_CHANGED` accessibility event arrived within a **hardcoded 1000ms** - a window this
 * function, and every call site's own `BACK_CONTRACT_WAIT_TIMEOUT_MS`, has no way to see or extend.
 * `pressBack()`'s boolean return is therefore not a "was this delivered" signal at all - it is "did a
 * qualifying accessibility event arrive within one second of sending it", which is exactly the kind of
 * thing a loaded CI runner (or, as it turned out, this very machine while other work was running) makes
 * unreliable regardless of whether the press landed. Building a retry on top of that signal was tried
 * here and immediately produced two false failures on a real local run of tests that pass today
 * (`clause3_backOffAnotherTabLandsOnDialogi` and `clause3_backNeverWalksThroughPreviouslyVisitedTabs`,
 * both failing with "never delivered after 3 attempt(s)" on a perfectly healthy emulator) - confirming
 * empirically, not just by reading the bytecode, that this signal fires on ordinary settling delay, not
 * on a genuinely dropped press. Retrying on it would also be actively harmful in the case it *does*
 * fire correctly-but-late: the key was already injected, so a "retry" is a real second back-press
 * stacked on a first one that likely already landed, risking exactly the kind of over-navigation (an
 * extra screen skipped) this whole item exists to stop happening. **Decision: `pressSystemBack()` does
 * not retry the press, and does not inspect [UiDevice.pressBack]'s return value at all.** The call-site
 * polling waits from point 1 already give every condition that is genuinely, eventually true a full
 * [BACK_CONTRACT_WAIT_TIMEOUT_MS] (12s - twelve times [UiDevice.pressBack]'s own internal window) to
 * resolve, which is the correct place to absorb "delivered, but slow" - the failure mode this
 * investigation confirmed is what's actually happening. A press that is *never* delivered at all (the
 * accessibility connection permanently failing to reconnect, say) still fails loudly and diagnosably:
 * the call-site `waitUntil` times out and reports exactly what it was waiting for, which is a clear
 * enough signal without this function guessing at a cause it cannot reliably detect.
 */
fun pressSystemBack() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    device.pressBack()
    device.waitForIdle()
}

/**
 * The polling budget every back-contract call site uses for its post-`pressSystemBack()` assertion -
 * see this file's own `26-33` doc comment for why 5 seconds, even as a poll rather than a single wait,
 * was confirmed insufficient on real CI, and why this number is 12 rather than something open-ended.
 */
const val BACK_CONTRACT_WAIT_TIMEOUT_MS = 12_000L
