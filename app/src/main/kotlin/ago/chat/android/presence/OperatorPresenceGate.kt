package ago.chat.android.presence

import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-85`: the one fact [ago.chat.android.realtime.OperatorHubConnectionLifecycle] needs in order to
 * stop disconnecting the hub on every backgrounding — whether [OperatorPresenceService] is the one
 * currently responsible for [ago.chat.android.core.network.realtime.OperatorHubConnection]'s own
 * lifecycle for this session. [ago.chat.android.devices.ProcessLifecycleForegroundTracker]'s own shape
 * (`@Volatile`, a plain `Boolean`, no `StateFlow`) restated for a second, unrelated signal: both readers
 * only ever want a synchronous "is it true right now", never a stream of future changes.
 *
 * A narrow, framework-free interface — [OperatorPresenceController] is the only writer
 * ([activate]/[deactivate]), [ago.chat.android.realtime.OperatorHubConnectionLifecycle] the only reader
 * ([isActive]) — kept separate from [OperatorPresenceController] itself so a test of the lifecycle
 * observer can substitute a trivial fake with no `Service`, no `Context`, and no battery-optimisation
 * logic in play at all.
 */
public interface OperatorPresenceGate {
    /** `true` from the moment [OperatorPresenceController] has told [OperatorPresenceService] to start
     * for this session until sign-out or a permission fetch that no longer grants
     * `conversation:send` — see that class's own doc comment for exactly when each transition fires. */
    public val isActive: Boolean

    public fun activate()

    public fun deactivate()
}

@Singleton
public class DefaultOperatorPresenceGate
    @Inject
    constructor() : OperatorPresenceGate {
        @Volatile
        private var active: Boolean = false

        override val isActive: Boolean
            get() = active

        override fun activate() {
            active = true
        }

        override fun deactivate() {
            active = false
        }
    }
