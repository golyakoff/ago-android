package ago.chat.android.presence

import ago.chat.android.core.network.realtime.OperatorHubConnection
import ago.chat.android.di.IoDispatcher
import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * `26-85`: the foreground service that owns [OperatorHubConnection]'s own `connect()`/`disconnect()` for
 * as long as [OperatorPresenceController] has decided the signed-in identity is worth keeping online in
 * the background — `docs/backlog/26-85-*.md`'s own chosen fix for `26-84`: a call, a music player, or a
 * navigation app's persistent notification, restated for an operator's own hub connection. Started and
 * stopped only through [ForegroundServiceLauncher]; nothing else in this app builds an `Intent` naming
 * this class.
 *
 * **`@AndroidEntryPoint` on a plain `Service`, the identical [ago.chat.android.devices.AgoPushMessagingService]
 * precedent** — that class's own doc comment states why this needs nothing beyond the Hilt Gradle plugin
 * this app already applies, unlike [ago.chat.android.devices.DeviceRegistrationWorker]'s own
 * `EntryPointAccessors` route (chosen there specifically because `WorkManager`'s own construction path
 * bypasses Hilt's component tree — a `Service` the OS starts through `Context.startForegroundService`
 * does not have that problem).
 *
 * **`startForeground` inside [onStartCommand], not `onCreate`.** `onCreate` runs once, before the first
 * `onStartCommand` this instance will ever see; the platform's own "call `startForeground` within a few
 * seconds of `startForegroundService`" deadline is measured from the `startForegroundService` call
 * [ForegroundServiceLauncher.start] makes, which is what actually delivers `onStartCommand` — calling it
 * there is what keeps that deadline met on every restart, not only the first.
 *
 * **No `android:foregroundServiceType` argument on the two-argument `startForeground` overload below.**
 * This service's manifest entry declares exactly one type (`connectedDevice`) — the platform's own
 * documented behaviour is that the manifest-declared type applies automatically when
 * `startForeground(id, notification)` names none itself, and the three-argument overload only exists to
 * *narrow* a manifest declaring more than one type, which this entry never does.
 *
 * **`connection.connect()`/`connection.disconnect()`, never awaited on a lifecycle callback.** Both are
 * the identical idempotent calls [ago.chat.android.realtime.OperatorHubConnectionLifecycle] and
 * [ago.chat.android.signin.SignInViewModel] already make — two, three, or four callers racing any of
 * them is exactly what [OperatorHubConnection]'s own doc comment already guarantees is safe
 * (`ensureConnection`'s "built at most once" guard, `connect`'s own "idempotent" contract) — so launching
 * each on this class's own scope rather than blocking [onStartCommand]/[onDestroy] on it costs nothing
 * and risks nothing.
 *
 * **`START_STICKY`, not `START_NOT_STICKY`.** If the system kills this process under memory pressure
 * while [OperatorPresenceController] still expects it running, `START_STICKY` asks the platform to
 * recreate it with a `null` intent — [onStartCommand] treats that identically to a real start (calling
 * `connect()` again is a no-op if already connected, per that method's own idempotence), which is the
 * correct recovery for a service whose whole purpose is "stay running", unlike
 * [ago.chat.android.devices.DeviceRegistrationWorker]'s one-shot `Result` shape.
 */
@AndroidEntryPoint
public class OperatorPresenceService : Service() {
    @Inject
    internal lateinit var connection: OperatorHubConnection

    @Inject
    @IoDispatcher
    internal lateinit var ioDispatcher: CoroutineDispatcher

    private val scope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(OPERATOR_PRESENCE_NOTIFICATION_ID, buildPresenceNotification(this))
        scope.launch { runCatching { connection.connect() } }
        return START_STICKY
    }

    /**
     * The one deliberate-stop path: [ForegroundServiceLauncher.stop] (from
     * [OperatorPresenceController.onSignedOut] or a permission fetch that no longer grants
     * `conversation:send`) calls `Context.stopService`, which the platform answers by tearing this
     * instance down through here — never through [onStartCommand] again. Disconnecting is launched on
     * this class's own scope rather than awaited, the identical "never block a lifecycle callback on
     * suspending work" shape every other `Service`/`Application` callback in this app already follows
     * ([ago.chat.android.devices.AgoPushMessagingService.onNewToken]), and wrapped in `runCatching` for
     * the identical "a lifecycle teardown must never throw" reason
     * [ago.chat.android.signin.SignInViewModel.routeNow] already states for its own fire-and-forget
     * `connect()` call.
     */
    override fun onDestroy() {
        scope.launch { runCatching { connection.disconnect() } }
        super.onDestroy()
    }

    /** Never bound — every caller reaches this service through [ForegroundServiceLauncher]'s own
     * `startService`/`stopService` pair, never `bindService`. */
    override fun onBind(intent: Intent?): IBinder? = null
}
