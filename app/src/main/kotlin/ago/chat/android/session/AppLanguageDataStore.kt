package ago.chat.android.session

import ago.chat.android.ui.language.AppLanguage
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File

/**
 * `26-92`: the one place `language.preferences_pb`'s file path and its one key are spelled out, so
 * [ago.chat.android.di.AppModule]'s own Hilt-provided singleton and [ago.chat.android.MainActivity]'s
 * own `attachBaseContext` bootstrap read build the identical [DataStore] rather than the path being typed
 * twice and risking the two drifting apart. `MainActivity` needs its own, separately-constructed instance
 * rather than an injected one because Hilt's field injection only runs from `onCreate` onward — see that
 * override's own doc comment — and a second `DataStore` instance built from the same file is exactly as
 * safe as the first: `androidx.datastore` serialises every reader/writer against one file through its own
 * internal file lock, regardless of how many [DataStore] objects a process happens to have open on it.
 */
internal fun appLanguageDataStore(context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        produceFile = { File(context.filesDir, "language.preferences_pb") },
    )

private val APP_LANGUAGE_KEY: Preferences.Key<String> = stringPreferencesKey("app_language")

internal fun Preferences.toAppLanguage(): AppLanguage =
    AppLanguage.entries.firstOrNull { it.name == this[APP_LANGUAGE_KEY] } ?: AppLanguage.System

internal fun MutablePreferences.writeAppLanguage(language: AppLanguage) {
    this[APP_LANGUAGE_KEY] = language.name
}
