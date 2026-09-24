package ago.chat.android.shell

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.OperatorPermissionsApi
import ago.chat.android.core.domain.permissions.PermissionsFetch
import ago.chat.android.di.IoDispatcher
import ago.chat.android.presence.OperatorPresenceController
import ago.chat.android.session.OperatorIdentity
import ago.chat.android.session.OperatorIdentityProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-16`: reads the signed-in operator's own [OperatorPermissions] once per session — the fact the
 * whole bottom navigation bar is computed from (`ago.chat.android.core.domain.navigation.visibleBottomDestinations`).
 *
 * `26-85`: also the one call site that hands that same permission set to [OperatorPresenceController] —
 * not a new fetch, the identical answer [permissions] itself renders from, so an operator's own
 * background-presence eligibility is computed from the exact set the bottom bar already trusts, never a
 * second, independently-timed read of the same endpoint.
 *
 * **Scoped to [AppShellRoute]'s own call site — the shell, not the Activity — which is exactly the
 * "one call, once, when the operator screen actually mounts" scope `ago-console`'s own
 * `PermissionsProvider` has** (mounted at its own shared layout route, not earlier). Hilt's default
 * `ViewModelStoreOwner` for a plain `@Composable` is the nearest one up the tree - here, the `NavBackStackEntry`
 * this view model's own call site sits on top of - so this fetch runs exactly once for as long as that
 * entry survives (`AppShellScreen`'s own doc comment on why that entry, unlike every bottom-tab
 * destination's, is never actually popped-and-recreated).
 *
 * ## The three states this class ever publishes, and what each one renders
 *
 * [OperatorPermissions.Unknown] is not a state to compute a navigation bar from — it is the
 * state a caller uses to decide whether to compute one **at all**. [AppShellScreen] never calls
 * `visibleBottomDestinations` while [permissions] is still [OperatorPermissions.Unknown]: it renders a
 * full-screen loading state instead ([loadError] absent) or a retry screen ([loadError] present),
 * exactly the same two-way split [ago.chat.android.signin.SignInUiState.Working]/
 * [ago.chat.android.signin.SignInUiState.Unavailable] already draw for the sign-in probes. This is
 * the answer to `docs/backlog/26-16-*.md`'s own Done-when box ("say explicitly in your report what
 * you render during the 'not loaded yet' window"): **nothing that looks like a bottom bar at all**,
 * because the alternative — drawing the safe four-destination floor and then possibly expanding to
 * five once the answer resolves — is itself "a bar that then changes under the operator", which the
 * Done-when box explicitly rules out even though the console's own `permissionsKnown` combinator
 * accepts exactly that transient window for its own, desktop-sidebar equivalent.
 */
@HiltViewModel
public class AppShellViewModel
    @Inject
    constructor(
        private val api: OperatorPermissionsApi,
        private val identityProvider: OperatorIdentityProvider,
        private val presenceController: OperatorPresenceController,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutablePermissions = MutableStateFlow<OperatorPermissions>(OperatorPermissions.Unknown)
        public val permissions: StateFlow<OperatorPermissions> = mutablePermissions.asStateFlow()

        private val mutableLoadError = MutableStateFlow<NetworkFailure?>(null)

        /**
         * `26-77`: the account menu's own header fields. `null` until the read below completes — never
         * blocking [AppShellContent] the way [permissions] genuinely does, because there is no
         * "safe floor" question riding on identity the way there is on which bottom-nav destinations to
         * draw; [ago.chat.android.ui.components.AccountAvatarAction] already renders an honest fallback
         * for a `null`/absent name, so this has nothing to gate.
         */
        private val mutableIdentity = MutableStateFlow<OperatorIdentity?>(null)
        public val identity: StateFlow<OperatorIdentity?> = mutableIdentity.asStateFlow()

        /** `null` while loading or once [permissions] has resolved — set only while [permissions] is
         * still [OperatorPermissions.Unknown] *and* the one fetch that could have resolved it failed.
         * `26-59`: [NetworkFailure]'s own classification rather than a pre-rendered `String` — this
         * class used to build one itself, reading an exception's own message for two of the three
         * cases; `AppShellScreen` renders this into a sentence instead, the same split every other
         * network-failure surface in this app now follows. */
        public val loadError: StateFlow<NetworkFailure?> = mutableLoadError.asStateFlow()

        init {
            load()
            viewModelScope.launch { mutableIdentity.value = identityProvider.currentIdentity() }
        }

        /** The retry screen's only control — re-asks the identical question, nothing else changes. */
        public fun retry() {
            mutableLoadError.value = null
            load()
        }

        private fun load() {
            viewModelScope.launch {
                when (val fetch = withContext(ioDispatcher) { api.fetchMyPermissions() }) {
                    is PermissionsFetch.Loaded -> {
                        val knownPermissions = OperatorPermissions.Known(fetch.granted)
                        mutablePermissions.value = knownPermissions
                        mutableLoadError.value = null
                        // `26-85`: the one call site that knows what this session's permission set
                        // actually is - `OperatorPresenceController` decides from it whether
                        // `OperatorPresenceService` should be running at all.
                        presenceController.onPermissionsLoaded(knownPermissions)
                    }

                    is PermissionsFetch.Failed -> {
                        // `permissions` stays `Unknown` - a failed read is not "holds nothing", and
                        // must never compute the four-destination floor as though it had answered.
                        mutableLoadError.value = fetch.reason
                    }
                }
            }
        }
    }
