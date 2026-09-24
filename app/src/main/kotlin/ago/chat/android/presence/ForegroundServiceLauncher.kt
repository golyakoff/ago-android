package ago.chat.android.presence

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-85`: the one class that actually holds a `Context` for [OperatorPresenceService]'s own start/stop
 * — split out of [OperatorPresenceController] for the identical reason
 * [ago.chat.android.devices.WorkManagerDeviceRegistrationScheduler]'s own doc comment states for itself
 * ("the one binding that *does* carry a `Context`... a separate class... rather than one more method"):
 * everything that decides *whether* to run stays plain-JVM-testable behind [OperatorPresenceController],
 * and only the two calls that genuinely need Android sit behind this narrow seam.
 */
public interface ForegroundServiceLauncher {
    /** Idempotent from the caller's point of view: starting an already-running foreground service just
     * redelivers `onStartCommand` ([OperatorPresenceService]'s own doc comment) rather than a second
     * instance. */
    public fun start()

    /** A call with nothing running is a harmless no-op — `Context.stopService`'s own documented
     * contract. */
    public fun stop()
}

@Singleton
public class AndroidForegroundServiceLauncher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ForegroundServiceLauncher {
        override fun start() {
            ContextCompat.startForegroundService(context, Intent(context, OperatorPresenceService::class.java))
        }

        override fun stop() {
            context.stopService(Intent(context, OperatorPresenceService::class.java))
        }
    }
