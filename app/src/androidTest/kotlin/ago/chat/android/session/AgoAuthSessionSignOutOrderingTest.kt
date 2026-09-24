package ago.chat.android.session

import ago.chat.android.devices.DeviceRevocation
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-06`: the ordering `docs/backlog/26-06-*.md` explicitly asks to be proven by call order, not by
 * the absence of a symptom - "sign-out calls `DELETE` before the token is discarded". Instrumented, not
 * a plain JVM test, because [SessionStore] is a real `EncryptedSharedPreferences` file, which needs a
 * real Keystore - the same reason `RoomConversationListCacheTest` needs a real device for Room.
 *
 * **How the order is actually proven, not merely asserted.** The fake [DeviceRevocation] below reads
 * [SessionStore.authStateJson] *at the moment it is called* and records whether it was still present.
 * Had `AgoAuthSession.signOut()`'s two steps run in the opposite order, `store.clear()` would already
 * have emptied the file by the time this fake ran, and `authStateWasStillPresentWhenRevoked` would read
 * `false` - a real, mechanical failure a reversed implementation cannot pass, not a hoped-for one.
 *
 * **This sandbox has no emulator, so this test is proven to compile
 * (`./gradlew assembleDebugAndroidTest`) but not proven to pass on a real device or CI runner** - that
 * proof is the managing session's to run, the same status this session's own convention gives every
 * other real-device-dependent check in this item.
 */
@RunWith(AndroidJUnit4::class)
class AgoAuthSessionSignOutOrderingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // `26-06`: camelCase, not this project's usual backtick-quoted sentence -
    // `DeviceRegistrationWorkerTest`'s own doc comment states why an androidTest method name may not
    // contain a space (it becomes part of a DEX'd class name for the test's own suspend-lambda
    // continuation).
    @Test
    fun signOutRevokesThisDeviceWhileTheStoredSessionIsStillPresentThenClearsIt() =
        runTest {
            val store = SessionStore(context)
            // A placeholder standing in for a real serialized `AuthState` - `signOut()` never parses
            // it, only `store.clear()`'s effect on it is observed below.
            store.authStateJson = "{}"

            var authStateWasStillPresentWhenRevoked = false
            val revocation =
                object : DeviceRevocation {
                    override suspend fun revokeThisDevice() {
                        authStateWasStillPresentWhenRevoked = store.authStateJson != null
                    }
                }

            val session =
                AgoAuthSession(
                    context = context,
                    store = store,
                    config =
                        OidcConfig(
                            issuer = "https://example.invalid/realms/ago-chat",
                            clientId = "ago-android",
                            redirectUri = "ago-android://callback",
                            apiBaseUrl = "https://example.invalid",
                            consoleUrl = "https://example.invalid",
                            calendarApiBaseUrl = null,
                        ),
                    ioDispatcher = Dispatchers.IO,
                    deviceRevocation = Lazy { revocation },
                )

            session.signOut()

            assertTrue(
                "revokeThisDevice must run while the session is still readable, not after it is cleared",
                authStateWasStillPresentWhenRevoked,
            )
            assertEquals(null, store.authStateJson)

            store.clear()
        }
}
