package ago.chat.android.devices

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-129`: the one platform read the boot-autostart inference needs, behind a port for the identical reason
 * every other framework call in this app sits behind one (rule 2, read onto an Android client): both
 * [inferAutostartBootSignal]'s caller and the `BOOT_COMPLETED` receiver need the same "approximate wall-clock
 * time of the last boot", and a plain JVM test can substitute a fixed value for it where `System`/`SystemClock`
 * cannot be set.
 *
 * `System.currentTimeMillis() - SystemClock.elapsedRealtime()` is the standard estimate: `elapsedRealtime`
 * counts monotonically from boot (deep sleep included), so subtracting it from the wall clock yields the wall
 * clock *at* boot — the same on every read within one boot session except for wall-clock adjustments, which
 * [DEFAULT_BOOT_TIME_TOLERANCE_MILLIS] absorbs.
 */
public interface BootTimeSource {
    public fun approximateBootTimeMillis(): Long
}

@Singleton
public class AndroidBootTimeSource
    @Inject
    constructor() : BootTimeSource {
        override fun approximateBootTimeMillis(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    }
