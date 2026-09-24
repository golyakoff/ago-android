package ago.chat.android.devices

import ago.chat.android.di.IoDispatcher
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.rustore.sdk.pushclient.messaging.exception.RuStorePushClientException
import ru.rustore.sdk.pushclient.messaging.model.RemoteMessage
import ru.rustore.sdk.pushclient.messaging.service.RuStoreMessagingService
import javax.inject.Inject

/**
 * `26-06`/`adr/0180`: the service the manifest's `ru.rustore.sdk.pushclient.MESSAGING_EVENT`
 * intent-filter starts - the second of the three call sites, and the only one this SDK itself drives:
 * `onNewToken`'s own documentation says in so many words that after it fires the app is responsible
 * for delivering the new token to its own server. `onMessageReceived`/`onDeletedMessages` are
 * deliberately no-ops - **rendering or suppressing anything is `26-18`'s job, not this item's**
 * (`docs/backlog/26-06-*.md`'s own Out of scope), and this class exists so that item has a real
 * service to extend rather than one it has to create from nothing.
 *
 * **`@AndroidEntryPoint` on a plain `Service`, no extra dependency.** Hilt supports `Service` as a
 * first-class entry point with nothing beyond the `hilt-android`/Hilt Gradle plugin this app already
 * has (unlike [DeviceRegistrationWorker]'s own `EntryPointAccessors` route, chosen there specifically
 * *because* `WorkManager`'s own construction path bypasses Hilt's component tree - a `Service` the OS
 * starts through the manifest does not have that problem).
 *
 * **Every method here runs on a background thread already** (RuStore's own documentation, confirmed
 * against `RuStoreMessagingService.class`'s own `serviceScope`/dispatcher fields) - so [onNewToken]
 * launches onto this class's own scope rather than blocking the caller, and never onto `Dispatchers.Main`.
 */
@AndroidEntryPoint
public class AgoPushMessagingService : RuStoreMessagingService() {
    @Inject
    internal lateinit var coordinator: DeviceRegistrationCoordinator

    @Inject
    @IoDispatcher
    internal lateinit var ioDispatcher: CoroutineDispatcher

    private val scope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }

    /**
     * The new token, handed straight to [DeviceRegistrationCoordinator.onNewToken] - never through a
     * `Log` call. The token is a routing address, not a secret, per `docs/backlog/26-06-*.md`'s own
     * Scope, but "not a secret" and "safe to print" are different claims, and this class makes neither
     * for it.
     */
    override fun onNewToken(token: String) {
        scope.launch {
            runCatching { coordinator.onNewToken(token) }
        }
    }

    /** `26-18`'s job. Intentionally empty - see this class's own doc comment. */
    override fun onMessageReceived(message: RemoteMessage) {
    }

    /** `26-18`'s job. Intentionally empty. */
    override fun onDeletedMessages() {
    }

    /**
     * Reported, never hidden - `docs/backlog/26-06-*.md`'s own Scope names these three by name. The
     * exception's own class name is logged, never its `message` (`NetworkFailure`'s own doc comment
     * states why an exception's own text is not trusted onto a log line in this app), and there is no
     * token anywhere in a [RuStorePushClientException] for this line to risk printing.
     */
    override fun onError(errors: List<RuStorePushClientException>) {
        errors.forEach { error -> Log.w(TAG, "RuStore push onError: ${error::class.simpleName}") }
    }

    private companion object {
        const val TAG = "DeviceRegistration"
    }
}
