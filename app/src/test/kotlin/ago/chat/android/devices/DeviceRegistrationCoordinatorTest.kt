package ago.chat.android.devices

import ago.chat.android.core.domain.devices.DeviceRegistrationApi
import ago.chat.android.core.domain.devices.InstallationIdProvider
import ago.chat.android.core.domain.devices.PushProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-06`: [DeviceRegistrationCoordinator] on a plain JVM, through fakes for its three ports - no
 * `Context`, no `WorkManager`, no RuStore SDK on the classpath at all, which is exactly the property
 * splitting [DeviceRegistrationScheduler] off this class buys (that interface's own doc comment).
 *
 * The property `docs/backlog/26-06-*.md` explicitly asks to be proven by call order, not by the absence
 * of a symptom, is here too: `revokeThisDevice revokes the server row before it deletes the local
 * token`, below, records the actual sequence rather than merely asserting both calls happened.
 */
class DeviceRegistrationCoordinatorTest {
    @Test
    fun `registerThisDevice reads the installation id and the current token, then registers both`() =
        runTest {
            val api = FakeDeviceRegistrationApi()
            val coordinator =
                coordinatorFor(
                    installationId = "install-1",
                    token = PushTokenResult.Token("token-abc"),
                    api = api,
                )

            assertTrue(coordinator.registerThisDevice())
            assertEquals(listOf("install-1" to "token-abc"), api.registerCalls)
        }

    @Test
    fun `registerThisDevice with no token available is still a success, and never calls register`() =
        runTest {
            val api = FakeDeviceRegistrationApi()
            val coordinator =
                coordinatorFor(
                    token = PushTokenResult.Unavailable(PushUnavailableReason.Unknown, critical = false),
                    api = api,
                )

            assertTrue(coordinator.registerThisDevice())
            assertTrue(api.registerCalls.isEmpty())
        }

    @Test
    fun `registerThisDevice raises a warning when the token failure is critical - no host, an IPC failure`() =
        runTest {
            val gateway =
                FakeGateway(
                    availability = PushAvailability.Available,
                    token = PushTokenResult.Unavailable(PushUnavailableReason.HostAppNotInstalled, critical = true),
                )
            val coordinator = coordinatorFor(gateway = gateway)

            // `checkPushAvailability()` itself said `Available` - exactly the disagreement `26-101`'s own
            // Scope calls out ("`checkPushAvailability()` may not always be raised even when it applies").
            // `getToken()` failing critically must still surface, not be shadowed by the earlier `Available`.
            assertTrue(coordinator.registerThisDevice())
            assertEquals(
                PushAvailability.Unavailable(PushUnavailableReason.HostAppNotInstalled),
                coordinator.pushAvailability.value,
            )
        }

    @Test
    fun `registerThisDevice stays quiet when the token failure is merely transient`() =
        runTest {
            val gateway =
                FakeGateway(
                    availability = PushAvailability.Available,
                    token = PushTokenResult.Unavailable(PushUnavailableReason.Unknown, critical = false),
                )
            val coordinator = coordinatorFor(gateway = gateway)

            // A blip talking to RuStore's own backend while minting a token - `docs/backlog/26-101-*.md`'s
            // own Done-when: "a transient network failure does not raise that warning".
            assertTrue(coordinator.registerThisDevice())
            assertEquals(PushAvailability.Available, coordinator.pushAvailability.value)
        }

    @Test
    fun `reportTokenFailure ignores a transient cause - AgoPushMessagingService onError's own hook`() =
        runTest {
            val coordinator = coordinatorFor(gateway = FakeGateway(availability = PushAvailability.Available))
            coordinator.registerThisDevice()
            assertEquals(PushAvailability.Available, coordinator.pushAvailability.value)

            coordinator.reportTokenFailure(PushUnavailableReason.HostAppBackgroundWorkNotGranted, critical = false)

            assertEquals(PushAvailability.Available, coordinator.pushAvailability.value)
        }

    @Test
    fun `reportTokenFailure raises the warning for a critical cause with no registerThisDevice call nearby`() =
        runTest {
            val coordinator = coordinatorFor(gateway = FakeGateway(availability = PushAvailability.Available))
            coordinator.registerThisDevice()
            assertEquals(PushAvailability.Available, coordinator.pushAvailability.value)

            // `AgoPushMessagingService.onError`'s own shape: a distributor failure reported with no
            // `registerThisDevice` call anywhere nearby (a background token refresh, for instance).
            coordinator.reportTokenFailure(PushUnavailableReason.HostAppNotInstalled, critical = true)

            assertEquals(
                PushAvailability.Unavailable(PushUnavailableReason.HostAppNotInstalled),
                coordinator.pushAvailability.value,
            )
        }

    @Test
    fun `registerThisDevice relays the write's own result, including a failure`() =
        runTest {
            val api = FakeDeviceRegistrationApi(registerResult = false)
            val coordinator = coordinatorFor(token = PushTokenResult.Token("t"), api = api)

            assertFalse(coordinator.registerThisDevice())
        }

    @Test
    fun `registerThisDevice records Unavailable rather than hiding it`() =
        runTest {
            val gateway = FakeGateway(availability = PushAvailability.Unavailable(PushUnavailableReason.HostAppNotInstalled))
            val coordinator = coordinatorFor(gateway = gateway)

            assertEquals(null, coordinator.pushAvailability.value)
            coordinator.registerThisDevice()
            assertEquals(
                PushAvailability.Unavailable(PushUnavailableReason.HostAppNotInstalled),
                coordinator.pushAvailability.value,
            )
        }

    @Test
    fun `registerThisDevice records Available too, not only the unhappy path`() =
        runTest {
            val coordinator = coordinatorFor(gateway = FakeGateway(availability = PushAvailability.Available))

            coordinator.registerThisDevice()

            assertEquals(PushAvailability.Available, coordinator.pushAvailability.value)
        }

    @Test
    fun `onNewToken registers the token it was handed, without asking the SDK for a fresh one`() =
        runTest {
            val gateway = FakeGateway(token = PushTokenResult.Token("should-not-be-used"))
            val api = FakeDeviceRegistrationApi()
            val coordinator = coordinatorFor(installationId = "install-1", gateway = gateway, api = api)

            assertTrue(coordinator.onNewToken(PushProvider.RuStore, "rotated-token"))

            assertEquals(listOf("install-1" to "rotated-token"), api.registerCalls)
            assertEquals(0, gateway.currentTokenCalls)
        }

    @Test
    fun `onNewToken re-registers with the same installationId every time - token rotation, not a new install`() =
        runTest {
            val api = FakeDeviceRegistrationApi()
            val coordinator = coordinatorFor(installationId = "install-1", api = api)

            coordinator.onNewToken(PushProvider.RuStore, "token-1")
            coordinator.onNewToken(PushProvider.RuStore, "token-2")

            assertEquals(listOf("install-1" to "token-1", "install-1" to "token-2"), api.registerCalls)
        }

    @Test
    fun `26-100 - onNewToken passes the caller's own provider through, regardless of which gateway is bound`() =
        runTest {
            val api = FakeDeviceRegistrationApi()
            // The bound gateway is RuStore, but `AgoFcmMessagingService`'s own `onNewToken` call always
            // names `PushProvider.Fcm` for itself - that class's own doc comment states why the provider
            // is the caller's, not read off `pushGateway`.
            val coordinator =
                coordinatorFor(
                    installationId = "install-1",
                    gateway = FakeGateway(provider = PushProvider.RuStore),
                    api = api,
                )

            coordinator.onNewToken(PushProvider.Fcm, "an-fcm-token")

            assertEquals(listOf(PushProvider.Fcm), api.registerProviders)
        }

    @Test
    fun `26-100 - registerThisDevice registers with whichever provider the bound gateway reports`() =
        runTest {
            val api = FakeDeviceRegistrationApi()
            val coordinator =
                coordinatorFor(
                    token = PushTokenResult.Token("token"),
                    gateway = FakeGateway(token = PushTokenResult.Token("token"), provider = PushProvider.Fcm),
                    api = api,
                )

            coordinator.registerThisDevice()

            assertEquals(listOf(PushProvider.Fcm), api.registerProviders)
        }

    @Test
    fun `revokeThisDevice revokes the server row before it deletes the local token`() =
        runTest {
            val order = mutableListOf<String>()
            val api =
                object : DeviceRegistrationApi {
                    override suspend fun register(
                        installationId: String,
                        token: String,
                        provider: PushProvider,
                    ): Boolean = true

                    override suspend fun revoke(installationId: String): Boolean {
                        order += "revoke"
                        return true
                    }
                }
            val gateway =
                object : PushRegistrationGateway {
                    override val provider: PushProvider = PushProvider.RuStore

                    override suspend fun currentToken(): PushTokenResult =
                        PushTokenResult.Unavailable(PushUnavailableReason.Unknown, critical = false)

                    override suspend fun deleteToken() {
                        order += "deleteToken"
                    }

                    override suspend fun checkAvailability(): PushAvailability = PushAvailability.Available
                }
            val coordinator = coordinatorFor(gateway = gateway, api = api)

            coordinator.revokeThisDevice()

            // Not merely "both happened" - the exact order. Had `deleteToken` run first, this fails
            // even though both calls would still have landed.
            assertEquals(listOf("revoke", "deleteToken"), order)
        }

    @Test
    fun `revokeThisDevice revokes exactly this installation's own id`() =
        runTest {
            val api = FakeDeviceRegistrationApi()
            val coordinator = coordinatorFor(installationId = "install-42", api = api)

            coordinator.revokeThisDevice()

            assertEquals(listOf("install-42"), api.revokeCalls)
        }

    private fun coordinatorFor(
        installationId: String = "install-1",
        token: PushTokenResult = PushTokenResult.Token("token"),
        gateway: PushRegistrationGateway = FakeGateway(token = token),
        api: DeviceRegistrationApi = FakeDeviceRegistrationApi(),
    ): DeviceRegistrationCoordinator =
        DeviceRegistrationCoordinator(
            pushGateway = gateway,
            installationIdProvider = FakeInstallationIdProvider(installationId),
            deviceRegistrationApi = api,
            ioDispatcher = Dispatchers.Unconfined,
        )

    private class FakeInstallationIdProvider(
        private val id: String,
    ) : InstallationIdProvider {
        override suspend fun installationId(): String = id
    }

    private class FakeGateway(
        private val token: PushTokenResult = PushTokenResult.Token("token"),
        private val availability: PushAvailability = PushAvailability.Available,
        override val provider: PushProvider = PushProvider.RuStore,
    ) : PushRegistrationGateway {
        var currentTokenCalls: Int = 0
            private set

        override suspend fun currentToken(): PushTokenResult {
            currentTokenCalls++
            return token
        }

        override suspend fun deleteToken() {
        }

        override suspend fun checkAvailability(): PushAvailability = availability
    }

    private class FakeDeviceRegistrationApi(
        private val registerResult: Boolean = true,
        private val revokeResult: Boolean = true,
    ) : DeviceRegistrationApi {
        val registerCalls = mutableListOf<Pair<String, String>>()
        val registerProviders = mutableListOf<PushProvider>()
        val revokeCalls = mutableListOf<String>()

        override suspend fun register(
            installationId: String,
            token: String,
            provider: PushProvider,
        ): Boolean {
            registerCalls += installationId to token
            registerProviders += provider
            return registerResult
        }

        override suspend fun revoke(installationId: String): Boolean {
            revokeCalls += installationId
            return revokeResult
        }
    }
}
