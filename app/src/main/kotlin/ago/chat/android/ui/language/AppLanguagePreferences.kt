package ago.chat.android.ui.language

import kotlinx.coroutines.flow.Flow

/**
 * `26-92`'s own single source of truth for [AppLanguage] — the identical "a port declared next to the
 * consumer, implemented over a concrete Android technology in `:app`" shape
 * [ago.chat.android.ui.theme.ThemePreferences] already establishes for the theme choice, ported for the
 * identical reason: nothing outside the UI layer ever needs to know which interface language is chosen,
 * so there is no dependency-rule reason to declare this port anywhere `:core:domain`/`:core:network`
 * could see it, and the same reason not to — `:core:domain` may hold no `androidx.*` import at all, and
 * [ago.chat.android.session.DataStoreAppLanguagePreferences] (the one implementation) is built on
 * `androidx.datastore`.
 *
 * [ago.chat.android.shell.SettingsViewModel]'s own test substitutes a fake for this interface the same
 * way it already does for [ago.chat.android.ui.theme.ThemePreferences] — this is what keeps that class
 * testable on a plain JVM with no real file behind it.
 */
public interface AppLanguagePreferences {
    /** Emits the persisted choice on every change, including one this same process just wrote — the
     * same "applied immediately, no restart, by being collected as Compose state" property
     * [ThemePreferences.mode][ago.chat.android.ui.theme.ThemePreferences.mode] states for itself. Unlike
     * theme, collecting this alone does not make a language switch visible — see [applyAppLanguage]'s
     * own doc comment for the second, Android-specific step [ago.chat.android.shell.SettingsRoute] takes
     * once this has persisted. */
    public val language: Flow<AppLanguage>

    public suspend fun setLanguage(language: AppLanguage)
}
