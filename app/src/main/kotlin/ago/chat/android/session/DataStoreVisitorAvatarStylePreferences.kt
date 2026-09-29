package ago.chat.android.session

import ago.chat.android.ui.components.VisitorAvatarStyle
import ago.chat.android.ui.components.VisitorAvatarStylePreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-285`'s own [VisitorAvatarStylePreferences] over `androidx.datastore` — [DataStoreThemePreferences]'s
 * own doc comment states why this, rather than Room or plain `SharedPreferences`, and this class copies
 * that shape exactly.
 *
 * **Reuses [DataStoreThemePreferences]'s own unqualified `DataStore<Preferences>`** (the file
 * `di/AppModule.provideThemeDataStore` builds, `"theme.preferences_pb"`) rather than a second,
 * dedicated file the way `"26-92"`'s own `@LanguageDataStore` isolates the language file — both Тема
 * and this preference are pure UI state read at the `MainActivity` root with nothing pre-`onCreate`
 * depending on either, unlike the language file `attachBaseContext` reads before `setContent` ever
 * runs, so there is no correctness reason for a second file, only a cosmetic one (the file's own name
 * now undersells its contents — left as-is, since renaming the provider is unrelated scope creep for
 * this item). A distinct key (`"visitor_avatar_style"`), not `KEY` shared with Тема's own `"theme_mode"`,
 * is what keeps the two values from colliding inside that one file.
 */
@Singleton
public class DataStoreVisitorAvatarStylePreferences
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) : VisitorAvatarStylePreferences {
        override val style: Flow<VisitorAvatarStyle> =
            store.data.map { preferences ->
                val stored = preferences[KEY]
                VisitorAvatarStyle.entries.firstOrNull { it.name == stored } ?: VisitorAvatarStyle.Emoji
            }

        override suspend fun setStyle(style: VisitorAvatarStyle) {
            store.edit { preferences -> preferences[KEY] = style.name }
        }

        private companion object {
            val KEY: Preferences.Key<String> = stringPreferencesKey("visitor_avatar_style")
        }
    }
