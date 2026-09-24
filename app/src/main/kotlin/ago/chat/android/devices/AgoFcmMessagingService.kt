package ago.chat.android.devices

import ago.chat.android.core.domain.devices.PushProvider
import ago.chat.android.di.IoDispatcher
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * `26-100`/`adr/0181`: the manifest's `com.google.firebase.MESSAGING_EVENT` intent-filter starts this -
 * FCM's own twin of [AgoPushMessagingService], feeding the identical [IncomingPushRouter] path. That
 * class needs no change for this to work: `adr/0181` §3 requires both transports to deliver the
 * identical data-only `data` map, so [IncomingPushRouter.handleMessage] is already provider-blind
 * (`docs/backlog/26-100-*.md`'s own cross-repo contract).
 *
 * **`@AndroidEntryPoint` on a plain `Service`, no extra dependency** - the identical shape
 * [AgoPushMessagingService]'s own doc comment states for RuStore's service.
 *
 * **Every override here does nothing but translate an SDK type into a plain value and hand it to
 * [IncomingPushRouter]/[DeviceRegistrationCoordinator]** - the identical "this class holds no decision of
 * its own" split [AgoPushMessagingService]'s own doc comment states, restated here rather than
 * re-explained, since both services exist for exactly the same reason under two different SDKs.
 *
 * **No `onError` override.** `FirebaseMessagingService` has no callback equivalent to
 * `RuStoreMessagingService.onError` - FCM's own token/delivery failures surface only through
 * `getToken()`'s own `Task.addOnFailureListener` ([FcmPushGateway.currentToken]), which is the one path
 * [DeviceRegistrationCoordinator.registerThisDevice] already covers; there is no second, out-of-band
 * failure feed to wire up the way [AgoPushMessagingService.onError] is for RuStore.
 */
@AndroidEntryPoint
public class AgoFcmMessagingService : FirebaseMessagingService() {
    @Inject
    internal lateinit var coordinator: DeviceRegistrationCoordinator

    @Inject
    internal lateinit var router: IncomingPushRouter

    @Inject
    @IoDispatcher
    internal lateinit var ioDispatcher: CoroutineDispatcher

    private val scope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }

    /**
     * The new token, handed straight to [DeviceRegistrationCoordinator.onNewToken] with this service's
     * own fixed [PushProvider.Fcm] - never through a `Log` call, for the identical "a routing address is
     * not a secret, but is not printed either" reason [AgoPushMessagingService.onNewToken]'s own doc
     * comment states.
     *
     * `26-100`: `firebase-messaging` 25.1.3 deprecates this override in favour of a new
     * `onRegistered(String)`/`onUnregistered(String)` pair - [FcmPushGateway]'s own doc comment states
     * why this class stays on the deprecated-but-still-shipped `onNewToken` rather than that new,
     * opt-in, differently-shaped registration model. `@Suppress("DEPRECATION")` on this override alone,
     * not the file.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        scope.launch {
            runCatching { coordinator.onNewToken(PushProvider.Fcm, token) }
        }
    }

    /**
     * A data-only message, identically shaped to RuStore's own (`adr/0181` §3) - [RemoteMessage.getData]
     * is non-null on FCM's own type (an empty map when there is nothing), handed straight to
     * [IncomingPushRouter.handleMessage] alongside [RemoteMessage.getMessageId] the same way
     * [AgoPushMessagingService.onMessageReceived] hands over RuStore's own nullable equivalent - that
     * function's own `.orEmpty()` handling already covers both.
     *
     * Launched onto this service's own scope, never awaited - `onMessageReceived` itself returns
     * immediately either way, matching [AgoPushMessagingService.onMessageReceived]'s own reasoning for
     * why that is safe here too (no network call anywhere on this path, well within FCM's own handling
     * window).
     */
    override fun onMessageReceived(message: RemoteMessage) {
        scope.launch {
            runCatching { router.handleMessage(message.messageId, message.data) }
        }
    }

    /**
     * FCM's own recovery hook for messages it could not deliver (too many pending, TTL expiry) - the
     * identical shape RuStore's `onDeletedMessages()` is, wired to the identical
     * [IncomingPushRouter.handleDeletedMessages] recovery [AgoPushMessagingService.onDeletedMessages]
     * already uses. Synchronous, not launched - see that override's own doc comment for why.
     */
    override fun onDeletedMessages() {
        router.handleDeletedMessages()
    }
}
