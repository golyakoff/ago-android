package ago.chat.android.devices

import ago.chat.android.core.domain.devices.InstallationIdProvider
import ago.chat.android.di.DeviceDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-06`: [InstallationIdProvider] over its own `androidx.datastore` file - `DataStoreThemePreferences`'s
 * own doc comment states why DataStore over Room or plain `SharedPreferences` for a value like this
 * one, and the identical reasoning applies unchanged (an id, not a secret; testable on a plain JVM with
 * a real temp file and no `Context`).
 *
 * **Its own file, never `SessionStore`'s.** `SessionStore.clear()` runs on every sign-out
 * (`AgoAuthSession.completeSignOut()`, `signOut()` until `26-93` split it in two), and
 * [InstallationIdProvider]'s own doc comment states this id must
 * survive exactly that. Sharing a file with the tokens would mean either leaving this key out of
 * `clear()` - a rule a future call site can get wrong the day it forgets - or accepting that
 * "generated once per install" quietly becomes "generated once per session", silently defeating the
 * one property `docs/architecture/push-notifications.md` names as the reason `installation_id` exists
 * at all. Also **not encrypted** (unlike `SessionStore`'s file): this id is not a credential, and
 * `docs/backlog/26-06-*.md`'s own Scope states the project id it travels alongside is not secret
 * either - there is nothing here `EncryptedSharedPreferences`'s cost would be buying anything for.
 *
 * **Atomic by construction, not by a hand-rolled lock.** [DataStore.edit] serialises concurrent callers
 * against the same file already; generating the id *inside* the transform block (rather than reading,
 * deciding, then writing in a second call) is what makes two callers racing this method on first launch
 * still agree on one id rather than each writing their own UUID and the second write winning silently.
 */
@Singleton
public class DataStoreInstallationId
    @Inject
    constructor(
        @DeviceDataStore private val store: DataStore<Preferences>,
    ) : InstallationIdProvider {
        override suspend fun installationId(): String {
            val preferences =
                store.edit { preferences ->
                    if (preferences[KEY] == null) {
                        preferences[KEY] = UUID.randomUUID().toString()
                    }
                }
            return requireNotNull(preferences[KEY]) { "installationId must be set by the edit block above" }
        }

        private companion object {
            val KEY: Preferences.Key<String> = stringPreferencesKey("installation_id")
        }
    }
