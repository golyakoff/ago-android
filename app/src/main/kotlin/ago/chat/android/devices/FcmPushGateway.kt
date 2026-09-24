package ago.chat.android.devices

import ago.chat.android.core.domain.devices.PushProvider
import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * `26-100`/`adr/0181`: [PushRegistrationGateway] over the real `FirebaseMessaging` singleton - FCM's own
 * twin of [RuStorePushGateway], selected instead of it by `di/AppModule.kt`'s
 * `providePushRegistrationGateway` on a device [TransportSelector] finds has usable Google Play Services.
 * `RuStorePushGateway` is untouched by this class existing - both implement the identical
 * [PushRegistrationGateway] seam, and only one is ever bound into the graph for a given process.
 *
 * **`suspendCancellableCoroutine`, not `Task.await()`.** `com.google.android.gms.tasks.Task` is the exact
 * same blocking-`await()` shape [RuStorePushGateway]'s own doc comment already found and rejected for
 * `ru.rustore.sdk.core.tasks.Task` - a different package, the identical "parks the calling thread"
 * design, so the identical fix applies: `addOnSuccessListener`/`addOnFailureListener`/
 * `addOnCompleteListener` bridged through a suspend function, never `.await()`.
 *
 * **`getToken()`/`deleteToken()`, not the new `register()`/`unregister()` - both deliberately, even
 * though `firebase-messaging` 25.1.3 deprecates the pair this class calls.** The replacement is not a
 * drop-in: it is a second, **opt-in** registration model (a `firebase_messaging_installation_id_enabled`
 * manifest meta-data flag `docs/backlog/26-100-*.md` never asked for) that does not return the token at
 * all - it arrives later, asynchronously, through a new `FirebaseMessagingService.onRegistered(String)`
 * callback, which [PushRegistrationGateway.currentToken] (a pull-based `suspend fun` every existing
 * caller - `DeviceRegistrationCoordinator`, `DeviceRegistrationWorker` - already assumes) has no seam
 * for. Adopting it is a real, separate redesign of this port's own shape, not a same-scope substitution,
 * so this class keeps calling the deprecated-but-still-shipped, still-documented `getToken()`/
 * `deleteToken()` (`@Suppress("DEPRECATION")` on each, not a blanket file-level suppression) and the
 * matching deprecated `onNewToken` override in [AgoFcmMessagingService] - noted here for whoever next
 * touches this file, not routed around silently.
 */
@Singleton
public class FcmPushGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PushRegistrationGateway {
        override val provider: PushProvider = PushProvider.Fcm

        @Suppress("DEPRECATION")
        override suspend fun currentToken(): PushTokenResult =
            suspendCancellableCoroutine { continuation ->
                FirebaseMessaging
                    .getInstance()
                    .token
                    .addOnSuccessListener { token ->
                        if (continuation.isActive) continuation.resume(PushTokenResult.Token(token))
                    }.addOnFailureListener { cause ->
                        if (continuation.isActive) continuation.resume(cause.toFcmTokenFailure())
                    }
            }

        @Suppress("DEPRECATION")
        override suspend fun deleteToken() {
            suspendCancellableCoroutine { continuation ->
                FirebaseMessaging
                    .getInstance()
                    .deleteToken()
                    .addOnCompleteListener {
                        // Best-effort, per DeviceRevocation's own doc comment: sign-out proceeds
                        // regardless of whether FCM's own backend could be reached just now.
                        if (continuation.isActive) continuation.resume(Unit)
                    }
            }
        }

        /**
         * `26-100`: unlike RuStore's own `checkPushAvailability()`, `FirebaseMessaging` exposes no
         * availability check of its own - Play Services being usable *is* the availability question for
         * this transport, so this reads the identical `GoogleApiAvailability` signal [TransportSelector]
         * already asks at registration time, folded through the same pure-function shape
         * ([playServicesAvailabilityFor]) so a JVM test drives both branches with no `Context`.
         */
        override suspend fun checkAvailability(): PushAvailability =
            playServicesAvailabilityFor(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context))
    }

/**
 * `26-100`: [ConnectionResult.SUCCESS] maps to [PushAvailability.Available]; every other result folds
 * onto [PushUnavailableReason.Unknown] rather than a dedicated `PlayServicesUnavailable` member. A new
 * member would ripple into `NotificationSettingsScreen`/`shell/SettingsScreen`'s own exhaustive `when`
 * blocks and a new string resource - both outside this change's scope (push/devices,
 * `AgoChatApplication`, and the build files only). `Unknown`'s existing copy ("could not verify whether
 * push can arrive on this phone") already reads honestly for this case; a dedicated reason is a small,
 * separate follow-up if the two causes ever need to be told apart in the UI. `internal`, not `private`:
 * [FcmPushGatewayTest] calls this directly with a bare `Int`.
 */
internal fun playServicesAvailabilityFor(playServicesAvailabilityResult: Int): PushAvailability =
    if (playServicesAvailabilityResult == ConnectionResult.SUCCESS) {
        PushAvailability.Available
    } else {
        PushAvailability.Unavailable(PushUnavailableReason.Unknown)
    }

/**
 * `26-100`: FCM has no structured exception hierarchy the way `RuStorePushClientException` is - every
 * failure `FirebaseMessaging.getToken()`'s own `Task` reports is a bare `Exception`, so the only
 * distinction worth drawing is the identical one [RuStorePushGateway.toTokenFailure] already draws for
 * RuStore's own catch-all branch: an [IOException] is the ordinary shape of a transient network blip
 * while minting a token (never critical - [DeviceRegistrationCoordinator] retries quietly), everything
 * else is treated as a genuine, actionable failure. `internal`, not `private`: [FcmPushGatewayTest] calls
 * this directly on a real exception instance.
 */
internal fun Throwable.toFcmTokenFailure(): PushTokenResult.Unavailable =
    if (this is IOException) {
        PushTokenResult.Unavailable(PushUnavailableReason.Unknown, critical = false)
    } else {
        PushTokenResult.Unavailable(PushUnavailableReason.Unknown, critical = true)
    }
