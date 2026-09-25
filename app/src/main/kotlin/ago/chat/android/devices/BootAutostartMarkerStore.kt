package ago.chat.android.devices

import ago.chat.android.di.DeviceDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-129`: the two persisted boot markers [inferAutostartBootSignal] compares.
 *
 * - [lastSeenBootTimeMillis] — the boot the app first recorded a foreground run during, the baseline that
 *   distinguishes "installed mid-session, no reboot yet" from "rebooted and blocked". `null` before any run.
 * - [lastAutostartBootTimeMillis] — the boot [BootCompletedReceiver] last recorded on `BOOT_COMPLETED`.
 *   `null` if that receiver has never fired.
 */
public data class BootMarkers(
    public val lastSeenBootTimeMillis: Long?,
    public val lastAutostartBootTimeMillis: Long?,
)

/**
 * `26-129`: persistence for the two boot markers — a port for the identical reason
 * [PushMessageDedupeStore]/[BatteryAwarenessPromptPreferences] are ports rather than raw `DataStore` reads at
 * their call sites: the receiver and the inference reader both touch it, and a plain-JVM test substitutes a
 * fake with no real file IO (`DataStoreThemePreferencesTest`'s own documented finding is why this codebase
 * keeps real single-file DataStore writes out of unit tests on this platform).
 *
 * The two markers are written by two different callers on purpose — the receiver only ever records the
 * *autostart* marker, the app's foreground open only ever records the *seen* baseline — so neither path can
 * accidentally overwrite the other's evidence. Both live in the identical `@DeviceDataStore`
 * `device.preferences_pb` file [DataStoreInstallationId]/[DataStorePushMessageDedupeStore] already write to:
 * small, non-secret, install-scoped values with no reason to survive `SessionStore.clear()`'s sign-out wipe
 * (a reboot marker is device state, not session state) and no reason for a dedicated file.
 */
public interface BootAutostartMarkerStore {
    public suspend fun read(): BootMarkers

    /** Records [bootTimeMillis] as the first-run baseline — see [BootMarkers.lastSeenBootTimeMillis]. */
    public suspend fun recordSeenBoot(bootTimeMillis: Long)

    /** Records [bootTimeMillis] as the boot for which `BOOT_COMPLETED` fired — see [BootMarkers.lastAutostartBootTimeMillis]. */
    public suspend fun recordAutostartBoot(bootTimeMillis: Long)
}

@Singleton
public class DataStoreBootAutostartMarkerStore
    @Inject
    constructor(
        @DeviceDataStore private val store: DataStore<Preferences>,
    ) : BootAutostartMarkerStore {
        override suspend fun read(): BootMarkers {
            val preferences = store.data.first()
            return BootMarkers(
                lastSeenBootTimeMillis = preferences[KEY_LAST_SEEN_BOOT],
                lastAutostartBootTimeMillis = preferences[KEY_LAST_AUTOSTART_BOOT],
            )
        }

        override suspend fun recordSeenBoot(bootTimeMillis: Long) {
            store.edit { preferences -> preferences[KEY_LAST_SEEN_BOOT] = bootTimeMillis }
        }

        override suspend fun recordAutostartBoot(bootTimeMillis: Long) {
            store.edit { preferences -> preferences[KEY_LAST_AUTOSTART_BOOT] = bootTimeMillis }
        }

        private companion object {
            val KEY_LAST_SEEN_BOOT: Preferences.Key<Long> = longPreferencesKey("autostart_last_seen_boot_millis")
            val KEY_LAST_AUTOSTART_BOOT: Preferences.Key<Long> = longPreferencesKey("autostart_last_autostart_boot_millis")
        }
    }
