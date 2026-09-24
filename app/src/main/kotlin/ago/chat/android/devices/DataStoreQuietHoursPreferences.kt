package ago.chat.android.devices

import ago.chat.android.di.DeviceDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [QuietHoursPreferences] over `@DeviceDataStore` — the identical file [DataStoreInstallationId]/
 * [DataStorePushMessageDedupeStore] already write to: a small, non-secret, device-scoped value with no
 * reason to be wiped by `SessionStore.clear()`'s own sign-out path (an operator signing back in on the
 * same phone should not have to re-teach it their quiet hours) and no reason for a fourth on-disk file
 * to exist just for three fields. Not the theme file either — [DataStoreThemePreferences]'s own
 * `theme.preferences_pb` is a UI preference this value happens to resemble in shape, but the device file's
 * own "keeps living past a sign-out" contract is the one this value actually needs, not the theme file's.
 */
@Singleton
public class DataStoreQuietHoursPreferences
    @Inject
    constructor(
        @DeviceDataStore private val store: DataStore<Preferences>,
    ) : QuietHoursPreferences {
        override val settings: Flow<QuietHoursSettings> =
            store.data.map { preferences ->
                QuietHoursSettings(
                    enabled = preferences[KEY_ENABLED] ?: false,
                    startMinuteOfDay = preferences[KEY_START] ?: QuietHoursSettings.DEFAULT_START_MINUTE,
                    endMinuteOfDay = preferences[KEY_END] ?: QuietHoursSettings.DEFAULT_END_MINUTE,
                )
            }

        override suspend fun setSettings(settings: QuietHoursSettings) {
            store.edit { preferences ->
                preferences[KEY_ENABLED] = settings.enabled
                preferences[KEY_START] = settings.startMinuteOfDay
                preferences[KEY_END] = settings.endMinuteOfDay
            }
        }

        private companion object {
            val KEY_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("quiet_hours_enabled")
            val KEY_START: Preferences.Key<Int> = intPreferencesKey("quiet_hours_start_minute")
            val KEY_END: Preferences.Key<Int> = intPreferencesKey("quiet_hours_end_minute")
        }
    }
