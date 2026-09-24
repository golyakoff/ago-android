package ago.chat.android.devices

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-18`: [decideAlert]'s own `appInForeground` input, as a port - never read by calling
 * `ProcessLifecycleOwner.get().lifecycle.currentState` directly from [IncomingPushRouter], which would
 * make that class untestable without a real `Lifecycle` in play, the identical "an external signal sits
 * behind a small interface" shape [PushRegistrationGateway] already establishes for the RuStore SDK.
 */
public interface AppForegroundTracker {
    /** `true` from `ON_START` to the matching `ON_STOP`, on [ProcessLifecycleOwner]'s own registry -
     * see [ProcessLifecycleForegroundTracker]'s own doc comment for why that signal and not an
     * `Activity`'s. */
    public val isAppInForeground: Boolean
}

/**
 * `OperatorHubConnectionLifecycle`'s own shape, restated for a second, unrelated foreground signal
 * rather than widening that class to serve two purposes. **`ProcessLifecycleOwner`, not `MainActivity`'s
 * own `onStart`/`onStop`** - that class's own doc comment states in full why an `Activity`'s lifecycle is
 * the wrong signal (a rotation fires both without the app ever leaving the foreground), and the reasoning
 * is unchanged for a push's own foreground check.
 *
 * A `@Volatile` field rather than a `StateFlow`: [AgoPushMessagingService] reads this from a background
 * coroutine that has no interest in observing a stream of *future* changes, only in a plain, synchronous
 * "is it true right now" - a `StateFlow.value` read would do the identical job at the cost of a type
 * [IncomingPushRouter]'s own unit tests would then have to construct just to read one `Boolean` out of.
 *
 * `@Singleton`, started once from `AgoChatApplication.onCreate` - registering more than once would add a
 * second observer flipping the identical field twice per transition, harmless but not the intended shape
 * (`OperatorHubConnectionLifecycle.start`'s own doc comment, restated).
 */
@Singleton
public class ProcessLifecycleForegroundTracker
    @Inject
    constructor() :
    AppForegroundTracker,
        DefaultLifecycleObserver {
        @Volatile
        private var foreground: Boolean = false

        override val isAppInForeground: Boolean
            get() = foreground

        /** Called once, from `AgoChatApplication.onCreate` - see [OperatorHubConnectionLifecycle.start]'s
         * own doc comment for why calling this a second time is harmless but not the intended shape. */
        public fun start() {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }

        override fun onStart(owner: LifecycleOwner) {
            foreground = true
        }

        override fun onStop(owner: LifecycleOwner) {
            foreground = false
        }
    }
