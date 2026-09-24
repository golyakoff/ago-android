package ago.chat.android.devices

import ago.chat.android.core.domain.devices.DeviceRegistrationApi
import ago.chat.android.core.domain.devices.InstallationIdProvider
import ago.chat.android.core.domain.devices.PushProvider
import ago.chat.android.di.IoDispatcher
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-06`/`adr/0180`: everything the registration half of the three call sites
 * `docs/architecture/push-notifications.md`'s own "Device registration" section names actually share -
 * reading a token from the SDK, upserting the server row, and reporting whether push can work on this
 * device at all.
 *
 * **Why one class rather than three call sites each doing their own thing.** Sign-in,
 * [AgoPushMessagingService.onNewToken] and [DeviceRegistrationWorker] all need the identical sequence -
 * read the installation id, ask the SDK for the current token, PUT it - and a duplicated version of
 * that sequence at each of the three sites is exactly the kind of drift `26-59`'s own history in this
 * codebase warns about (one call site quietly diverging from the other two).
 *
 * **Deliberately holds no `Context` and no `WorkManager`.** [DeviceRegistrationScheduler] (a real
 * `WorkManager` call) and this class are split for exactly that reason - see that interface's own doc
 * comment. What is left here is three interfaces' worth of plain suspend functions, which is what makes
 * `DeviceRegistrationCoordinatorTest` a plain JVM test with fakes and no Android runtime at all.
 */
@Singleton
public class DeviceRegistrationCoordinator
    @Inject
    constructor(
        private val pushGateway: PushRegistrationGateway,
        private val installationIdProvider: InstallationIdProvider,
        private val deviceRegistrationApi: DeviceRegistrationApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : DeviceRevocation,
        DeviceRegistrar {
        private val mutablePushAvailability = MutableStateFlow<PushAvailability?>(null)

        /**
         * The last [PushAvailability] this coordinator observed - `null` before anything has asked yet.
         * `SignInViewModel.pushAvailability` relays this the identical way `hubConnectionState` relays
         * `OperatorHubConnection.state`, so a future screen (`26-19`) has a value to bind to with no
         * further plumbing.
         */
        override val pushAvailability: StateFlow<PushAvailability?> = mutablePushAvailability.asStateFlow()

        /**
         * The registration half of all three call sites: checks and records availability (never
         * hiding an `Unavailable` - logged at `WARN`, the reason only, never a token), then reads the
         * current token and upserts this installation's row. `true` only when the write itself
         * succeeded; a device the SDK cannot answer for at all is not a failure worth retrying
         * (nothing this app does would change that), so it reports success and moves on.
         *
         * `26-101`: `checkPushAvailability()` and `getToken()` are two separate SDK calls that can
         * disagree - RuStore's own docs already admit `checkPushAvailability()` "may not always be
         * raised even when it applies" for [PushUnavailableReason.Unauthorized], and nothing stops
         * `getToken()` failing for a reason the availability check missed entirely. So [reportTokenFailure]
         * runs *after* the assignment above, and only overwrites [pushAvailability] - never clears an
         * `Unavailable` the check itself already found, and never fires for the ordinary shape of a
         * transient network blip ([PushTokenResult.Unavailable.critical] is `false`) that the next
         * `registerThisDevice()` (the periodic worker's own retry) may simply resolve on its own.
         */
        override suspend fun registerThisDevice(): Boolean =
            withContext(ioDispatcher) {
                val availability = pushGateway.checkAvailability()
                mutablePushAvailability.value = availability
                if (availability is PushAvailability.Unavailable) {
                    Log.w(TAG, "push unavailable on this device: ${availability.reason}")
                }

                when (val token = pushGateway.currentToken()) {
                    is PushTokenResult.Token ->
                        deviceRegistrationApi.register(installationIdProvider.installationId(), token.value, pushGateway.provider)
                    is PushTokenResult.Unavailable -> {
                        reportTokenFailure(token.reason, token.critical)
                        true
                    }
                }
            }

        /**
         * `26-101`: the one place a non-transient push failure ever gets to set [pushAvailability] from
         * outside `checkAvailability()`'s own answer - shared between [registerThisDevice]'s own
         * `currentToken()` failure branch above and [AgoPushMessagingService.onError], which reports the
         * identical port-level classification from a second SDK callback that runs with no
         * `registerThisDevice` call anywhere nearby. `internal`, not `private`: [AgoPushMessagingService]
         * is a real `Service` Hilt constructs outside this class, not a fake in a test, so it needs actual
         * module visibility rather than a narrower interface - the same reasoning [onNewToken] itself
         * already is `public` for.
         *
         * A transient (`critical == false`) failure is deliberately a no-op: nagging the operator over
         * one bad connection is exactly the behaviour `docs/backlog/26-101-*.md`'s own Scope rules out
         * ("do not nag on a blip").
         */
        internal fun reportTokenFailure(
            reason: PushUnavailableReason,
            critical: Boolean,
        ) {
            if (!critical) return
            Log.w(TAG, "push token unavailable, non-transient: $reason")
            mutablePushAvailability.value = PushAvailability.Unavailable(reason)
        }

        /**
         * [AgoPushMessagingService.onNewToken]/[AgoFcmMessagingService.onNewToken]'s own call - the
         * identical write [registerThisDevice] makes, with the token the rotation callback already
         * handed over rather than asking the SDK for it again.
         *
         * `26-100`/`adr/0181`: [provider] is the caller's own, not read off [pushGateway] - each
         * messaging service is permanently wired to exactly one transport (RuStore's `onNewToken` fires
         * only for a RuStore-minted token, FCM's only for an FCM one), and that is true regardless of
         * which gateway `di/AppModule.kt` happened to select as *this* device's registration transport.
         */
        public suspend fun onNewToken(
            provider: PushProvider,
            token: String,
        ): Boolean =
            withContext(ioDispatcher) {
                deviceRegistrationApi.register(installationIdProvider.installationId(), token, provider)
            }

        /**
         * `DeviceRevocation`'s own contract: revoke, then delete the local token, in that order.
         * `AgoAuthSession.completeSignOut()` (`signOut()` until `26-93` split it in two) calls this
         * strictly before it clears the stored session - see that function's own doc comment for why
         * the ordering has to be that way round.
         */
        override suspend fun revokeThisDevice() {
            withContext(ioDispatcher) {
                deviceRegistrationApi.revoke(installationIdProvider.installationId())
                pushGateway.deleteToken()
            }
        }

        private companion object {
            const val TAG = "DeviceRegistration"
        }
    }
