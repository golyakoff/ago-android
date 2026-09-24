package ago.chat.android.testing

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.test.runner.AndroidJUnitRunner
import java.util.Locale

/**
 * `26-94`: pins the instrumented-test process's own rendered locale to `ru`, so the whole suite stops
 * depending on whatever locale the device or emulator happens to boot with — the real defect `26-91`
 * exposed the moment `values-en/strings.xml` gave English somewhere real to resolve to
 * (`docs/architecture.md`'s "Pinning the locale instrumented UI tests render against" has the full
 * account, including the three device-side mechanisms tried against the CI emulator itself that all
 * failed first — none of them reachable from inside this process at all, which is the point).
 *
 * ## Why not `AppCompatDelegate.setApplicationLocales`, this item's own first suggestion
 *
 * Decompiling `androidx.appcompat:appcompat:1.8.0`'s own `AppCompatDelegate.class` shows
 * `setApplicationLocales` reaching the real platform `LocaleManager` (the API-33+ per-app-language
 * facility this class wraps) only through a private `getLocaleManagerForApplication()` helper, which
 * walks a static set of *already-created* `AppCompatDelegate` instances and asks each for its own
 * `Context`. Every entry in that set was created by an `AppCompatActivity`, an `AppCompatDialog`, or an
 * explicit `AppCompatDelegate.create(Activity, …)`/`create(Dialog, …)` call — there is no overload that
 * takes a bare `Context`. Every one of the fourteen classes this item touches drives its screen through
 * a plain [androidx.activity.ComponentActivity] (`createAndroidComposeRule<ComponentActivity>()`), and
 * nothing reachable from the flows they exercise ever constructs an `AppCompatActivity` first. Call
 * `setApplicationLocales` while that set is still empty — which is exactly the state of the process the
 * moment this runner's [onCreate] fires — and `getLocaleManagerForApplication()` returns `null`: the
 * call falls through to a branch that only records the request for delegates *not yet created*, and
 * never reaches `LocaleManager` at all. A real, load-bearing dead end, confirmed by reading the actual
 * bytecode rather than assumed — which is why this runner calls the platform `LocaleManager` directly
 * instead, and why `androidx.appcompat` gained no new dependency for this item: it already sits in this
 * app's dependency graph transitively (`net.openid:appauth`'s own `AppCompatActivity`-based redirect
 * screens — see the manifest comment on those activities), but nothing in this app calls it directly,
 * and this fix does not change that.
 *
 * ## Why a custom runner, not a `TestRule` or a shared base class
 *
 * The fourteen classes share no base class and no common `@get:Rule` today (checked before writing
 * this). A `TestRule`/`@Before` still runs *after* `Instrumentation` has created the target
 * `Application` — and, for the very first test in the process, after that test's own first `setContent`
 * call — either of which may already have resolved and cached resources against whatever locale the
 * device booted with. Overriding [onCreate] runs before `super.onCreate()` schedules any of that
 * (`AndroidJUnitRunner`'s own `Instrumentation.onCreate()` only starts the sequence that creates the
 * target `Application` and the first `Activity`, on `onStart()`'s own thread, once it returns), so the
 * very first `Configuration` this process ever resolves resources against is already `ru` — set once,
 * here, covering every test in every class with no per-file code beyond removing `@FlakyOnCi`.
 *
 * Wired in as `app/build.gradle.kts`'s own `testInstrumentationRunner`, replacing the stock
 * `androidx.test.runner.AndroidJUnitRunner` this subclasses and otherwise leaves untouched.
 */
class LocaleForcingTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        pinLocaleToRussian(targetContext)
        super.onCreate(arguments)
    }

    // `@Suppress` has to sit on the whole function rather than the one deprecated call inside the
    // `else` branch below - Kotlin does not allow an annotation directly on a bare expression
    // statement, only on a declaration.
    @Suppress("DEPRECATION")
    private fun pinLocaleToRussian(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The real per-app-language platform facility (API 33+): `LocaleManager.
            // setApplicationLocales` is keyed to the *calling* app's own package, which this
            // self-instrumenting test process shares with the app under test, and reconfigures every
            // `Context` in the process immediately - no `AppCompatActivity`/delegate involved at all,
            // unlike the dead end above. This app's own `minSdk` is 26, but CI's own emulator
            // (`.github/workflows/ci.yml`, `api-level: 34`) and every currently shipped, supported
            // real device both sit comfortably above 33 - `docs/architecture.md` names that boundary
            // as this mechanism's own, stated limit rather than leaving it to be discovered.
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                LocaleList.forLanguageTags("ru")
        } else {
            // Below 33 there is no platform per-app-language facility, and - per this file's own doc
            // comment above - no `AppCompatDelegate` exists in this process to hook either. This is the
            // most a plain `Configuration` override can do without one: it changes `Locale.getDefault()`
            // and this context's own cached `Resources` immediately, but a *new* `Activity` on this API
            // range still receives its `Configuration` from the system, not from this override - so it
            // is not a full fix on this branch. Recorded honestly rather than left silently unproven:
            // this item's own real target (CI's emulator) never takes this path at all.
            val locale = Locale.forLanguageTag("ru")
            Locale.setDefault(locale)
            val resources = context.resources
            val configuration = Configuration(resources.configuration)
            configuration.setLocale(locale)
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
    }
}
