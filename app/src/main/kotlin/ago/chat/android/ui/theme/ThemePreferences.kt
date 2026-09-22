package ago.chat.android.ui.theme

import kotlinx.coroutines.flow.Flow

/**
 * `26-17`'s own single source of truth for [ThemeMode] — the identical "a port declared next to the
 * consumer, implemented over a concrete Android technology in `:app`" shape
 * [ago.chat.android.core.domain.identity.ActiveSiteSelection] already establishes for the active site,
 * scaled down to one value with no cross-module reader. It lives in `:app` rather than `:core:domain`
 * because nothing outside the UI layer ever needs to know which theme is chosen — unlike the active
 * site, no HTTP request or hub connection reads it — so there is no dependency-rule reason to declare it
 * where `:core:network`/`:core:domain` could see it, and every reason not to: `ThemePreferences` is
 * implemented over `androidx.datastore`, and `:core:domain` may hold no `androidx.*` import at all
 * (`docs/architecture.md`, "Module layout").
 *
 * [DataStoreThemePreferences] is the one implementation; a fake standing in for this interface is what
 * lets [ago.chat.android.shell.SettingsViewModel]'s own test assert against a plain in-memory
 * [kotlinx.coroutines.flow.MutableStateFlow] rather than a real file.
 */
public interface ThemePreferences {
    /** Emits the persisted choice on every change — including one this same process just wrote —
     * which is what lets [ago.chat.android.MainActivity] apply a change immediately, with no restart,
     * simply by collecting this as Compose state. */
    public val mode: Flow<ThemeMode>

    public suspend fun setMode(mode: ThemeMode)
}
