package ago.chat.android.signin

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.identity.PostSignInRouter
import ago.chat.android.core.domain.identity.SignInDestination
import ago.chat.android.core.network.realtime.OperatorHubConnection
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.di.IoDispatcher
import ago.chat.android.session.SignInFailedException
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-12`: the pre-session state machine — sign in, route, pick a site, sign out.
 *
 * Thin on purpose. The decision this item is actually about lives in `PostSignInRouter`
 * (`:core:domain`, tested without Android); what is here is the part that genuinely needs a
 * lifecycle: launching an `Intent`, surviving the Activity that launched it, and holding one state
 * for Compose to render.
 *
 * **Why the routing runs on an IO dispatcher rather than on `viewModelScope`'s default.** The router
 * reads [ActiveSiteSelection], which reads `EncryptedSharedPreferences`, which decrypts — and
 * `viewModelScope` is `Dispatchers.Main.immediate`. Every hop out of this class is explicit for that
 * reason, and the dispatcher is injected so a test never touches a real thread pool.
 */
@HiltViewModel
public class SignInViewModel
    @Inject
    constructor(
        private val session: SignInSession,
        private val router: PostSignInRouter,
        private val activeSite: ActiveSiteSelection,
        private val hubConnection: OperatorHubConnection,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<SignInUiState>(SignInUiState.Starting)
        public val state: StateFlow<SignInUiState> = mutableState.asStateFlow()

        /**
         * `26-13`'s own "a minimal connection-state surface" — a plain relay onto the app's one
         * `OperatorHubConnection.state`, threaded down to [SignedInScreen]'s debug row
         * (`HubConnectionDebugRow`). Disconnecting stays `OperatorHubConnectionLifecycle`'s own job,
         * tied to the process foreground rather than to whichever screen happens to be visible — this
         * class's own [routeNow] additionally *connects* it the moment sign-in resolves to
         * `Operator`, a real-device gap `OperatorHubConnectionLifecycle`'s own foreground-only wiring
         * left open (see that function's doc comment).
         */
        public val hubConnectionState: StateFlow<OperatorHubConnectionState> = hubConnection.state

        /**
         * Authorization `Intent`s for the Activity to launch. A `Channel` rather than a `StateFlow`
         * because an `Intent` is an event: re-collected after a configuration change, a state would
         * open a second Custom Tab for a sign-in already in progress.
         */
        private val authorizationIntents = Channel<Intent>(Channel.BUFFERED)
        public val authorizationRequests: Flow<Intent> = authorizationIntents.receiveAsFlow()

        init {
            resumeSession()
        }

        /** At every app start: is there a session, and if so where does this identity belong? */
        public fun resumeSession() {
            viewModelScope.launch {
                mutableState.value = SignInUiState.Working
                mutableState.value =
                    if (session.hasSession()) {
                        routeNow()
                    } else {
                        SignInUiState.SignedOut
                    }
            }
        }

        public fun beginSignIn() {
            viewModelScope.launch {
                mutableState.value = SignInUiState.Working
                try {
                    authorizationIntents.send(session.beginAuthorization())
                } catch (failure: SignInFailedException) {
                    mutableState.value = SignInUiState.SignInFailed(failure.message ?: "")
                }
            }
        }

        /**
         * The result of the Custom Tab. `null` data is the operator backing out of the browser,
         * which is not a failure and must not render as one — the same judgement `11-17` made about
         * a replayed callback: "not an error at all in the ordinary case".
         */
        public fun onAuthorizationResult(data: Intent?) {
            viewModelScope.launch {
                if (data == null) {
                    mutableState.value = SignInUiState.SignedOut
                    return@launch
                }

                mutableState.value = SignInUiState.Working
                try {
                    session.completeAuthorization(data)
                } catch (failure: SignInFailedException) {
                    mutableState.value = SignInUiState.SignInFailed(failure.message ?: "")
                    return@launch
                }

                mutableState.value = routeNow()
            }
        }

        /** The site picker's answer. Written first, then the tree is re-run with it in place. */
        public fun chooseSite(siteId: String) {
            viewModelScope.launch {
                mutableState.value = SignInUiState.Working
                withContext(ioDispatcher) { activeSite.select(siteId) }
                mutableState.value = routeNow()
            }
        }

        /** The retry arm's only control. Re-asks the same questions; nothing else changes. */
        public fun retry() {
            resumeSession()
        }

        public fun signOut() {
            viewModelScope.launch {
                mutableState.value = SignInUiState.Working
                session.signOut()
                mutableState.value = SignInUiState.SignedOut
            }
        }

        /**
         * A real device found what no unit test could: `OperatorHubConnectionLifecycle`'s own
         * process-foreground binding assumes finishing the Custom Tab OAuth round trip produces a
         * fresh `ProcessLifecycleOwner.onStart` - the natural place `connect()` would otherwise fire
         * from. On at least one real device it does not happen reliably, so an operator who just
         * signed in landed on the conversation list with the hub never connected at all, surviving a
         * retry and a sign-out/sign-in cycle identically, since neither produces that transition
         * either. [OperatorHubConnection.connect] is documented idempotent (a no-op once already
         * connecting/connected), so calling it here as well - the moment routing actually resolves to
         * [SignInDestination.Operator] - costs nothing on the path that already worked and fixes the
         * path that did not. Launched rather than awaited, on its own `viewModelScope` child, and
         * with its own `catch`: a transient network failure here must never fail *sign-in* itself, or
         * surface as an uncaught exception on a fire-and-forget best-effort attempt
         * (`hub.start().await()` can throw). The hub's own `state` flow, already surfaced to the UI,
         * is where a failed connect belongs - `OperatorHubConnectionLifecycle`'s own foreground-driven
         * retry, or an operator backgrounding and reopening the app, is what tries again.
         */
        private suspend fun routeNow(): SignInUiState =
            when (val destination = withContext(ioDispatcher) { router.route() }) {
                is SignInDestination.Operator -> {
                    viewModelScope.launch(ioDispatcher) {
                        runCatching { hubConnection.connect() }
                    }
                    SignInUiState.SignedIn(destination.activeSiteId)
                }
                is SignInDestination.ChooseSite -> SignInUiState.ChooseSite(destination.tenancies)
                SignInDestination.PlatformOwnerTerminal -> SignInUiState.PlatformOwnerTerminal
                SignInDestination.Registration -> SignInUiState.Registration
                is SignInDestination.Unavailable -> SignInUiState.Unavailable(destination.failure)
            }
    }
