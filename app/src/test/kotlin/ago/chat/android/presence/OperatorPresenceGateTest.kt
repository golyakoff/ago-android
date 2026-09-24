package ago.chat.android.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-85`: [DefaultOperatorPresenceGate]'s own tiny state machine - `false` until [OperatorPresenceGate.activate]
 * runs, and back to `false` the moment [OperatorPresenceGate.deactivate] does, with no other transition
 * possible. Small on purpose: everything this class is trusted for by
 * [ago.chat.android.realtime.OperatorHubConnectionLifecycle] is exactly these three lines.
 */
class OperatorPresenceGateTest {
    @Test
    fun `a fresh gate starts inactive`() {
        val gate = DefaultOperatorPresenceGate()

        assertFalse(gate.isActive)
    }

    @Test
    fun `activate makes isActive true`() {
        val gate = DefaultOperatorPresenceGate()

        gate.activate()

        assertTrue(gate.isActive)
    }

    @Test
    fun `deactivate makes isActive false again`() {
        val gate = DefaultOperatorPresenceGate()
        gate.activate()

        gate.deactivate()

        assertFalse(gate.isActive)
    }

    @Test
    fun `deactivating an already-inactive gate is a harmless no-op`() {
        val gate = DefaultOperatorPresenceGate()

        gate.deactivate()

        assertFalse(gate.isActive)
    }
}
