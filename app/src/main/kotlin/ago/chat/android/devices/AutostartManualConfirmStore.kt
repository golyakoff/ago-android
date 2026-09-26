package ago.chat.android.devices

import ago.chat.android.di.DeviceDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-187`: the operator's own claim that autostart is on, persisted so a correctly-configured phone on a
 * restrictive OEM is not stuck showing [AutostartUiState.Recommended] until its next reboot happens to
 * produce [AutostartBootSignal.AutostartConfirmed]. A sibling port to [BootAutostartMarkerStore], not a
 * third field folded into it: that store's own two markers are both *observations* one specific receiver or
 * reader writes ([BootAutostartMarkerStore]'s own doc comment on why the two markers are written by two
 * different callers on purpose); this one is neither — it is a person's statement, set and cleared from the
 * Settings screen itself, and giving it its own small port keeps "who writes this and why" answerable by the
 * type alone rather than by which of `BootMarkers`' fields a caller happens to touch. It still lives in the
 * identical `@DeviceDataStore` `device.preferences_pb` file every other small, non-secret, install-scoped
 * flag here already shares — the same reasoning [BootAutostartMarkerStore] states for its own two keys.
 *
 * **Never the last word on its own.** [SettingsViewModel][ago.chat.android.shell.SettingsViewModel]'s own
 * `reloadAutostartUiState` clears this flag the moment a boot is observed to have been [Blocked
 * ][AutostartBootSignal.Blocked] — a real, unfavourable observation always outranks an operator's earlier,
 * now-contradicted claim (see [resolveAutostartUiState]'s own precedence).
 */
public interface AutostartManualConfirmStore {
    /** `false` until an operator has ever checked the box, or after a [AutostartBootSignal.Blocked]
     * observation cleared it — the safe "nothing claimed" default, matching every other boolean flag in
     * this app that starts unset. */
    public suspend fun read(): Boolean

    public suspend fun setConfirmed(confirmed: Boolean)
}

@Singleton
public class DataStoreAutostartManualConfirmStore
    @Inject
    constructor(
        @DeviceDataStore private val store: DataStore<Preferences>,
    ) : AutostartManualConfirmStore {
        override suspend fun read(): Boolean = store.data.first()[KEY_MANUALLY_CONFIRMED] ?: false

        override suspend fun setConfirmed(confirmed: Boolean) {
            store.edit { preferences -> preferences[KEY_MANUALLY_CONFIRMED] = confirmed }
        }

        private companion object {
            val KEY_MANUALLY_CONFIRMED: Preferences.Key<Boolean> = booleanPreferencesKey("autostart_manually_confirmed")
        }
    }
