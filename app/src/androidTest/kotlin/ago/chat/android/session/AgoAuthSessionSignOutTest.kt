package ago.chat.android.session

import ago.chat.android.devices.DeviceRevocation
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-17`'s own Done-when: "Sign-out returns to the launch screen and leaves no token behind — proven
 * by inspecting the encrypted store afterwards, not by the UI having navigated away." An instrumented
 * test rather than a plain JVM one because [SessionStore] genuinely needs the Android Keystore
 * (`SessionStore`'s own doc comment) — there is no fake standing in for it here on purpose, since a fake
 * could not prove anything about the real, on-disk file.
 *
 * No real network call is exercised even now that `26-06` has added a device-revocation call to
 * `AgoAuthSession.signOut` — a no-op fake [DeviceRevocation] is supplied below, since this file's own
 * job is the encrypted store's own contents, not the revocation call itself
 * (`AgoAuthSessionSignOutOrderingTest` is where that call's ordering is proven). This still writes
 * directly into [SessionStore] rather than driving a real Keycloak round trip through
 * `beginAuthorization`/`completeAuthorization` — the two fields [AgoAuthSession] itself ever writes are
 * exactly the two this test seeds.
 *
 * **What running this for real on a device found, that reading the source alone would not have**: the
 * raw file is not *empty* after `clear()` - `androidx.security.crypto.EncryptedSharedPreferences`
 * deliberately keeps its own two Tink keyset entries
 * (`__androidx_security_crypto_encrypted_prefs_key_keyset__`/`..._value_keyset__`) in the same file even
 * after `Editor.clear()`, confirmed by decompiling `EncryptedSharedPreferences$Editor.clearKeysIfNeeded`,
 * which explicitly skips any key `isReservedKey` answers true for. That is the library keeping its own
 * encryption keys alive so the *same* file can be written to again without regenerating a keyset - it is
 * infrastructure, not a token, and it is not secret on its own (it is itself encrypted by the
 * Keystore-held master key, which is exactly what makes it safe to leave "in the clear" as a preference
 * key name). [KNOWN_RESERVED_KEYS] names exactly those two constants, decompiled rather than guessed, so
 * this test's own assertion is precise about what may remain rather than silently accepting "whatever
 * is left over".
 */
@RunWith(AndroidJUnit4::class)
class AgoAuthSessionSignOutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun signOutEmptiesTheEncryptedStoreCompletely() =
        runBlocking {
            val store = SessionStore(context)
            // Seeded directly - real values from a real sign-in look exactly like this once persisted;
            // `AgoAuthSession` itself never inspects their contents before clearing them.
            store.authStateJson = """{"refreshToken":"not-a-real-token-this-is-a-fixture"}"""
            store.activeSiteId = "11111111-1111-1111-1111-111111111111"
            assertTrue("the fixture must actually be written before signing out can prove anything", store.authStateJson != null)

            val session =
                AgoAuthSession(
                    context = context,
                    store = store,
                    config =
                        OidcConfig(
                            issuer = "",
                            clientId = "",
                            redirectUri = "",
                            apiBaseUrl = "",
                            consoleUrl = "",
                            calendarApiBaseUrl = null,
                        ),
                    ioDispatcher = Dispatchers.IO,
                    deviceRevocation = Lazy { NoOpDeviceRevocation },
                )

            session.signOut()

            // Through `SessionStore`'s own accessors first...
            assertNull("no auth state may remain", store.authStateJson)
            assertNull("no active site may remain", store.activeSiteId)

            // ...and independently, through the raw file itself - the same
            // `EncryptedSharedPreferences`-backed file `SessionStore.FILE_NAME` names ("ago-chat-session"),
            // opened as plain preferences here. Its own key set is inspectable with no decryption at
            // all - a key NAME here is either one of `KNOWN_RESERVED_KEYS` (plain by construction, see
            // this class's own doc comment) or an encrypted, opaque blob for `auth-state`/`active-site-id`;
            // neither the plaintext key name nor its value is ever visible unencrypted, so the real
            // assertion is "nothing beyond the library's own two reserved keys survived", proven by
            // exact key-set equality rather than merely a count.
            val rawFile = context.getSharedPreferences("ago-chat-session", Context.MODE_PRIVATE)
            assertEquals(
                "sign-out must leave nothing behind but this library's own keyset bookkeeping",
                KNOWN_RESERVED_KEYS,
                rawFile.all.keys,
            )
        }

    /** A revocation that does nothing - this file's own assertions are about [SessionStore]'s file, not
     * about whether a revocation call happened at all (that is `AgoAuthSessionSignOutOrderingTest`'s job). */
    private object NoOpDeviceRevocation : DeviceRevocation {
        override suspend fun revokeThisDevice() {
        }
    }

    private companion object {
        /** `androidx.security.crypto.EncryptedSharedPreferences`'s own two reserved key names -
         * decompiled from `EncryptedSharedPreferences.isReservedKey`/`KEY_KEYSET_ALIAS`/
         * `VALUE_KEYSET_ALIAS` in the real 1.1.0-alpha06 class, not copied from documentation. */
        val KNOWN_RESERVED_KEYS =
            setOf(
                "__androidx_security_crypto_encrypted_prefs_key_keyset__",
                "__androidx_security_crypto_encrypted_prefs_value_keyset__",
            )
    }
}
