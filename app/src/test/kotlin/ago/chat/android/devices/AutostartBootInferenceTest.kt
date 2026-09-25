package ago.chat.android.devices

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-129`: [inferAutostartBootSignal] proven as a plain function over three explicit marker values - the
 * three cases `docs/backlog/26-129-*.md` names by name (rebooted+blocked → warn; rebooted+autostarted → no
 * warn; no reboot → no warn), plus the baseline and tolerance edges that decide which of those a real device
 * lands in. No Android, no `DataStore`, no clock: the whole decision is arithmetic over three longs.
 */
class AutostartBootInferenceTest {
    private companion object {
        // A plausible boot wall-clock estimate and a later one, a full day apart - unambiguously two
        // different boots at any sane tolerance.
        const val BOOT_A = 1_700_000_000_000L
        const val BOOT_B = BOOT_A + 24L * 60L * 60L * 1000L
    }

    @Test
    fun `rebooted and the receiver never ran is blocked - warn`() {
        // Current boot is B; the app last recorded boot A (seen) and the receiver's last record is also A
        // (it fired on some earlier boot, never on B) - so B was reached without autostart.
        assertEquals(
            AutostartBootSignal.Blocked,
            inferAutostartBootSignal(
                currentBootTimeMillis = BOOT_B,
                lastSeenBootTimeMillis = BOOT_A,
                lastAutostartBootTimeMillis = BOOT_A,
            ),
        )
    }

    @Test
    fun `rebooted and the receiver never fired at all is blocked - warn`() {
        // The stronger blocked shape: the receiver has never once run (null), yet the app has been alive
        // across a reboot (seen A, current B). Autostart has simply never worked.
        assertEquals(
            AutostartBootSignal.Blocked,
            inferAutostartBootSignal(
                currentBootTimeMillis = BOOT_B,
                lastSeenBootTimeMillis = BOOT_A,
                lastAutostartBootTimeMillis = null,
            ),
        )
    }

    @Test
    fun `rebooted and the receiver recorded this boot is confirmed - no warn`() {
        // Current boot is B, the receiver recorded B (it ran on this boot) - autostart demonstrably worked.
        assertEquals(
            AutostartBootSignal.AutostartConfirmed,
            inferAutostartBootSignal(
                currentBootTimeMillis = BOOT_B,
                lastSeenBootTimeMillis = BOOT_A,
                lastAutostartBootTimeMillis = BOOT_B,
            ),
        )
    }

    @Test
    fun `no reboot since the app last ran is no signal - no warn`() {
        // Current boot equals the seen boot: the phone has not rebooted, so autostart was never asked to fire.
        assertEquals(
            AutostartBootSignal.NoSignal,
            inferAutostartBootSignal(
                currentBootTimeMillis = BOOT_A,
                lastSeenBootTimeMillis = BOOT_A,
                lastAutostartBootTimeMillis = null,
            ),
        )
    }

    @Test
    fun `no baseline yet is no signal - first run, no false warning`() {
        // First run ever: no seen marker. Nothing to compare against, so no reboot can be inferred - the
        // case that would otherwise falsely warn every freshly-installed app on its very first launch.
        assertEquals(
            AutostartBootSignal.NoSignal,
            inferAutostartBootSignal(
                currentBootTimeMillis = BOOT_A,
                lastSeenBootTimeMillis = null,
                lastAutostartBootTimeMillis = null,
            ),
        )
    }

    @Test
    fun `wall-clock drift within the tolerance still counts as the same boot`() {
        // The boot estimate drifted by under the tolerance (an NTP correction, not a reboot): seen and
        // current are within the window, so this stays NoSignal rather than reading drift as a fresh reboot.
        val drift = DEFAULT_BOOT_TIME_TOLERANCE_MILLIS - 1
        assertEquals(
            AutostartBootSignal.NoSignal,
            inferAutostartBootSignal(
                currentBootTimeMillis = BOOT_A + drift,
                lastSeenBootTimeMillis = BOOT_A,
                lastAutostartBootTimeMillis = null,
            ),
        )
    }

    @Test
    fun `a confirmed match is still confirmed when the receiver record drifts within tolerance`() {
        // The receiver recorded the current boot, but the two estimates differ by under the tolerance -
        // still the same boot, so still confirmed.
        val drift = DEFAULT_BOOT_TIME_TOLERANCE_MILLIS - 1
        assertEquals(
            AutostartBootSignal.AutostartConfirmed,
            inferAutostartBootSignal(
                currentBootTimeMillis = BOOT_B,
                lastSeenBootTimeMillis = BOOT_A,
                lastAutostartBootTimeMillis = BOOT_B - drift,
            ),
        )
    }

    @Test
    fun `a stale receiver record beyond tolerance does not count as this boot`() {
        // The receiver's last record is older than the current boot by more than the tolerance - it ran on a
        // previous boot, not this one, so this boot was blocked.
        val gap = DEFAULT_BOOT_TIME_TOLERANCE_MILLIS + 1
        assertEquals(
            AutostartBootSignal.Blocked,
            inferAutostartBootSignal(
                currentBootTimeMillis = BOOT_B,
                lastSeenBootTimeMillis = BOOT_A,
                lastAutostartBootTimeMillis = BOOT_B - gap,
            ),
        )
    }
}
