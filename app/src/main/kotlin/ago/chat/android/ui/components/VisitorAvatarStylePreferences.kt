package ago.chat.android.ui.components

import kotlinx.coroutines.flow.Flow

/**
 * `26-285`'s own single source of truth for [VisitorAvatarStyle] — the identical "a port declared next
 * to the consumer, implemented over a concrete Android technology in `:app`" shape
 * [ago.chat.android.ui.theme.ThemePreferences] already establishes for Тема, scaled down to one value
 * with no cross-module reader. It lives in `:app`, beside [VisitorAvatar] rather than in
 * `:core:domain`, for the identical dependency-rule reason that interface's own doc comment states:
 * nothing outside the UI layer ever reads which avatar style is chosen, and the implementation is built
 * on `androidx.datastore`, which `:core:domain` (a plain Kotlin JVM module) may never import.
 *
 * [DataStoreVisitorAvatarStylePreferences] is the one implementation; a fake standing in for this
 * interface is what lets [ago.chat.android.shell.SettingsViewModel]'s own test assert against a plain
 * in-memory [kotlinx.coroutines.flow.MutableStateFlow] rather than a real file.
 */
public interface VisitorAvatarStylePreferences {
    /** Emits the persisted choice on every change — including one this same process just wrote —
     * which is what lets [ago.chat.android.MainActivity] apply a change immediately, with no restart,
     * simply by collecting this as Compose state, the identical mechanism
     * [ago.chat.android.ui.theme.ThemePreferences.mode]'s own doc comment states for Тема. */
    public val style: Flow<VisitorAvatarStyle>

    public suspend fun setStyle(style: VisitorAvatarStyle)
}
