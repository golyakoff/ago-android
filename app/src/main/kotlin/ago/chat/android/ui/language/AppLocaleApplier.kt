package ago.chat.android.ui.language

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * `26-92`: makes an operator's own choice of [AppLanguage] actually take effect, right after
 * [AppLanguagePreferences.setLanguage] has persisted it — two different real mechanisms, chosen by API
 * level, and **neither of them `AppCompatDelegate.setApplicationLocales`**, despite that call being this
 * item's own first-suggested API.
 *
 * `docs/architecture.md`'s "Pinning the locale instrumented UI tests render against" (`26-94`) already
 * found that call a dead end in this exact app, by decompiling it: it only ever reaches the platform
 * [LocaleManager] by walking a static set of *already-created* `AppCompatDelegate` instances, populated
 * exclusively by an `AppCompatActivity`, an `AppCompatDialog`, or an explicit `Activity`/`Dialog`-bound
 * `AppCompatDelegate.create(...)` call. [ago.chat.android.MainActivity] — this app's only `Activity` — is
 * a plain [androidx.activity.ComponentActivity], and nothing else in this app ever constructs one of
 * those either, so that set is permanently empty here. Calling `setApplicationLocales` against an empty
 * set does not throw — it silently falls through to a branch that only records the request for a
 * delegate *not yet created*, and never reaches [LocaleManager] at all. It would compile, run, and switch
 * nothing, which is worse than a compile error. `26-94`'s own doc comment names this exact risk for
 * whoever picked up `26-92` before it existed; this is that dead end, avoided rather than rediscovered.
 *
 * - **API 33+**: the real per-app-language platform facility, called directly on [LocaleManager] — the
 *   identical call [ago.chat.android.testing.LocaleForcingTestRunner] already makes, for the identical
 *   reason. The platform itself persists this choice (an app relaunch on a real device sees it with no
 *   help from this app's own [AppLanguagePreferences]) and recreates every one of this app's own running
 *   activities automatically once it is set — this function does not call [Activity.recreate] on this
 *   branch, because the system already will, and calling it again here would race the recreation the
 *   system's own write just triggered.
 * - **Below 33**: no such facility exists. [AppLanguagePreferences.setLanguage] — the caller's own first
 *   step, already run before this function is — is what makes the choice survive a restart on this range,
 *   read back by [ago.chat.android.MainActivity.attachBaseContext] on the next cold start (that
 *   override's own doc comment has the rest). This function's only job on this branch is the *live*
 *   half: recreating the current `Activity` so an operator's tap is seen immediately, without which the
 *   change would sit correctly persisted but invisible until the app was restarted by some other means.
 */
public fun applyAppLanguage(
    context: Context,
    language: AppLanguage,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java)?.applicationLocales =
            language.localeTag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
    } else {
        (context as? Activity)?.recreate()
    }
}

/**
 * The pre-33 half of the same feature, called from [ago.chat.android.MainActivity.attachBaseContext]
 * rather than from here — [Context.createConfigurationContext] wraps [context] so every `Resources`
 * lookup made through the returned `Context` (which is what an `Activity`'s own `attachBaseContext`
 * installs as its base) already resolves against [language] before a single string is read, the standard
 * technique every per-app locale override predating the platform's own facility uses. Returns [context]
 * itself, unwrapped, for [AppLanguage.System] — there is nothing to override when the choice is "follow
 * the device", and wrapping with whatever the device's current locale happens to be right now would
 * freeze that answer at this call's own moment instead of continuing to track it live.
 *
 * Also updates [Locale.setDefault] when overriding — [ago.chat.android.testing.LocaleForcingTestRunner]'s
 * own pre-33 branch does the same, for the same reason: [Context.createConfigurationContext] alone only
 * changes what *this* `Context`'s own `Resources` resolve against, and any code elsewhere in this process
 * reading [Locale.getDefault] directly (date/number formatting outside a Compose `stringResource` call)
 * would otherwise keep seeing the device's locale rather than the operator's chosen one.
 */
public fun wrapContextForLanguage(
    context: Context,
    language: AppLanguage,
): Context {
    val tag = language.localeTag ?: return context
    val locale = Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(locale)
    return context.createConfigurationContext(configuration)
}
