package ago.chat.android.devices

import kotlin.math.abs

/**
 * `26-129`: the after-the-fact autostart signal `26-128` deliberately could not read. Android exposes no
 * API to read whether autostart-after-reboot is granted ([AutostartAdvisor]'s own doc comment), so `26-128`
 * shows only a manufacturer-based *guess*. This adds a real, if imperfect, observation on top of it — never
 * a live/proactive probe, only an inference drawn the next time the operator opens the app.
 */
public enum class AutostartBootSignal {
    /**
     * The phone rebooted since the app last ran, and the app's `BOOT_COMPLETED` receiver never recorded
     * that boot before this manual open — evidence the OS blocked the app from starting itself. The one
     * case that turns the «Автозапуск» row orange with the specific reason.
     */
    Blocked,

    /**
     * The phone rebooted since the app last ran, and the `BOOT_COMPLETED` receiver *did* record the current
     * boot — positive proof autostart worked this session. Overrides the manufacturer guess so a restrictive
     * OEM on which the operator has already enabled autostart is not falsely warned.
     */
    AutostartConfirmed,

    /**
     * Not enough evidence either way: no baseline recorded yet (first run), or no reboot has happened since
     * the app last ran. The «Автозапуск» row falls back to `26-128`'s manufacturer guess unchanged — this
     * signal never *removes* the OEM recommendation, it only refines it when there is a real observation.
     */
    NoSignal,
}

/**
 * `26-129`: two estimates of a boot's wall-clock start (`System.currentTimeMillis() -
 * SystemClock.elapsedRealtime()`) are treated as the *same* boot when they fall within
 * [DEFAULT_BOOT_TIME_TOLERANCE_MILLIS] of each other. The estimate is not perfectly stable within one boot
 * session — a wall-clock adjustment (NTP correction, a manual clock change) shifts it while
 * `elapsedRealtime` keeps counting — so a bare equality check would read routine NTP jitter as a fresh
 * reboot. Two minutes comfortably clears realistic NTP correction (sub-second in practice) while staying
 * well under the shortest plausible interval between two real reboots, so it neither merges distinct boots
 * nor splits one. A large *manual* clock change with no reboot can still fool it — the documented,
 * accepted imperfection of a signal `docs/backlog/26-129-*.md` itself calls "real, if imperfect".
 */
public const val DEFAULT_BOOT_TIME_TOLERANCE_MILLIS: Long = 2L * 60L * 1000L

/**
 * `26-129`: the whole decision, as one pure function over three explicit inputs so a plain JUnit test drives
 * every case with no Android, no `DataStore` and no clock in play — the identical "the behaviour worth
 * proving never needed a file" split [SeenKeysWindow] already draws for the push-dedupe store.
 *
 * @param currentBootTimeMillis this open's approximate last-boot wall-clock time.
 * @param lastSeenBootTimeMillis the boot the app recorded at its first foreground run, or `null` before any
 *   run has ever established a baseline. Without a baseline there is no way to tell "the app was just
 *   installed mid-session, no reboot expected" from "the app rebooted and was blocked", so a `null` here is
 *   always [AutostartBootSignal.NoSignal].
 * @param lastAutostartBootTimeMillis the boot the `BOOT_COMPLETED` receiver last recorded, or `null` if it
 *   has never fired (autostart never worked, or the app has never been through a reboot).
 * @param toleranceMillis the same-boot window — see [DEFAULT_BOOT_TIME_TOLERANCE_MILLIS].
 */
public fun inferAutostartBootSignal(
    currentBootTimeMillis: Long,
    lastSeenBootTimeMillis: Long?,
    lastAutostartBootTimeMillis: Long?,
    toleranceMillis: Long = DEFAULT_BOOT_TIME_TOLERANCE_MILLIS,
): AutostartBootSignal {
    // No baseline yet: the very first run, before the app has recorded any boot it was alive during. There
    // is nothing to compare against, so no reboot can be inferred.
    if (lastSeenBootTimeMillis == null) return AutostartBootSignal.NoSignal

    // Still the same boot the app last ran in: the phone has not rebooted since, so autostart was never
    // even asked to fire. Nothing to warn about.
    if (abs(currentBootTimeMillis - lastSeenBootTimeMillis) <= toleranceMillis) return AutostartBootSignal.NoSignal

    // A reboot happened since the app last ran. Did the receiver record *this* boot?
    val receiverRanThisBoot =
        lastAutostartBootTimeMillis != null &&
            abs(currentBootTimeMillis - lastAutostartBootTimeMillis) <= toleranceMillis

    return if (receiverRanThisBoot) AutostartBootSignal.AutostartConfirmed else AutostartBootSignal.Blocked
}
