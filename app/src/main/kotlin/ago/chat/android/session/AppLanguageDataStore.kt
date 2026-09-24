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
 * `26-92`: the one place `language.preferences_pb`'s file path and its one key are spelled out, shared
 * by [ago.chat.android.di.AppModule]'s Hilt provider and [ago.chat.android.MainActivity]'s own
 * `attachBaseContext` bootstrap read.
 *
 * `26-92` follow-up (crash fix): this used to build the [DataStore] with a bare
 * `PreferenceDataStoreFactory.create` on every call. Both `attachBaseContext` (at activity creation) and
 * the Hilt provider (when [SettingsViewModel] is created — i.e. opening Settings) call it for the *same*
 * file, so a **second active `DataStore` for one file** was created and androidx.datastore threw
 * `IllegalStateException: There are multiple DataStores active for the same file` — crashing the app the
 * moment Настройки opened (`0.25.0`). androidx forbids more than one active instance per file per
 * process; the earlier "a second instance is exactly as safe as the first" note was wrong. The
 * process-wide, double-checked singleton below hands every caller — whatever `Context` it holds — the
 * one instance, built from `applicationContext.filesDir` so the path never depends on the caller.
 */
@Volatile
private var appLanguageDataStoreInstance: DataStore<Preferences>? = null
private val appLanguageDataStoreLock = Any()

internal fun appLanguageDataStore(context: Context): DataStore<Preferences> =
    appLanguageDataStoreInstance ?: synchronized(appLanguageDataStoreLock) {
        appLanguageDataStoreInstance ?: PreferenceDataStoreFactory
            .create(produceFile = { File(context.applicationContext.filesDir, "language.preferences_pb") })
            .also { appLanguageDataStoreInstance = it }
    }

private val APP_LANGUAGE_KEY: Preferences.Key<String> = stringPreferencesKey("app_language")

internal fun Preferences.toAppLanguage(): AppLanguage =
    AppLanguage.entries.firstOrNull { it.name == this[APP_LANGUAGE_KEY] } ?: AppLanguage.System

internal fun MutablePreferences.writeAppLanguage(language: AppLanguage) {
    this[APP_LANGUAGE_KEY] = language.name
}
