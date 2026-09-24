package ago.chat.android.session

import ago.chat.android.devices.DeviceRevocation
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-06`: the ordering `docs/backlog/26-06-*.md` explicitly asks to be proven by call order, not by
 * the absence of a symptom - "sign-out calls `DELETE` before the token is discarded". Instrumented, not
 * a plain JVM test, because [SessionStore] is a real `EncryptedSharedPreferences` file, which needs a
 * real Keystore - the same reason `RoomConversationListCacheTest` needs a real device for Room.
 *
 * `26-93` split `signOut()` into [AgoAuthSession.beginSignOut]/[AgoAuthSession.completeSignOut] and
 * added its own ordering question on top of `26-06`'s: does the *new* step - Keycloak's own
 * RP-Initiated Logout round trip - ever block, or reorder, the device-revocation-then-clear sequence
 * this file already proved? [signOutBuildsAnEndSessionRoundTripButNeverBlocksLocalSignOutWhenItDoesNotComplete]
 * is that question, asked the same mechanical way: a fake collaborator that records what it observed,
 * not a hope about what should have happened.
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
            // A placeholder standing in for a real serialized `AuthState` - `completeSignOut()` never
            // parses it, only `store.clear()`'s effect on it is observed below. It carries no
            // `AuthorizationServiceConfiguration`, so `beginSignOut()` has nothing to build an
            // end-session request against and honestly answers `null` - the "no round trip to run"
            // branch [signOutBuildsAnEndSessionRoundTripButNeverBlocksLocalSignOutWhenItDoesNotComplete]
            // below exercises the other branch of.
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
                            postLogoutRedirectUri = "ago-android://logout-callback",
                            apiBaseUrl = "https://example.invalid",
                            consoleUrl = "https://example.invalid",
                            calendarApiBaseUrl = null,
                        ),
                    ioDispatcher = Dispatchers.IO,
                    deviceRevocation = Lazy { revocation },
                )

            val endSessionIntent = session.beginSignOut()
            assertNull("no configuration was ever persisted, so there is nothing to end at the IdP", endSessionIntent)

            session.completeSignOut(endSessionIntent)

            assertTrue(
                "revokeThisDevice must run while the session is still readable, not after it is cleared",
                authStateWasStillPresentWhenRevoked,
            )
            assertEquals(null, store.authStateJson)

            store.clear()
        }

    /**
     * `26-93`'s own Done-when: "a failure of the end-session call ... does not prevent local sign-out
     * from completing". This seeds a real `AuthorizationServiceConfiguration` (with an
     * `endSessionEndpoint`, the way a real Keycloak discovery document would) and a real id token, so
     * [AgoAuthSession.beginSignOut] takes the *other* branch this time and hands back a genuine
     * `Intent` to launch - proving the round trip is really attempted when there is one to attempt,
     * not merely that it is skippable. It then calls [AgoAuthSession.completeSignOut] with `null`,
     * standing in for every way that round trip can end without a parsed response - a dismissed
     * Custom Tab, or a network/Keycloak failure the browser could not redirect back from - and proves
     * device revocation and the local clear still ran, in the same order `26-06`'s own test proves.
     *
     * What this test does not and cannot prove without a real browser: that the `Intent` actually
     * opens Keycloak's real `end_session_endpoint` and that Keycloak's own SSO cookie is really gone
     * afterwards - the two boxes this item's own Done-when leaves for a real device.
     */
    @Test
    fun signOutBuildsAnEndSessionRoundTripButNeverBlocksLocalSignOutWhenItDoesNotComplete() =
        runTest {
            val store = SessionStore(context)
            val configuration =
                AuthorizationServiceConfiguration(
                    Uri.parse("https://example.invalid/realms/ago-chat/protocol/openid-connect/auth"),
                    Uri.parse("https://example.invalid/realms/ago-chat/protocol/openid-connect/token"),
                    null,
                    Uri.parse("https://example.invalid/realms/ago-chat/protocol/openid-connect/logout"),
                )
            val authorizationRequest =
                AuthorizationRequest
                    .Builder(configuration, "ago-android", ResponseTypeValues.CODE, Uri.parse("ago-android://callback"))
                    .build()
            val authState = AuthState(configuration)
            authState.update(
                AuthorizationResponse
                    .Builder(authorizationRequest)
                    .setIdToken("not-a-real-jwt-this-is-a-fixture")
                    .build(),
                null,
            )
            store.authStateJson = authState.jsonSerializeString()

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
                            postLogoutRedirectUri = "ago-android://logout-callback",
                            apiBaseUrl = "https://example.invalid",
                            consoleUrl = "https://example.invalid",
                            calendarApiBaseUrl = null,
                        ),
                    ioDispatcher = Dispatchers.IO,
                    deviceRevocation = Lazy { revocation },
                )

            val endSessionIntent = session.beginSignOut()
            assertNotNull(
                "a real config with an end_session_endpoint and an id token must produce a round trip to run",
                endSessionIntent,
            )

            // Standing in for a dismissed Custom Tab or a realm the browser could never reach - neither
            // may block what follows.
            session.completeSignOut(null)

            assertTrue(
                "revokeThisDevice must run while the session is still readable, not after it is cleared, " +
                    "even when the end-session round trip never completed",
                authStateWasStillPresentWhenRevoked,
            )
            assertEquals(null, store.authStateJson)

            store.clear()
        }
}
