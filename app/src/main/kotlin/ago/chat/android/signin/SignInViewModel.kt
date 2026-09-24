package ago.chat.android.signin

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.identity.PostSignInRouter
import ago.chat.android.core.domain.identity.SignInDestination
import ago.chat.android.core.network.realtime.OperatorHubConnection
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.devices.DeviceRegistrar
import ago.chat.android.devices.DeviceRegistrationScheduler
import ago.chat.android.devices.PushAvailability
import ago.chat.android.di.IoDispatcher
import ago.chat.android.presence.OperatorPresenceController
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
        private val deviceRegistrar: DeviceRegistrar,
        private val registrationScheduler: DeviceRegistrationScheduler,
        private val presenceController: OperatorPresenceController,
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
         * `26-06`: a plain relay onto [DeviceRegistrar.pushAvailability] - the identical
         * `hubConnectionState` shape immediately above, so a future screen (`26-19`) can bind to a
         * real diagnostic with no new plumbing. `null` until [routeNow] has actually asked once.
         */
        public val pushAvailability: StateFlow<PushAvailability?> = deviceRegistrar.pushAvailability

        /**
         * Authorization `Intent`s for the Activity to launch. A `Channel` rather than a `StateFlow`
         * because an `Intent` is an event: re-collected after a configuration change, a state would
         * open a second Custom Tab for a sign-in already in progress.
         */
        private val authorizationIntents = Channel<Intent>(Channel.BUFFERED)
        public val authorizationRequests: Flow<Intent> = authorizationIntents.receiveAsFlow()

        /**
         * `26-18`: "asked at the moment it means something rather than at first launch"
         * (`docs/backlog/26-18-*.md`'s own Scope). **The moment chosen is every sign-in that resolves to
         * [SignInDestination.Operator]** - the identical moment [routeNow] already registers this device
         * for push at all: an operator who has just been told "you can receive visitor conversations on
         * this phone" is being asked about the one permission that makes that promise real, which is a
         * stronger connection to the benefit than "first app launch" (before any session, any site, or
         * any conversation exists) could ever state. Fired on **every** such sign-in rather than once ever,
         * which is deliberately harmless rather than merely tolerated: `ActivityResultContracts
         * .RequestPermission()` on an already-granted permission resolves instantly with no dialog shown
         * (`MainActivity`'s own doc comment on its launcher), and a permission the operator has explicitly
         * denied shows nothing either past Android's own "don't ask again" state - so the only real
         * prompt an operator ever sees from this is the first one.
         *
         * A `Channel`, the identical "an event, not a state, or a rotation replays it" reasoning
         * [authorizationRequests] above already states, restated for a system permission dialog instead
         * of a Custom Tab.
         */
        private val notificationPermissionRequests = Channel<Unit>(Channel.BUFFERED)
        public val requestNotificationPermissionEvents: Flow<Unit> = notificationPermissionRequests.receiveAsFlow()

        /**
         * `26-93`: [authorizationRequests]'s own "an `Intent` is an event" shape, restated for the
         * end-session Custom Tab [signOut] opens instead of the sign-in one. Only emitted when
         * [SignInSession.beginSignOut] actually returns an `Intent` — when it returns `null` (no IdP
         * session worth ending), [signOut] finishes locally with no Custom Tab and nothing is sent
         * here at all.
         */
        private val signOutIntents = Channel<Intent>(Channel.BUFFERED)
        public val signOutRequests: Flow<Intent> = signOutIntents.receiveAsFlow()

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

        /**
         * `26-93`: step one only. [SignInSession.beginSignOut] either has nothing to do at the IdP
         * (`null` - [finishSignOut] runs immediately, no Custom Tab) or hands back an `Intent` this
         * has to route to [signOutRequests] for `MainActivity` to launch, the identical split
         * [beginSignIn] already makes for sign-in.
         */
        public fun signOut() {
            viewModelScope.launch {
                mutableState.value = SignInUiState.Working
                when (val intent = session.beginSignOut()) {
                    null -> finishSignOut(null)
                    else -> signOutIntents.send(intent)
                }
            }
        }

        /**
         * The end-session Custom Tab's result, `26-93`. Unlike [onAuthorizationResult], `data` is never
         * inspected here either - [finishSignOut] (via [SignInSession.completeSignOut]) treats a
         * completed round trip, a Keycloak/network failure, and a dismissed Custom Tab identically,
         * for the reason [ago.chat.android.session.AgoAuthSession.completeSignOut]'s own doc comment
         * states: this app's local sign-out must never depend on how the browser's part of it went.
         */
        public fun onSignOutResult(data: Intent?) {
            viewModelScope.launch { finishSignOut(data) }
        }

        private suspend fun finishSignOut(data: Intent?) {
            session.completeSignOut(data)
            // `26-85`: stops `OperatorPresenceService` and lowers `OperatorPresenceGate` - a
            // signed-out identity has no permission set worth keeping a background connection open
            // for, and `AppShellViewModel`'s own `NavBackStackEntry` (the only other caller of
            // `OperatorPresenceController`) is torn down by this same sign-out with nothing left to
            // fetch permissions again on.
            presenceController.onSignedOut()
            mutableState.value = SignInUiState.SignedOut
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
                    // `26-06`: **every sign-in**, the first of the three call sites
                    // `docs/architecture/push-notifications.md` names. `schedulePeriodicRegistration()`
                    // is synchronous and idempotent (`ExistingPeriodicWorkPolicy.KEEP`) so it runs here
                    // directly rather than inside the launch below; `registerThisDevice()` is launched
                    // the identical fire-and-forget way `hubConnection.connect()` is immediately above -
                    // a slow or failed push registration must never fail sign-in itself, the same
                    // reasoning that launch's own doc comment states for the hub connect attempt.
                    registrationScheduler.schedulePeriodicRegistration()
                    viewModelScope.launch(ioDispatcher) {
                        runCatching { deviceRegistrar.registerThisDevice() }
                    }
                    // `26-18`: fire-and-forget, the identical shape as the two calls immediately above -
                    // `notificationPermissionRequests`'s own doc comment states why every sign-in is the
                    // right moment rather than only the first one ever.
                    notificationPermissionRequests.trySend(Unit)
                    SignInUiState.SignedIn(destination.activeSiteId)
                }
                is SignInDestination.ChooseSite -> SignInUiState.ChooseSite(destination.tenancies)
                SignInDestination.PlatformOwnerTerminal -> SignInUiState.PlatformOwnerTerminal
                SignInDestination.Registration -> SignInUiState.Registration
                is SignInDestination.Unavailable -> SignInUiState.Unavailable(destination.failure)
            }
    }
