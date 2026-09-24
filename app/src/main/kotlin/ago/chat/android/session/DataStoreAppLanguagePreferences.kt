package ago.chat.android.session

import ago.chat.android.di.LanguageDataStore
import ago.chat.android.ui.language.AppLanguage
import ago.chat.android.ui.language.AppLanguagePreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-92`'s [AppLanguagePreferences] over `androidx.datastore` — [DataStoreThemePreferences]'s own doc
 * comment already states, in full, why this and not Room or plain `SharedPreferences`; the same reasoning
 * applies unchanged to a second, unrelated preference. A dedicated `@LanguageDataStore`-qualified file
 * (`app/src/main/kotlin/ago/chat/android/session/AppLanguageDataStore.kt`) rather than
 * [DataStoreThemePreferences]'s own `theme.preferences_pb` — the two are read at genuinely different
 * points in this app's own startup (this one synchronously, from
 * [ago.chat.android.MainActivity.attachBaseContext], before Hilt has injected anything at all; theme
 * only ever reactively, from inside `setContent`), and sharing one file would couple two preferences that
 * happen to have nothing to do with each other beyond both living on the Settings screen.
 */
@Singleton
public class DataStoreAppLanguagePreferences
    @Inject
    constructor(
        @LanguageDataStore private val store: DataStore<Preferences>,
    ) : AppLanguagePreferences {
        override val language: Flow<AppLanguage> = store.data.map { it.toAppLanguage() }

        override suspend fun setLanguage(language: AppLanguage) {
            store.edit { preferences -> preferences.writeAppLanguage(language) }
        }
    }
