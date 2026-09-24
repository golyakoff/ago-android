package ago.chat.android.devices

import kotlinx.coroutines.suspendCancellableCoroutine
import ru.rustore.sdk.core.exception.RuStoreException
import ru.rustore.sdk.core.feature.model.FeatureAvailabilityResult
import ru.rustore.sdk.pushclient.RuStorePushClient
import ru.rustore.sdk.pushclient.messaging.exception.RuStorePushClientException
import java.io.IOException
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
                    }.addOnFailureListener { cause ->
                        // `26-101`: never a reason to crash a sign-in or a periodic job (this port's own
                        // doc comment, unchanged) - but the cause used to be discarded outright, which is
                        // exactly what let "no host installed" and "a two-second network blip" resolve to
                        // the identical `Unavailable` and sail through `DeviceRegistrationCoordinator` as
                        // an indistinguishable, silent non-failure. `toTokenFailure()` below is what tells
                        // them apart.
                        if (continuation.isActive) continuation.resume(cause.toTokenFailure())
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
            (this as? RuStorePushClientException)?.toPushUnavailableReason() ?: PushUnavailableReason.Unknown
    }

/**
 * `RuStorePushClientException`'s three named subtypes → this port's own [PushUnavailableReason] -
 * `internal` rather than `private` because [AgoPushMessagingService.onError] reports the identical
 * exception type from a second SDK callback (`RuStoreMessagingService.onError`) entirely outside
 * [RuStorePushGateway.currentToken]'s own `Task`, and needs the identical mapping rather than a second,
 * possibly-drifting copy of it.
 */
internal fun RuStorePushClientException.toPushUnavailableReason(): PushUnavailableReason =
    when (this) {
        is RuStorePushClientException.HostAppNotInstalledException -> PushUnavailableReason.HostAppNotInstalled
        is RuStorePushClientException.HostAppBackgroundWorkPermissionNotGranted ->
            PushUnavailableReason.HostAppBackgroundWorkNotGranted
        is RuStorePushClientException.UnauthorizedException -> PushUnavailableReason.Unauthorized
        // `RuStorePushClientException` is itself `sealed` - the compiler already knows these three
        // named subtypes are every subtype that can ever exist, so there is no fourth case to fall
        // through to here. Kept only because `PushUnavailableReason.Unknown` still needs a real reason
        // to exist for [toTokenFailure]'s own non-`RuStorePushClientException` branches below.
    }

/**
 * `26-101`: what `RuStorePushClient.getToken()`'s own `Task.addOnFailureListener` reports, classified
 * into [PushTokenResult.Unavailable] rather than handed back as a bare marker.
 *
 * **Three buckets, not two.** A `RuStorePushClientException` is the SDK's own structured verdict on
 * *this device* - [RuStorePushClientException.isCritical] is the real property the RuStore SDK ships for
 * exactly the question this item asks ("is this actionable, or should the app just retry"), true for
 * [RuStorePushClientException.HostAppNotInstalledException] and
 * [RuStorePushClientException.UnauthorizedException], false for
 * [RuStorePushClientException.HostAppBackgroundWorkPermissionNotGranted] (pushes still arrive, delayed -
 * [PushUnavailableReason]'s own doc comment already says so). An [IOException] is the ordinary shape of a
 * transport failure talking to RuStore's own backend while minting a token - the identical family
 * `ago.chat.android.core.domain.net.NetworkFailure` already carves out for Ktor's own calls - and is
 * never critical: nothing about *this device* is broken, so [DeviceRegistrationCoordinator] retries
 * quietly rather than raising a warning over one bad connection. Everything else - most plausibly an IPC
 * failure talking to the local host app (a dead `Binder`, a `RemoteException`), which RuStore raises no
 * dedicated exception type for - is treated as critical rather than trusted away in silence: the same
 * "never hide an `Unavailable`" rule [PushRegistrationGateway.checkAvailability]'s own doc comment already
 * states for `checkPushAvailability()`'s failure branch.
 */
internal fun Throwable.toTokenFailure(): PushTokenResult.Unavailable {
    val clientException = this as? RuStorePushClientException
    return when {
        clientException != null ->
            PushTokenResult.Unavailable(clientException.toPushUnavailableReason(), critical = clientException.isCritical)
        this is IOException -> PushTokenResult.Unavailable(PushUnavailableReason.Unknown, critical = false)
        else -> PushTokenResult.Unavailable(PushUnavailableReason.Unknown, critical = true)
    }
}
