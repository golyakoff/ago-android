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
 * `26-06`/`26-18`/`adr/0180`: the service the manifest's `ru.rustore.sdk.pushclient.MESSAGING_EVENT`
 * intent-filter starts - the second of the three registration call sites `26-06` named, and now also
 * the one and only receive path `26-18` adds: `onMessageReceived`/`onDeletedMessages` below.
 *
 * **`@AndroidEntryPoint` on a plain `Service`, no extra dependency.** Hilt supports `Service` as a
 * first-class entry point with nothing beyond the `hilt-android`/Hilt Gradle plugin this app already
 * has (unlike [DeviceRegistrationWorker]'s own `EntryPointAccessors` route, chosen there specifically
 * *because* `WorkManager`'s own construction path bypasses Hilt's component tree - a `Service` the OS
 * starts through the manifest does not have that problem).
 *
 * **Every method here runs on a background thread already** (RuStore's own documentation, confirmed
 * against `RuStoreMessagingService.class`'s own `serviceScope`/dispatcher fields) - so every override
 * below launches onto this class's own scope rather than blocking the caller, and never onto
 * `Dispatchers.Main`.
 *
 * **This class holds no decision of its own for the receive path.** Every override here does nothing
 * but translate an SDK type into a plain value and hand it to [IncomingPushRouter] - the identical
 * "the `Service`/consumer deserializes, scopes, calls, acks; a plain class holds the decision" split
 * `NotifyOperatorDevicesHandler`'s own doc comment states for its own two consumers, restated here for
 * the client's own receive path. That split is what makes [IncomingPushRouter] - the actual dedupe,
 * suppress, and present sequence - testable on a plain JVM with fakes and no `Service`, no
 * `RemoteMessage`, and no real `NotificationManager` anywhere in the test.
 */
@AndroidEntryPoint
public class AgoPushMessagingService : RuStoreMessagingService() {
    @Inject
    internal lateinit var coordinator: DeviceRegistrationCoordinator

    @Inject
    internal lateinit var router: IncomingPushRouter

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

    /**
     * `26-18`: a data-only message ("in any case", RuStore's own documentation - this fires whether or
     * not the app ends up rendering anything). [message.data][RemoteMessage.getData] is nullable on the
     * SDK's own type despite its documentation describing the field as always present, so `.orEmpty()`-
     * style handling lives inside [IncomingPushRouter.handleMessage] itself rather than here, alongside
     * the rest of that class's own parsing.
     *
     * Launched onto this class's own scope, never awaited - `onMessageReceived` itself returns
     * immediately either way, and the real deadline is RuStore's own **20-second** handling window
     * (this class's own doc comment), which the launched coroutine - not this method - has to finish
     * inside. Building and showing a notification is well within that; there is no network call
     * anywhere on this path (`IncomingPush`'s own doc comment: the payload already carries what the
     * notification needs).
     */
    override fun onMessageReceived(message: RemoteMessage) {
        scope.launch {
            runCatching { router.handleMessage(message.messageId, message.data) }
        }
    }

    /**
     * `26-18`: RuStore's own recovery hook for pushes that were **not** delivered (TTL expiry being its
     * documented example) - wired to [IncomingPushRouter.handleDeletedMessages], which asks
     * [ConversationListViewModel][ago.chat.android.conversations.ConversationListViewModel] to refresh
     * rather than leaving the operator with a silent gap. Synchronous, not launched: the call itself is
     * a non-suspending `tryEmit` all the way down ([DefaultConversationRefreshSignal]'s own doc comment),
     * so there is nothing here that needs this class's own background scope.
     */
    override fun onDeletedMessages() {
        router.handleDeletedMessages()
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
