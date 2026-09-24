package ago.chat.android.realtime

import ago.chat.android.core.network.realtime.HubConnectionControl
import ago.chat.android.di.IoDispatcher
import ago.chat.android.presence.OperatorPresenceGate
import ago.chat.android.signin.SignInSession
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `docs/architecture.md` §Realtime: "Lifecycle tied to the app being in the foreground... The app
 * connects when it is in front and lets go when it is not." This class is the one place that binds
 * `OperatorHubConnection`'s own `connect()`/`disconnect()` — reached through [HubConnectionControl], the
 * identical testability seam [ago.chat.android.core.network.realtime.OperatorHubEvents] already
 * establishes for the read half — to that signal.
 *
 * **`ProcessLifecycleOwner`, not `MainActivity`'s own `onStart`/`onStop`.** This app has exactly one
 * `Activity` today, but its lifecycle is still the wrong signal: a device rotation destroys and
 * recreates that `Activity`, firing `onStop` on the way out and `onStart` on the way back in, and
 * neither transition is "the app left the foreground" — treating them as such is exactly the shape
 * that would fail this item's own "rotating the device does not drop or duplicate the connection"
 * Done-when box. `ProcessLifecycleOwner` reports the *process's* foreground state, unaffected by any
 * one `Activity` recreating — a rotation produces no lifecycle event on it at all.
 *
 * Gated on [SignInSession.hasSession] rather than connecting unconditionally: "one connection per
 * signed-in session" (this item's own Scope) means no session, no connection attempt — a signed-out
 * app foregrounding into the sign-in screen has no token to negotiate with and nothing worth reporting
 * a `Reconnecting` state for.
 *
 * A `@Singleton` constructed once and registered with `ProcessLifecycleOwner` from
 * `AgoChatApplication.onCreate` — never per-screen, since a screen registering its own observer is the
 * exact "a screen that opens its own [connection]" shape `docs/backlog/26-13-*.md`'s own Scope warns
 * against.
 *
 * `26-85`: [onStop] no longer disconnects unconditionally. `docs/backlog/26-84-*.md` found that this
 * class's own "let go when it is not [in front]" rule is exactly what silently released every
 * conversation an operator held within thirty seconds of locking their phone — `26-85`'s own fix is
 * `OperatorPresenceService`, a foreground service that keeps the connection open across an ordinary
 * backgrounding for any identity holding `conversation:send`. [presenceGate] is how the two classes
 * agree on who currently owns [disconnect]: while it reports [OperatorPresenceGate.isActive], calling
 * `disconnect()` here would immediately undo the one thing that service exists to prevent, so [onStop]
 * skips it and leaves the connection running. [onStart] still always attempts [connect] unconditionally
 * — reconnecting on foreground is correct regardless of which component last held the connection, and
 * [HubConnectionControl.connect]'s own idempotence makes a redundant call harmless.
 */
@Singleton
public class OperatorHubConnectionLifecycle
    @Inject
    constructor(
        private val connection: HubConnectionControl,
        private val session: SignInSession,
        private val presenceGate: OperatorPresenceGate,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : DefaultLifecycleObserver {
        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

        /** Called once, from `AgoChatApplication.onCreate` — registering more than once would add a
         * second observer and call `connect()`/`disconnect()` twice per transition, which is harmless
         * given both are idempotent but is not the intended shape. */
        public fun start() {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }

        override fun onStart(owner: LifecycleOwner) {
            scope.launch {
                if (session.hasSession()) {
                    connection.connect()
                }
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            if (presenceGate.isActive) {
                // `26-85`: `OperatorPresenceService` owns this connection's lifecycle for the whole
                // background window now - see this class's own doc comment.
                return
            }
            scope.launch { connection.disconnect() }
        }
    }
