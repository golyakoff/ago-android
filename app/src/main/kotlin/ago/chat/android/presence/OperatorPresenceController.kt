package ago.chat.android.presence

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.Permission
import ago.chat.android.core.domain.permissions.holds
import ago.chat.android.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-85`'s own orchestrator — the one class that turns "here is what the signed-in identity's
 * permission set holds" and "the operator just signed out" into [OperatorPresenceService] actually
 * starting or stopping, [OperatorPresenceGate] flipping in step with it, and, when it means something,
 * one request to leave Doze alone.
 *
 * **Why this is not simply inlined into [ago.chat.android.shell.AppShellViewModel].** That view model's
 * own scope is rendering a bottom nav bar from a permission set — a UI concern — and this item's own
 * gating rule ("holds `conversation:send`, not merely signed in") is a decision about an Android
 * component with its own lifecycle, wholly independent of any screen. Splitting it out is what makes
 * this class's own state machine (start/stop, the battery-exemption "ask once") testable with fakes for
 * [ForegroundServiceLauncher]/[BatteryOptimizationGate] and no `AppShellViewModel`, Compose, or
 * `NavBackStackEntry` in play at all — the identical "a screen calls a narrow port; the port's own tests
 * do not need the screen" split every other Android-facing interface in this app already draws
 * ([ago.chat.android.devices.DeviceRegistrationScheduler] is the precedent this class's own split from
 * [ForegroundServiceLauncher] follows).
 */
public interface OperatorPresenceController {
    /** [ago.chat.android.MainActivity]'s own event to launch
     * `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` from — a `Flow`, the identical
     * `Channel`-backed "an event, not a state, or a rotation replays it" shape
     * [ago.chat.android.signin.SignInViewModel.requestNotificationPermissionEvents] already establishes
     * for the sibling permission request `26-18` added. */
    public val requestBatteryOptimizationExemptionEvents: Flow<Unit>

    /** [ago.chat.android.shell.AppShellViewModel]'s own call, once per successful
     * `GET /api/v1/operators/me` read — starts [OperatorPresenceService] and requests the
     * battery-optimisation exemption (the first time, for an identity never asked before) when
     * [permissions] holds [Permission.CONVERSATION_SEND]; stops the service and lowers
     * [OperatorPresenceGate] otherwise. */
    public fun onPermissionsLoaded(permissions: OperatorPermissions)

    /** [ago.chat.android.signin.SignInViewModel.signOut]'s own call — stops the service and lowers
     * [OperatorPresenceGate] unconditionally, regardless of what [onPermissionsLoaded] last decided. */
    public fun onSignedOut()
}

@Singleton
public class DefaultOperatorPresenceController
    @Inject
    constructor(
        private val serviceLauncher: ForegroundServiceLauncher,
        private val presenceGate: OperatorPresenceGate,
        private val batteryOptimizationGate: BatteryOptimizationGate,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : OperatorPresenceController {
        // `OperatorHubConnectionLifecycle`'s own shape: a `@Singleton`'s own long-lived scope, never
        // `viewModelScope` — this class outlives every `AppShellViewModel` instance a rotation or a
        // sign-out/sign-in cycle can create.
        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

        private val mutableExemptionRequests = Channel<Unit>(Channel.BUFFERED)
        override val requestBatteryOptimizationExemptionEvents: Flow<Unit> = mutableExemptionRequests.receiveAsFlow()

        override fun onPermissionsLoaded(permissions: OperatorPermissions) {
            if (!permissions.holds(Permission.CONVERSATION_SEND)) {
                stopPresence()
                return
            }

            serviceLauncher.start()
            presenceGate.activate()
            scope.launch {
                if (batteryOptimizationGate.shouldRequestExemption()) {
                    batteryOptimizationGate.markRequested()
                    mutableExemptionRequests.trySend(Unit)
                }
            }
        }

        override fun onSignedOut() {
            stopPresence()
        }

        private fun stopPresence() {
            presenceGate.deactivate()
            serviceLauncher.stop()
        }
    }
