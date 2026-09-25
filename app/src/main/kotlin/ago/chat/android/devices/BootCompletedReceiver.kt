package ago.chat.android.devices

import ago.chat.android.di.IoDispatcher
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * `26-129`: the whole point of the autostart inference — the receiver whose *running* is the evidence. The
 * manifest's `RECEIVE_BOOT_COMPLETED` permission + `ACTION_BOOT_COMPLETED` intent-filter mean the OS delivers
 * this broadcast after a reboot **only if it let the app start itself**; a phone that blocked autostart never
 * calls this at all. So there is no "autostart failed" callback to handle here — recording that this ran, and
 * *inferring* the failure later from the absence of a fresh record ([inferAutostartBootSignal]), is the only
 * shape Android allows.
 *
 * It records exactly one marker — the current boot's approximate wall-clock time — and touches nothing else.
 * The `seen` baseline is the app foreground's job, never the receiver's, so the two markers can never
 * overwrite each other ([BootAutostartMarkerStore]'s own doc comment).
 *
 * **A Hilt `@EntryPoint`, not `@AndroidEntryPoint` field injection.** `@AndroidEntryPoint` on a
 * `BroadcastReceiver` requires calling `super.onReceive`, which the Kotlin compiler rejects as an abstract
 * super call (`BroadcastReceiver.onReceive` has no body). Pulling the graph out through
 * [EntryPointAccessors.fromApplication] is the established alternative for a receiver — it needs no super call
 * and no generated base class — and this receiver holds no injected state between calls anyway. The write is
 * moved off the main thread with [goAsync]: `onReceive` runs on the main thread and must return quickly, and
 * `DataStore` is a suspend/IO API, so the `PendingResult` is finished only once the write completes, keeping
 * the process alive for that brief window.
 */
public class BootCompletedReceiver : BroadcastReceiver() {
    /** The one slice of the singleton graph this receiver needs — resolved per broadcast, since a
     * `BroadcastReceiver` instance is short-lived and holds nothing between deliveries. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface BootReceiverEntryPoint {
        fun markerStore(): BootAutostartMarkerStore

        fun bootTimeSource(): BootTimeSource

        @IoDispatcher
        fun ioDispatcher(): CoroutineDispatcher
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val entryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, BootReceiverEntryPoint::class.java)
        val bootTimeMillis = entryPoint.bootTimeSource().approximateBootTimeMillis()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + entryPoint.ioDispatcher()).launch {
            try {
                entryPoint.markerStore().recordAutostartBoot(bootTimeMillis)
            } finally {
                pending.finish()
            }
        }
    }
}
