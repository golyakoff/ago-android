package ago.chat.android.presence

import ago.chat.android.core.domain.permissions.OperatorPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `26-85`'s own crux: does [DefaultOperatorPresenceController] start [OperatorPresenceService] for the
 * right identities, and only those, and does the battery-optimisation exemption get asked exactly once?
 * A plain JVM test - [ForegroundServiceLauncher]/[BatteryOptimizationGate] are both narrow, framework-free
 * interfaces precisely so this class's whole decision logic is provable with no `Service`, no `Context`,
 * and no real Android runtime anywhere in the way (`DefaultOperatorPresenceController`'s own doc
 * comment).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultOperatorPresenceControllerTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an identity holding conversation-send starts the service and activates the gate`() =
        runTest(dispatcher) {
            val launcher = FakeForegroundServiceLauncher()
            val gate = DefaultOperatorPresenceGate()
            val controller = controllerWith(launcher = launcher, gate = gate)

            controller.onPermissionsLoaded(OperatorPermissions.Known(setOf("conversation:send")))
            advanceUntilIdle()

            assertEquals(1, launcher.startCalls)
            assertEquals(0, launcher.stopCalls)
            assertTrue(gate.isActive)
        }

    /** The one Done-when box `docs/backlog/26-85-*.md` states in these exact words: "The foreground
     * service does not start for an identity holding no `conversation:send` permission." */
    @Test
    fun `an identity without conversation-send never starts the service`() =
        runTest(dispatcher) {
            val launcher = FakeForegroundServiceLauncher()
            val gate = DefaultOperatorPresenceGate()
            val controller = controllerWith(launcher = launcher, gate = gate)

            controller.onPermissionsLoaded(OperatorPermissions.Known(setOf("customer:read", "site:configure")))
            advanceUntilIdle()

            assertEquals(0, launcher.startCalls)
            assertEquals(1, launcher.stopCalls)
            assertFalse(gate.isActive)
        }

    @Test
    fun `an identity with an empty grant set never starts the service`() =
        runTest(dispatcher) {
            val launcher = FakeForegroundServiceLauncher()
            val controller = controllerWith(launcher = launcher)

            controller.onPermissionsLoaded(OperatorPermissions.Known(emptySet()))
            advanceUntilIdle()

            assertEquals(0, launcher.startCalls)
        }

    /** [OperatorPermissions.Unknown] answers `false` for every [ago.chat.android.core.domain.permissions.holds]
     * check by design - this controller must never treat "not read yet" as "holds it", the identical
     * fail-closed direction [OperatorPermissions]'s own doc comment states for the navigation bar. */
    @Test
    fun `an Unknown permission set never starts the service`() =
        runTest(dispatcher) {
            val launcher = FakeForegroundServiceLauncher()
            val controller = controllerWith(launcher = launcher)

            controller.onPermissionsLoaded(OperatorPermissions.Unknown)
            advanceUntilIdle()

            assertEquals(0, launcher.startCalls)
        }

    @Test
    fun `signing out stops the service and deactivates the gate even if the service was never started`() =
        runTest(dispatcher) {
            val launcher = FakeForegroundServiceLauncher()
            val gate = DefaultOperatorPresenceGate()
            val controller = controllerWith(launcher = launcher, gate = gate)

            controller.onSignedOut()
            advanceUntilIdle()

            assertEquals(1, launcher.stopCalls)
            assertFalse(gate.isActive)
        }

    @Test
    fun `signing out after a running session stops the service and deactivates the gate`() =
        runTest(dispatcher) {
            val launcher = FakeForegroundServiceLauncher()
            val gate = DefaultOperatorPresenceGate()
            val controller = controllerWith(launcher = launcher, gate = gate)
            controller.onPermissionsLoaded(OperatorPermissions.Known(setOf("conversation:send")))
            advanceUntilIdle()

            controller.onSignedOut()
            advanceUntilIdle()

            assertEquals(1, launcher.startCalls)
            assertEquals(1, launcher.stopCalls)
            assertFalse(gate.isActive)
        }

    /** "Ask once; if refused, the app must still work" (`docs/backlog/26-85-*.md`'s own Scope) - the
     * half of that sentence this controller owns: firing the request exactly when
     * [BatteryOptimizationGate.shouldRequestExemption] says to, and marking it asked in the same breath. */
    @Test
    fun `starting the service requests the battery exemption when the gate says to`() =
        runTest(dispatcher) {
            val batteryGate = FakeBatteryOptimizationGate(shouldRequest = true)
            val controller = controllerWith(batteryGate = batteryGate)

            val received = mutableListOf<Unit>()
            val collector = launch { controller.requestBatteryOptimizationExemptionEvents.toList(received) }

            controller.onPermissionsLoaded(OperatorPermissions.Known(setOf("conversation:send")))
            advanceUntilIdle()
            collector.cancel()

            assertEquals(1, received.size)
            assertEquals(1, batteryGate.markRequestedCalls)
        }

    /** Already exempted, or already asked before - either way [BatteryOptimizationGate] says no, and
     * this controller must not nag regardless. */
    @Test
    fun `starting the service never requests the exemption when the gate says not to`() =
        runTest(dispatcher) {
            val batteryGate = FakeBatteryOptimizationGate(shouldRequest = false)
            val controller = controllerWith(batteryGate = batteryGate)

            val received = mutableListOf<Unit>()
            val collector = launch { controller.requestBatteryOptimizationExemptionEvents.toList(received) }

            controller.onPermissionsLoaded(OperatorPermissions.Known(setOf("conversation:send")))
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, received.size)
            assertEquals(0, batteryGate.markRequestedCalls)
        }

    /** An identity with no `conversation:send` never starts the service at all, so there is nothing to
     * ask a battery-optimisation exemption for either - the exemption exists to protect a running
     * foreground service, not a bare permission read. */
    @Test
    fun `an identity without conversation-send never requests the battery exemption either`() =
        runTest(dispatcher) {
            val batteryGate = FakeBatteryOptimizationGate(shouldRequest = true)
            val controller = controllerWith(batteryGate = batteryGate)

            val received = mutableListOf<Unit>()
            val collector = launch { controller.requestBatteryOptimizationExemptionEvents.toList(received) }

            controller.onPermissionsLoaded(OperatorPermissions.Known(emptySet()))
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, received.size)
            assertEquals(0, batteryGate.markRequestedCalls)
        }

    private fun controllerWith(
        launcher: ForegroundServiceLauncher = FakeForegroundServiceLauncher(),
        gate: OperatorPresenceGate = DefaultOperatorPresenceGate(),
        batteryGate: BatteryOptimizationGate = FakeBatteryOptimizationGate(shouldRequest = false),
    ): DefaultOperatorPresenceController =
        DefaultOperatorPresenceController(
            serviceLauncher = launcher,
            presenceGate = gate,
            batteryOptimizationGate = batteryGate,
            ioDispatcher = dispatcher,
        )

    private class FakeForegroundServiceLauncher : ForegroundServiceLauncher {
        var startCalls: Int = 0
            private set
        var stopCalls: Int = 0
            private set

        override fun start() {
            startCalls++
        }

        override fun stop() {
            stopCalls++
        }
    }

    private class FakeBatteryOptimizationGate(
        private val shouldRequest: Boolean,
    ) : BatteryOptimizationGate {
        var markRequestedCalls: Int = 0
            private set

        override suspend fun shouldRequestExemption(): Boolean = shouldRequest

        override suspend fun markRequested() {
            markRequestedCalls++
        }
    }
}
