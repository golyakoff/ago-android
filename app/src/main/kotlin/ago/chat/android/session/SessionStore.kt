package ago.chat.android.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything this app persists about a signed-in session, in `EncryptedSharedPreferences` —
 * `docs/architecture.md`: *"Tokens are held in `EncryptedSharedPreferences`; the refresh token never
 * leaves the device and never appears in a log."*
 *
 * **Why encrypted preferences rather than plain ones.** The refresh token is a long-lived credential
 * against a long SSO session; a plain `SharedPreferences` file is readable by anything with root or
 * a backup of the device's data directory. `androidx.security.crypto` wraps the file in envelope
 * encryption with the key held in the Android Keystore, which is hardware-backed where the device
 * has a TEE. Hand-rolling that — a Keystore-held AES key, a per-entry IV, authenticated encryption —
 * is precisely the kind of security plumbing this project reaches for a real dependency over.
 *
 * **Why one store for both the tokens and the active site**, rather than the tokens here and the
 * site id in ordinary preferences: sign-out has to leave nothing behind, and one `clear()` that
 * empties one file is a guarantee, where two stores cleared in sequence is an ordering somebody can
 * later get wrong. The site id is not itself a secret — it is a GUID the server hands out — so this
 * costs an encryption pass on a value that does not need one, which is a price worth a simpler
 * sign-out.
 *
 * `android:allowBackup="false"` is already set on the manifest (`26-07`), so none of this leaves the
 * device through Android's own backup transport either.
 */
@Singleton
public class SessionStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /**
         * `by lazy` rather than eager: building this touches the Keystore and reads a file, and the
         * one thing that must not happen is that work landing on the main thread during
         * `Application.onCreate`. Every caller below is reached from a coroutine on an IO
         * dispatcher — `SignInViewModel` is explicit about that, and it is the reason it is.
         */
        private val preferences: SharedPreferences by lazy {
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        /** AppAuth's own serialised `AuthState`: the tokens, their expiry, and the realm's endpoints. */
        public var authStateJson: String?
            get() = preferences.getString(KEY_AUTH_STATE, null)
            set(value) = preferences.edit().putString(KEY_AUTH_STATE, value).apply()

        /** The tenancy every request names, written by the site picker and by `26-17`'s switcher. */
        public var activeSiteId: String?
            get() = preferences.getString(KEY_ACTIVE_SITE, null)
            set(value) = preferences.edit().putString(KEY_ACTIVE_SITE, value).apply()

        /**
         * Empties the whole file. Called by `AgoAuthSession.signOut`, which is the only caller —
         * see that function for why `26-06`'s device revocation has to happen *before* this runs.
         */
        public fun clear() {
            preferences.edit().clear().apply()
        }

        private companion object {
            const val FILE_NAME = "ago-chat-session"
            const val KEY_AUTH_STATE = "auth-state"
            const val KEY_ACTIVE_SITE = "active-site-id"
        }
    }
