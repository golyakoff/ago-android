package ago.chat.android.session

import ago.chat.android.core.network.auth.AccessTokenProvider
import ago.chat.android.di.IoDispatcher
import ago.chat.android.signin.SignInSession
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import org.json.JSONException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A failure of the Keycloak round trip itself, as distinct from anything that happens after it. */
public class SignInFailedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The app's OIDC session: Authorization Code + PKCE against the `ago-android` realm client `26-11`
 * created, in a **Custom Tab**, with the resulting tokens in [SessionStore].
 *
 * ## Why AppAuth, and why never a WebView
 *
 * `docs/architecture.md`'s stack table names the alternative and rejects it: embedding a WebView
 * login is what OAuth 2.0 for Native Apps (RFC 8252) exists to stop — the app would be able to read
 * the operator's keystrokes at the identity provider, there is no shared SSO session with the
 * system browser, and the user cannot see the address bar they are being asked to trust. AppAuth
 * launches the system browser's Custom Tab, and PKCE is what proves the code being exchanged was
 * requested by *this* app rather than by whatever else on the device registered the same custom
 * scheme (`26-11`'s own reasoning for choosing `ago-android://callback` over an App Link).
 *
 * ## Why the endpoints are discovered rather than typed out
 *
 * [beginAuthorization] fetches the realm's OIDC discovery document instead of hardcoding
 * `/protocol/openid-connect/auth` and `/token`. That costs one request at sign-in and buys a client
 * that is right about a realm it did not have to encode assumptions about. Its failure is
 * deliberately a *sign-in* failure and nothing else — `11-17` is the record of what it costs when
 * "Keycloak refused you" and "the call after Keycloak failed" render as the same sentence.
 *
 * ## Why every token read suspends
 *
 * `performActionWithFreshTokens` is AppAuth's own "give me a token that is valid right now": it
 * returns the current one when it has life left and refreshes off the refresh token when it does
 * not. Making [currentAccessToken] suspend is what lets the Ktor plugin ask that question *per
 * request* instead of holding a copy — the `5-16` shape, stated at [AccessTokenProvider].
 *
 * ## What is never logged
 *
 * Nothing in this class logs, at any level. Not the access token, not the refresh token, not the
 * PKCE verifier, not the authorization code, and not the serialised `AuthState` (which contains all
 * of them). `26-12`'s own Done-when asks for "no token of any kind appears in logcat at any level,
 * including verbose", and the way to have that property is for the code holding the tokens to
 * contain no logging statement at all rather than to contain careful ones.
 */
@Singleton
public class AgoAuthSession
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: SessionStore,
        private val config: OidcConfig,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : AccessTokenProvider,
        SignInSession {
        /**
         * Serialises every mutation of [state] and every token refresh. Held *across* the refresh
         * network call on purpose: two requests hitting a just-expired token should produce one
         * refresh and both wait for it, not two competing refreshes that each invalidate the other's
         * result.
         */
        private val mutex = Mutex()

        /**
         * Created lazily because constructing it binds to the browser's Custom Tabs service. Never
         * disposed: it lives exactly as long as the process, which is what a `@Singleton` here
         * means, and `AuthorizationService.dispose()` exists for the Activity-scoped usage this is
         * deliberately not.
         */
        private val authorizationService: AuthorizationService by lazy { AuthorizationService(context) }

        private var state: AuthState? = null

        /** Whether there is a session to route on at all — the first question at every app start. */
        override suspend fun hasSession(): Boolean = withContext(ioDispatcher) { mutex.withLock { loadState().isAuthorized } }

        /**
         * Builds the authorization request and hands back the `Intent` that opens it in a Custom
         * Tab. The caller (`MainActivity`) launches it and returns the result to
         * [completeAuthorization]; the PKCE code verifier travels inside that `Intent` pair and is
         * never held by this class.
         */
        override suspend fun beginAuthorization(): Intent =
            withContext(ioDispatcher) {
                val serviceConfiguration = discoverConfiguration()
                val request =
                    AuthorizationRequest
                        .Builder(
                            serviceConfiguration,
                            config.clientId,
                            ResponseTypeValues.CODE,
                            Uri.parse(config.redirectUri),
                        ).setScope(SCOPE)
                        .build()

                mutex.withLock {
                    // A fresh `AuthState` carrying only the discovered endpoints: starting a new
                    // sign-in discards whatever half-finished one preceded it.
                    state = AuthState(serviceConfiguration)
                    persist()
                }

                authorizationService.getAuthorizationRequestIntent(request)
            }

        /**
         * Exchanges the authorization code for tokens.
         *
         * Throws [SignInFailedException] for every way the identity provider's own round trip can
         * fail, so the caller can say "sign-in failed" for those and *only* those — the split
         * `11-17` had to introduce in `ago-console` after a CORS-refused call **after** a successful
         * sign-in was reported as a sign-in failure and sent two people looking in the wrong place.
         */
        override suspend fun completeAuthorization(data: Intent): Unit =
            withContext(ioDispatcher) {
                val response = AuthorizationResponse.fromIntent(data)
                val failure = AuthorizationException.fromIntent(data)

                if (response == null) {
                    throw SignInFailedException(failure?.errorDescription ?: failure?.error ?: "authorization was not completed")
                }

                val tokens =
                    try {
                        exchange(response)
                    } catch (cause: AuthorizationException) {
                        throw SignInFailedException(cause.errorDescription ?: cause.error ?: "token exchange failed", cause)
                    }

                mutex.withLock {
                    loadState().update(response, null)
                    loadState().update(tokens, null)
                    persist()
                }
            }

        override suspend fun currentAccessToken(): String? = withContext(ioDispatcher) { mutex.withLock { freshAccessToken() } }

        override suspend fun refreshAccessToken(): String? =
            withContext(ioDispatcher) {
                mutex.withLock {
                    // Forced, rather than left to the expiry check. A server that rejects a token it
                    // should have accepted — clock skew, a revoked session, a realm restart — is
                    // exactly the case `AuthState`'s own "does it look expired" cannot see.
                    loadState().needsTokenRefresh = true
                    freshAccessToken()
                }
            }

        /**
         * Discards the session and returns the app to its launch screen.
         *
         * **The ordering is the part that matters, and it is `26-06`'s to extend.** Device
         * revocation — telling the backend to forget this installation's push registration — is an
         * *authenticated* call, so it has to happen while the token is still here, at the point
         * marked below. That is why this function suspends and why nothing is cleared before that
         * point: a non-suspending `signOut()` would make inserting an awaited network call a
         * signature change rippling through every caller, and clearing first would make the
         * revocation impossible to send at all. This item does not implement the revocation — it
         * leaves the one shape in which it can be added without moving anything.
         */
        override suspend fun signOut(): Unit =
            withContext(ioDispatcher) {
                mutex.withLock {
                    // `26-06` inserts the device-revocation call HERE — the token is still valid and
                    // still readable, and nothing below has run yet.

                    state = AuthState()
                    store.clear()
                }
            }

        // ------------------------------------------------------------------------------ internals

        private fun loadState(): AuthState {
            val existing = state
            if (existing != null) {
                return existing
            }

            val restored =
                store.authStateJson?.let { json ->
                    try {
                        AuthState.jsonDeserialize(json)
                    } catch (failure: JSONException) {
                        // A store this app cannot read is a store with no session in it. Discarding
                        // it costs one sign-in; trusting a half-parsed one costs a confusing loop.
                        null
                    }
                } ?: AuthState()

            state = restored
            return restored
        }

        private fun persist() {
            store.authStateJson = loadState().jsonSerializeString()
        }

        private suspend fun freshAccessToken(): String? {
            val current = loadState()
            if (!current.isAuthorized) {
                return null
            }

            val token =
                suspendCancellableCoroutine { continuation ->
                    current.performActionWithFreshTokens(authorizationService) { accessToken, _, failure ->
                        if (continuation.isActive) {
                            // A failure here is "no token right now", not an exception: the caller
                            // is a request that will go out unauthenticated and be answered by the
                            // server, which is a truer answer than one this client invented.
                            continuation.resume(if (failure == null) accessToken else null)
                        }
                    }
                }

            persist()
            return token
        }

        private suspend fun discoverConfiguration(): AuthorizationServiceConfiguration =
            suspendCancellableCoroutine { continuation ->
                AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(config.issuer)) { configuration, failure ->
                    if (!continuation.isActive) {
                        return@fetchFromIssuer
                    }
                    if (configuration != null) {
                        continuation.resume(configuration)
                    } else {
                        continuation.resumeWithException(
                            SignInFailedException(
                                failure?.errorDescription ?: failure?.error ?: "could not read the identity provider's configuration",
                                failure,
                            ),
                        )
                    }
                }
            }

        private suspend fun exchange(response: AuthorizationResponse): TokenResponse =
            suspendCancellableCoroutine { continuation ->
                authorizationService.performTokenRequest(response.createTokenExchangeRequest()) { tokens, failure ->
                    if (!continuation.isActive) {
                        return@performTokenRequest
                    }
                    if (tokens != null) {
                        continuation.resume(tokens)
                    } else {
                        continuation.resumeWithException(failure ?: SignInFailedException("token exchange returned nothing"))
                    }
                }
            }

        private companion object {
            /**
             * The same three scopes `ago-console`'s `userManager.ts` requests. Nothing product-
             * specific: `Ago.Chat.Api` reads `sub` and resolves everything else from its own
             * database (`adr/0068`), so there is no scope this client could ask for that would make
             * the token say more about tenancy.
             */
            const val SCOPE = "openid profile email"
        }
    }
