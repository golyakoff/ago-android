package ago.chat.android.devices

import ago.chat.android.core.domain.devices.DeviceRegistrationApi
import ago.chat.android.core.domain.devices.InstallationIdProvider
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
                        deviceRegistrationApi.register(installationIdProvider.installationId(), token.value)
                    PushTokenResult.Unavailable -> true
                }
            }

        /** [AgoPushMessagingService.onNewToken]'s own call - the identical write [registerThisDevice]
         * makes, with the token the rotation callback already handed over rather than asking the SDK
         * for it again. */
        public suspend fun onNewToken(token: String): Boolean =
            withContext(ioDispatcher) {
                deviceRegistrationApi.register(installationIdProvider.installationId(), token)
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
