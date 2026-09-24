package ago.chat.android.presence

import ago.chat.android.di.DeviceDataStore
import ago.chat.android.di.IoDispatcher
import android.content.Context
import android.os.PowerManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-85`: "ask once; if refused, the app must still work" (`docs/backlog/26-85-*.md`'s own Scope) — the
 * state machine behind that sentence. `PowerManager.isIgnoringBatteryOptimizations` alone cannot tell
 * "never asked" from "asked and refused" apart (both read `false`), so [shouldRequestExemption] also
 * consults a persisted flag only [markRequested] ever sets — the identical `@DeviceDataStore` file
 * [ago.chat.android.devices.DataStoreQuietHoursPreferences]/[ago.chat.android.devices.DataStoreInstallationId]
 * already write a small, non-secret, device-scoped value to, for the identical "survives a sign-out on
 * this same phone" reason those two classes' own doc comments state: an operator who was already asked
 * once, refused, and later signs out and back in on the identical device must never be asked a second
 * time.
 */
public interface BatteryOptimizationGate {
    /** `false` whenever asking would be pointless or repetitive: the OS already exempts this app, or
     * this class has already asked once before — regardless of what the operator chose, since this class
     * has no reliable way to read that choice back (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` gives
     * no dependable result), and `docs/backlog/26-85-*.md`'s own scope only ever asks for "once", never
     * "once per refusal". */
    public suspend fun shouldRequestExemption(): Boolean

    /** Records that the one-time ask has happened — called the moment [OperatorPresenceController]
     * decides to fire the request event, not after any reply. */
    public suspend fun markRequested()
}

@Singleton
public class AndroidBatteryOptimizationGate
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @DeviceDataStore private val store: DataStore<Preferences>,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : BatteryOptimizationGate {
        override suspend fun shouldRequestExemption(): Boolean =
            withContext(ioDispatcher) {
                // `getSystemService` answering `null` is not a documented real-world case for
                // `PowerManager` - treated as "already exempted" (never ask) rather than "unknown",
                // matching this whole class's own fail-quiet-not-fail-noisy contract.
                val alreadyExempted =
                    context
                        .getSystemService(PowerManager::class.java)
                        ?.isIgnoringBatteryOptimizations(context.packageName)
                        ?: true
                if (alreadyExempted) {
                    return@withContext false
                }

                val alreadyAsked = store.data.first()[KEY_REQUESTED] ?: false
                !alreadyAsked
            }

        override suspend fun markRequested() {
            withContext(ioDispatcher) {
                store.edit { preferences -> preferences[KEY_REQUESTED] = true }
            }
        }

        private companion object {
            val KEY_REQUESTED: Preferences.Key<Boolean> = booleanPreferencesKey("battery_optimization_exemption_requested")
        }
    }
