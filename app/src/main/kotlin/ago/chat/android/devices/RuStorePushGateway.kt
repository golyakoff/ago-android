package ago.chat.android.devices

import kotlinx.coroutines.suspendCancellableCoroutine
import ru.rustore.sdk.core.exception.RuStoreException
import ru.rustore.sdk.core.feature.model.FeatureAvailabilityResult
import ru.rustore.sdk.pushclient.RuStorePushClient
import ru.rustore.sdk.pushclient.messaging.exception.RuStorePushClientException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * `26-06`/`adr/0180`: [PushRegistrationGateway] over the real `RuStorePushClient` singleton.
 *
 * **Why `suspendCancellableCoroutine`, not `Task.await()`.** `ru.rustore.sdk.core.tasks.Task.await()`
 * exists and looks like the obvious bridge, but it is a **blocking** call (it parks the calling thread
 * on a `CountDownLatch`, confirmed by decompiling `pushclient-7.5.0.aar`'s own `Task.class` - there is
 * no suspend overload). Calling it from a coroutine would block whatever thread that coroutine happens
 * to be running on, exactly the "no sync-over-async" rule this project's own `ago-root/CLAUDE.md`
 * states for the backend, read onto the identical shape here. `addOnSuccessListener`/
 * `addOnFailureListener` - which RuStore's own documentation confirms run "in a background thread" for
 * every method used below - is the callback pair `AgoAuthSession`'s own `suspendCancellableCoroutine`
 * wrapping of AppAuth already establishes as this codebase's one idiom for adapting a callback-shaped
 * third-party API into a suspend function.
 */
@Singleton
public class RuStorePushGateway
    @Inject
    constructor() : PushRegistrationGateway {
        override suspend fun currentToken(): PushTokenResult =
            suspendCancellableCoroutine { continuation ->
                RuStorePushClient
                    .getToken()
                    .addOnSuccessListener { token ->
                        if (continuation.isActive) continuation.resume(PushTokenResult.Token(token))
                    }.addOnFailureListener {
                        // No token this attempt could not resolve - never a reason to crash a sign-in
                        // or a periodic job, matching this port's own doc comment.
                        if (continuation.isActive) continuation.resume(PushTokenResult.Unavailable)
                    }
            }

        override suspend fun deleteToken() {
            suspendCancellableCoroutine { continuation ->
                RuStorePushClient
                    .deleteToken()
                    .addOnSuccessListener {
                        if (continuation.isActive) continuation.resume(Unit)
                    }.addOnFailureListener {
                        // Best-effort, per DeviceRevocation's own doc comment: sign-out proceeds
                        // regardless of whether the SDK could reach its own backend just now.
                        if (continuation.isActive) continuation.resume(Unit)
                    }
            }
        }

        override suspend fun checkAvailability(): PushAvailability =
            suspendCancellableCoroutine { continuation ->
                RuStorePushClient
                    .checkPushAvailability()
                    .addOnSuccessListener { result ->
                        if (!continuation.isActive) return@addOnSuccessListener
                        continuation.resume(
                            when (result) {
                                FeatureAvailabilityResult.Available -> PushAvailability.Available
                                is FeatureAvailabilityResult.Unavailable ->
                                    PushAvailability.Unavailable(result.cause.toReason())
                            },
                        )
                    }.addOnFailureListener {
                        if (continuation.isActive) {
                            continuation.resume(PushAvailability.Unavailable(PushUnavailableReason.Unknown))
                        }
                    }
            }

        private fun RuStoreException.toReason(): PushUnavailableReason =
            when (this) {
                is RuStorePushClientException.HostAppNotInstalledException -> PushUnavailableReason.HostAppNotInstalled
                is RuStorePushClientException.HostAppBackgroundWorkPermissionNotGranted ->
                    PushUnavailableReason.HostAppBackgroundWorkNotGranted
                is RuStorePushClientException.UnauthorizedException -> PushUnavailableReason.Unauthorized
                else -> PushUnavailableReason.Unknown
            }
    }
