package ago.chat.android.devices

import ago.chat.android.core.domain.devices.PushProvider

/**
 * `26-06`/`adr/0180`: the app's one seam onto the RuStore Push SDK.
 *
 * **Declared in `:app`, not `:core:domain`.** `ago.chat.android.core.domain.devices.DeviceRegistrationApi`
 * and `InstallationIdProvider` sit in `:core:domain` because they generalise to any client shape a
 * future KMP `commonMain` might reuse (`adr/0178`) - a REST call and a persisted id are not Android
 * concepts. A push provider chosen specifically because it runs on Android (`adr/0180`'s whole
 * argument is Russian-data-residency-on-a-stock-Android-phone) has no such generalisation: an iOS
 * target would need a different provider entirely, wired behind a different gateway. That is the
 * identical reasoning `ago.chat.android.signin.SignInSession`'s own doc comment gives for keeping
 * AppAuth's Custom-Tab-shaped session in `:app` rather than `:core:domain` - a real third-party SDK
 * integration, not a product-level port.
 *
 * The interface itself still names no RuStore type - [PushTokenResult] and [PushAvailability] are
 * this app's own translation of `ru.rustore.sdk.core.tasks.Task`'s callback shape and
 * `ru.rustore.sdk.pushclient.messaging.exception.RuStorePushClientException`'s subtypes into values a
 * unit test can construct with no SDK on the classpath, the identical shielding
 * `ago.chat.android.core.domain.net.NetworkFailure` already does for Ktor/OkHttp's own exceptions.
 */
public interface PushRegistrationGateway {
    /**
     * `26-100`/`adr/0181`: which transport this gateway instance actually talks to - `fcm` or
     * `rustore` - fixed per concrete implementation ([RuStorePushGateway] always reports
     * [PushProvider.RuStore], [FcmPushGateway] always [PushProvider.Fcm]). `di/AppModule.kt`'s
     * `providePushRegistrationGateway` is what picks *which instance* the app's one `@Singleton`
     * binding resolves to, via [TransportSelector] - this property is how
     * [DeviceRegistrationCoordinator] learns which provider that was, so it can tell the server without
     * importing either concrete SDK itself.
     */
    public val provider: PushProvider

    /** `RuStorePushClient.getToken()` - mints one if this device has none. */
    public suspend fun currentToken(): PushTokenResult

    /** `RuStorePushClient.deleteToken()` - best-effort; sign-out proceeds either way (see
     * [DeviceRevocation]'s own doc comment for why a failed delete here is never surfaced). */
    public suspend fun deleteToken()

    /** `RuStorePushClient.checkPushAvailability()`, translated so a caller never has to import an SDK
     * exception type to read the answer. */
    public suspend fun checkAvailability(): PushAvailability
}

/** What asking the SDK for a token came back with. */
public sealed interface PushTokenResult {
    public data class Token(
        val value: String,
    ) : PushTokenResult

    /**
     * The SDK could not answer at all - a distributor problem, a transport failure, anything
     * `Task.addOnFailureListener` reports. Never a reason to crash a sign-in or a periodic job.
     *
     * `26-101`: this used to be a bare `data object`, discarding *why* - which is exactly what let a
     * device with no push host installed sail through [DeviceRegistrationCoordinator.registerThisDevice]
     * as a quiet success. [reason] is the same classification [PushAvailability.Unavailable] already
     * carries for `checkPushAvailability()`, and [critical] mirrors the real SDK's own
     * `RuStorePushClientException.isCritical` (`RuStorePushGateway`'s own doc comment on its token-failure
     * classifier states which exceptions set it and why) - `true` for a device that genuinely cannot
     * receive push (no host, an unrecognised failure talking to the host app) and never for the ordinary
     * shape of a transient network blip, which [DeviceRegistrationCoordinator] retries quietly rather
     * than nags about.
     */
    public data class Unavailable(
        val reason: PushUnavailableReason,
        val critical: Boolean,
    ) : PushTokenResult
}

/**
 * The four real conditions RuStore's own documentation lists for "will a push actually arrive here",
 * folded into [checkAvailability]'s answer - `docs/backlog/26-06-*.md`'s own Scope: "report what
 * `checkPushAvailability()` actually returns, and do not hide an `Unavailable`."
 */
public sealed interface PushAvailability {
    public data object Available : PushAvailability

    public data class Unavailable(
        val reason: PushUnavailableReason,
    ) : PushAvailability
}

/**
 * `RuStorePushClientException`'s three named subtypes, plus [Unknown] for everything
 * `checkPushAvailability()`'s own failure branch or an unrecognised cause reports. Never carries the
 * exception's own message: `NetworkFailure`'s own doc comment already states why this app renders a
 * classification rather than an exception's `toString()` - the identical rule, restated here because a
 * push-availability cause is exactly the same kind of caught `Throwable` that once printed a hostname
 * onto the sign-in screen.
 */
public enum class PushUnavailableReason {
    /** No distributor app (RuStore, or a VK fallback) is installed. */
    HostAppNotInstalled,

    /** A distributor is installed but not exempted from background restrictions - pushes still
     * arrive, delayed. Not critical (`RuStorePushClientException.isCritical` says so for this one). */
    HostAppBackgroundWorkNotGranted,

    /** No RuStore account signed in on the device - RuStore's own docs: may not always be raised even
     * when it applies. */
    Unauthorized,

    /** Every other cause: an unrecognised exception type, or the `Task` itself failing outright. */
    Unknown,
}
