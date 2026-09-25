package ago.chat.android.devices

import ago.chat.android.di.DeviceDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-128`: the first-launch battery/autostart sheet's own "don't show again" flag — a small,
 * non-secret, device-scoped `Boolean` with no reason to be wiped by `SessionStore.clear()`'s own
 * sign-out path (an operator signing back in on the same phone should not see the sheet again), and no
 * reason for a dedicated file: `@DeviceDataStore`'s `device.preferences_pb` is the identical file
 * [DataStoreQuietHoursPreferences]/[DataStoreInstallationId]/[DataStorePushMessageDedupeStore] already
 * write similarly-shaped small values to, for the same reasoning each of their own doc comments states.
 *
 * **Not the same flag as [ago.chat.android.presence.BatteryOptimizationGate].** That class tracks
 * whether the presence feature's own one-time system exemption *request* has fired; this tracks whether
 * *this* sheet — a different screen, a different recommendation (battery **and** autostart), reachable the
 * first time the operator opens the signed-in app rather than when presence needs a foreground service —
 * has been dismissed with the checkbox checked. Folding the two together would make dismissing one
 * silently suppress the other, with no operator-visible cause.
 */
public interface BatteryAwarenessPromptPreferences {
    public val dismissed: Flow<Boolean>

    public suspend fun setDismissed(dismissed: Boolean)
}

@Singleton
public class DataStoreBatteryAwarenessPromptPreferences
    @Inject
    constructor(
        @DeviceDataStore private val store: DataStore<Preferences>,
    ) : BatteryAwarenessPromptPreferences {
        override val dismissed: Flow<Boolean> = store.data.map { preferences -> preferences[KEY_DISMISSED] ?: false }

        override suspend fun setDismissed(dismissed: Boolean) {
            store.edit { preferences -> preferences[KEY_DISMISSED] = dismissed }
        }

        private companion object {
            val KEY_DISMISSED: Preferences.Key<Boolean> = booleanPreferencesKey("battery_awareness_prompt_dismissed")
        }
    }
