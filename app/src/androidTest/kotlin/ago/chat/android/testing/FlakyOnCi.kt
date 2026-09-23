package ago.chat.android.testing

/**
 * `26-47`: marks a test class CI excludes via `-Pandroid.testInstrumentationRunnerArguments.
 * notAnnotation=ago.chat.android.testing.FlakyOnCi` (`.github/workflows/ci.yml`) - not deleted, not
 * `@Ignore`d, just no longer a gate on every push. `26-35` designed this exact annotation and its
 * `ci.yml` wiring for a different, now-resolved case (the five `BackContract*Test` files before
 * `26-36`'s dispatcher rewrite) but never merged either - recreated here for a narrower, single case.
 *
 * `BackContractSheetDismissTest` alone still presses a real system key
 * (`SystemBackPress.kt`'s `pressBackOnFocusedWindow()`) because it proves which *window* - the
 * Activity's or a Material3 `ModalBottomSheet`'s own platform `Dialog` - a back signal reaches first,
 * something no in-process dispatcher call can answer (`26-36`'s own finding). Two of three real CI
 * runs since `26-36` landed failed on that one test's own second press
 * (`docs/backlog/26-47-*.md` has the full evidence) - a real, residual timing risk in a genuine
 * cross-window system key press on a loaded CI runner, not a logic defect in the test or the app.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class FlakyOnCi
