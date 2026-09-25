package ago.chat.android.devices

import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-129`: reads the current after-the-fact autostart signal for the «Автозапуск» row — behind a port so
 * [ago.chat.android.shell.SettingsViewModel] stays testable on a plain JVM (rule 2), the same shape
 * [AutostartAdvisor] already is for the manufacturer guess this refines.
 *
 * **Never a live/proactive read.** It only compares markers already on disk against the current boot's
 * estimate ([inferAutostartBootSignal]); it never probes an autostart permission (there is no such API) and
 * never *causes* anything to start — `docs/backlog/26-129-*.md`'s own "inference after the fact" constraint.
 */
public interface AutostartInferenceReader {
    public suspend fun currentSignal(): AutostartBootSignal
}

@Singleton
public class DefaultAutostartInferenceReader
    @Inject
    constructor(
        private val markers: BootAutostartMarkerStore,
        private val bootTime: BootTimeSource,
    ) : AutostartInferenceReader {
        override suspend fun currentSignal(): AutostartBootSignal {
            val current = bootTime.approximateBootTimeMillis()
            val snapshot = markers.read()

            // First run ever: establish the baseline so a *future* blocked reboot is distinguishable from
            // this install (which happened mid-session, with no reboot to autostart from). The signal for
            // this run is still NoSignal — a baseline is a starting point, never evidence in itself.
            //
            // The baseline is written once and then left alone: after a reboot it is deliberately *not*
            // advanced, so a Blocked/AutostartConfirmed determination stays stable across repeated opens
            // within the same boot session rather than reverting to the manufacturer guess on the second
            // open. An ancient baseline stays a valid "the app predates this boot" reference — every real
            // boot after it differs by far more than the tolerance — so the outcome is then decided purely
            // by whether the receiver recorded the current boot, which is exactly the question that matters.
            if (snapshot.lastSeenBootTimeMillis == null) {
                markers.recordSeenBoot(current)
            }

            return inferAutostartBootSignal(
                currentBootTimeMillis = current,
                lastSeenBootTimeMillis = snapshot.lastSeenBootTimeMillis,
                lastAutostartBootTimeMillis = snapshot.lastAutostartBootTimeMillis,
            )
        }
    }
