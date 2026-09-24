package ago.chat.android.realtime

import ago.chat.android.core.network.realtime.HubConnectionControl
import ago.chat.android.presence.OperatorPresenceGate
import ago.chat.android.signin.SignInSession
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * `26-85`'s own reason this file exists at all: [OperatorHubConnectionLifecycle.onStop] used to
 * disconnect unconditionally on every backgrounding, which `docs/backlog/26-84-*.md` traced to every
 * conversation an operator held being silently released within thirty seconds of locking their phone.
 * The fix this suite proves is exactly one `if`: [OperatorHubConnectionLifecycle.onStop] must skip
 * [HubConnectionControl.disconnect] while [OperatorPresenceGate.isActive] is `true`, and must still call
 * it the moment that gate is `false` again.
 *
 * A plain JVM test against [OperatorHubConnectionLifecycle.onStart]/`onStop` directly, with no
 * `ProcessLifecycleOwner` in play — [OperatorHubConnectionLifecycle.start] is the only member that reads
 * one, and neither lifecycle callback under test reads its own `owner` parameter, so a
 * [FakeLifecycleOwner] whose [Lifecycle] is never touched is enough. [HubConnectionControl] (`26-85`'s
 * own addition, alongside [ago.chat.android.core.network.realtime.OperatorHubEvents]) is what makes
 * [connect]/[disconnect] fakeable at all — the concrete `OperatorHubConnection` this class depended on
 * before could only ever be tested against a real (if unreachable) hub URL, the same gap
 * `OperatorHubEvents`'s own doc comment already names for a different caller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OperatorHubConnectionLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private val owner = FakeLifecycleOwner()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `foregrounding a signed-in session connects the hub`() =
        runTest(dispatcher) {
            val connection = FakeHubConnectionControl()
            val lifecycle = lifecycleWith(connection = connection, hasSession = true)

            lifecycle.onStart(owner)
            advanceUntilIdle()

            assertEquals(1, connection.connectCalls)
        }

    @Test
    fun `foregrounding a signed-out app never connects`() =
        runTest(dispatcher) {
            val connection = FakeHubConnectionControl()
            val lifecycle = lifecycleWith(connection = connection, hasSession = false)

            lifecycle.onStart(owner)
            advanceUntilIdle()

            assertEquals(0, connection.connectCalls)
        }

    @Test
    fun `backgrounding disconnects when no presence service is keeping the connection alive`() =
        runTest(dispatcher) {
            val connection = FakeHubConnectionControl()
            val lifecycle = lifecycleWith(connection = connection, presenceGate = FakeOperatorPresenceGate(initiallyActive = false))

            lifecycle.onStop(owner)
            advanceUntilIdle()

            assertEquals(1, connection.disconnectCalls)
        }

    /** `26-85`'s own reason to exist: proves the one line that keeps the whole item's promise - an
     * operator whose identity holds `conversation:send` and whose `OperatorPresenceService` is running
     * survives the exact transition that used to release every conversation they held within `26-84`'s
     * own 30-second grace period. */
    @Test
    fun `backgrounding never disconnects while OperatorPresenceService owns the connection`() =
        runTest(dispatcher) {
            val connection = FakeHubConnectionControl()
            val lifecycle = lifecycleWith(connection = connection, presenceGate = FakeOperatorPresenceGate(initiallyActive = true))

            lifecycle.onStop(owner)
            advanceUntilIdle()

            assertEquals(0, connection.disconnectCalls)
        }

    /** The gate is read at the moment `onStop` fires, not cached from some earlier point - a service
     * that stopped (sign-out, or a permission fetch that no longer grants `conversation:send`) between
     * two backgroundings must see the disconnect resume on the very next one. */
    @Test
    fun `disconnect resumes on the next backgrounding once the presence gate deactivates`() =
        runTest(dispatcher) {
            val connection = FakeHubConnectionControl()
            val gate = FakeOperatorPresenceGate(initiallyActive = true)
            val lifecycle = lifecycleWith(connection = connection, presenceGate = gate)

            lifecycle.onStop(owner)
            advanceUntilIdle()
            assertEquals(0, connection.disconnectCalls)

            gate.deactivate()
            lifecycle.onStop(owner)
            advanceUntilIdle()

            assertEquals(1, connection.disconnectCalls)
        }

    private fun lifecycleWith(
        connection: HubConnectionControl,
        hasSession: Boolean = true,
        presenceGate: OperatorPresenceGate = FakeOperatorPresenceGate(initiallyActive = false),
    ): OperatorHubConnectionLifecycle =
        OperatorHubConnectionLifecycle(
            connection = connection,
            session = FakeSession(hasSession),
            presenceGate = presenceGate,
            ioDispatcher = dispatcher,
        )

    private class FakeLifecycleOwner : LifecycleOwner {
        override val lifecycle: Lifecycle
            get() = throw UnsupportedOperationException("not exercised here - onStart/onStop never read their own owner")
    }

    private class FakeSession(
        private val hasSession: Boolean,
    ) : SignInSession {
        override suspend fun hasSession(): Boolean = hasSession

        override suspend fun beginAuthorization(): Intent = throw UnsupportedOperationException("not exercised here")

        override suspend fun completeAuthorization(data: Intent): Unit = throw UnsupportedOperationException("not exercised here")

        override suspend fun signOut(): Unit = throw UnsupportedOperationException("not exercised here")
    }

    private class FakeHubConnectionControl : HubConnectionControl {
        var connectCalls: Int = 0
            private set
        var disconnectCalls: Int = 0
            private set

        override suspend fun connect() {
            connectCalls++
        }

        override suspend fun disconnect() {
            disconnectCalls++
        }
    }

    private class FakeOperatorPresenceGate(
        initiallyActive: Boolean,
    ) : OperatorPresenceGate {
        @Volatile
        private var active: Boolean = initiallyActive

        override val isActive: Boolean
            get() = active

        override fun activate() {
            active = true
        }

        override fun deactivate() {
            active = false
        }
    }
}
