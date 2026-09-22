package ago.chat.android.session

import ago.chat.android.ui.theme.ThemeMode
import ago.chat.android.ui.theme.ThemePreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-17`'s [ThemePreferences] over `androidx.datastore` — see that interface's own doc comment for why
 * this, rather than Room or plain `SharedPreferences`.
 *
 * Takes the built [DataStore] itself, not a `Context` — the identical "depend on the narrowest thing
 * this class actually uses" shape [ago.chat.android.data.conversations.RoomConversationListCache] takes
 * a DAO rather than the whole [ago.chat.android.data.AgoChatDatabase]. It is also what makes
 * [DataStoreThemePreferencesTest] a plain JVM test with no Android `Context` anywhere in it: the
 * `di/AppModule` `@Provides` function that builds the real `DataStore<Preferences>` is the one place a
 * `Context` is read (for the file path), and that function's own body is one line — everything this
 * class does past that point is pure `DataStore` usage a temp file exercises identically to the real
 * on-device path.
 */
@Singleton
public class DataStoreThemePreferences
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) : ThemePreferences {
        override val mode: Flow<ThemeMode> =
            store.data.map { preferences ->
                val stored = preferences[KEY]
                ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.System
            }

        override suspend fun setMode(mode: ThemeMode) {
            store.edit { preferences -> preferences[KEY] = mode.name }
        }

        private companion object {
            val KEY: Preferences.Key<String> = stringPreferencesKey("theme_mode")
        }
    }
