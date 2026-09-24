package ago.chat.android.signin

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.identity.IdentityApi
import ago.chat.android.core.domain.identity.PostSignInRouter
import ago.chat.android.core.domain.identity.ProbeOutcome
import ago.chat.android.core.domain.identity.RoutingFailure
import ago.chat.android.core.domain.identity.RoutingStep
import ago.chat.android.core.domain.identity.Tenancy
import ago.chat.android.core.domain.identity.TenancyListing
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.network.auth.AccessTokenProvider
import ago.chat.android.core.network.realtime.OperatorHubConnection
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.devices.DeviceRegistrar
import ago.chat.android.devices.DeviceRegistrationScheduler
import ago.chat.android.devices.PushAvailability
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `26-12`: the view model's own job — turning a routing outcome into a screen, and telling a
 * *cancelled* sign-in apart from a *failed* one.
 *
 * A plain JVM test with no Robolectric: `SignInSession` exists precisely so this suite never has to
 * construct an `AgoAuthSession` (that interface's own doc comment has the reasoning), and nothing
 * below touches an `Intent`'s contents — the one path that needs a real one is the Custom Tab
 * launch, which belongs to `MainActivity` and to `26-20`'s instrumented tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val shop = Tenancy("11111111-1111-1111-1111-111111111111", "Кофейня")
    private val otherShop = Tenancy("22222222-2222-2222-2222-222222222222", "Ярмарка")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `no stored session lands on the launch screen without probing anything`() =
        runTest(dispatcher) {
            val api = FakeIdentityApi(TenancyListing.Known(listOf(shop)), seat = ProbeOutcome.Accepted)
            val viewModel = viewModelWith(api, session = FakeSession(hasSession = false))

            advanceUntilIdle()

            assertEquals(SignInUiState.SignedOut, viewModel.state.value)
            assertEquals(0, api.calls)
        }

    @Test
    fun `a stored session with an operator seat lands signed in, naming the active site`() =
        runTest(dispatcher) {
            val viewModel =
                viewModelWith(FakeIdentityApi(TenancyListing.Known(listOf(shop)), seat = ProbeOutcome.Accepted))

            advanceUntilIdle()

            assertEquals(SignInUiState.SignedIn(shop.siteId), viewModel.state.value)
        }

    @Test
    fun `landing signed in also attempts to connect the hub - a real device found this missing`() =
        runTest(dispatcher) {
            val viewModel =
                viewModelWith(FakeIdentityApi(TenancyListing.Known(listOf(shop)), seat = ProbeOutcome.Accepted))

            // Collected from the start, not read once at the end: `hubConnectionState` is a
            // `StateFlow`, and a late collector would only ever see wherever it landed - the whole
            // point here is to prove `Connecting` was ever reached at all, on the way to a
            // `Disconnected` this invalid host inevitably ends at.
            val observedStates = mutableListOf<OperatorHubConnectionState>()
            val collector = launch { viewModel.hubConnectionState.toList(observedStates) }

            advanceUntilIdle()
            collector.cancel()

            assertEquals(SignInUiState.SignedIn(shop.siteId), viewModel.state.value)
            // `OperatorHubConnectionLifecycle`'s own foreground-only binding left a real gap a unit
            // test cannot see (it needs a real Custom Tab round trip on a real device) - this
            // assertion is the one thing a unit test *can* prove: that reaching `SignedIn` genuinely
            // attempted a connection, rather than only rendering a screen that assumes one exists.
            assertTrue(
                "signing in must attempt to connect the hub, not only route to the signed-in screen",
                observedStates.contains(OperatorHubConnectionState.Connecting),
            )
        }

    @Test
    fun `landing signed in schedules the periodic push job and registers this device - 26-06's own two call sites`() =
        runTest(dispatcher) {
            val deviceRegistrar = FakeDeviceRegistrar()
            val registrationScheduler = FakeDeviceRegistrationScheduler()
            val viewModel =
                viewModelWith(
                    FakeIdentityApi(TenancyListing.Known(listOf(shop)), seat = ProbeOutcome.Accepted),
                    deviceRegistrar = deviceRegistrar,
                    registrationScheduler = registrationScheduler,
                )

            advanceUntilIdle()

            assertEquals(SignInUiState.SignedIn(shop.siteId), viewModel.state.value)
            assertEquals(1, registrationScheduler.scheduleCalls)
            assertEquals(1, deviceRegistrar.registerCalls)
        }

    @Test
    fun `landing signed in also asks MainActivity to request POST_NOTIFICATIONS - 26-18's own trigger`() =
        runTest(dispatcher) {
            val viewModel =
                viewModelWith(FakeIdentityApi(TenancyListing.Known(listOf(shop)), seat = ProbeOutcome.Accepted))

            val received = mutableListOf<Unit>()
            val collector = launch { viewModel.requestNotificationPermissionEvents.toList(received) }

            advanceUntilIdle()
            collector.cancel()

            assertEquals(1, received.size)
        }

    @Test
    fun `several tenancies ask before the app opens, and the answer is what opens it`() =
        runTest(dispatcher) {
            val activeSite = InMemoryActiveSite()
            val viewModel =
                viewModelWith(
                    FakeIdentityApi(TenancyListing.Known(listOf(shop, otherShop)), seat = ProbeOutcome.Accepted),
                    activeSite = activeSite,
                )

            advanceUntilIdle()
            assertEquals(SignInUiState.ChooseSite(listOf(shop, otherShop)), viewModel.state.value)

            viewModel.chooseSite(otherShop.siteId)
            advanceUntilIdle()

            assertEquals(SignInUiState.SignedIn(otherShop.siteId), viewModel.state.value)
            assertEquals(otherShop.siteId, activeSite.currentSiteId())
        }

    @Test
    fun `an owner-only identity lands on the terminal screen`() =
        runTest(dispatcher) {
            val viewModel =
                viewModelWith(
                    FakeIdentityApi(
                        TenancyListing.Known(emptyList()),
                        seat = ProbeOutcome.Refused,
                        owner = ProbeOutcome.Accepted,
                    ),
                )

            advanceUntilIdle()

            assertEquals(SignInUiState.PlatformOwnerTerminal, viewModel.state.value)
        }

    @Test
    fun `an identity that is neither lands on the registration arm`() =
        runTest(dispatcher) {
            val viewModel =
                viewModelWith(
                    FakeIdentityApi(
                        TenancyListing.Known(emptyList()),
                        seat = ProbeOutcome.Refused,
                        owner = ProbeOutcome.Refused,
                    ),
                )

            advanceUntilIdle()

            assertEquals(SignInUiState.Registration, viewModel.state.value)
        }

    @Test
    fun `a 5xx renders the retry arm, and retrying after it recovers reaches the real destination`() =
        runTest(dispatcher) {
            val api =
                FakeIdentityApi(
                    TenancyListing.Known(emptyList()),
                    seat = ProbeOutcome.Unanswered(NetworkFailure.ServerError(503)),
                    owner = ProbeOutcome.Accepted,
                )
            val viewModel = viewModelWith(api)

            advanceUntilIdle()
            assertEquals(
                SignInUiState.Unavailable(
                    RoutingFailure.ProbeDidNotAnswer(
                        RoutingStep.OPERATOR_SEAT,
                        NetworkFailure.ServerError(503),
                    ),
                ),
                viewModel.state.value,
            )

            // The API comes back. Nothing else changed - no new sign-in, no new token.
            api.seat = ProbeOutcome.Accepted
            api.tenancies = TenancyListing.Known(listOf(shop))
            viewModel.retry()
            advanceUntilIdle()

            assertEquals(SignInUiState.SignedIn(shop.siteId), viewModel.state.value)
        }

    @Test
    fun `dismissing the browser is not a failure`() =
        runTest(dispatcher) {
            val viewModel =
                viewModelWith(
                    FakeIdentityApi(TenancyListing.Known(listOf(shop)), seat = ProbeOutcome.Accepted),
                    session = FakeSession(hasSession = false),
                )
            advanceUntilIdle()

            viewModel.onAuthorizationResult(null)
            advanceUntilIdle()

            // `11-17`'s judgement about a replayed callback, applied to a cancelled Custom Tab: it
            // is "not an error at all in the ordinary case", so it renders the launch screen and
            // never a red one.
            assertEquals(SignInUiState.SignedOut, viewModel.state.value)
            assertTrue(viewModel.state.value !is SignInUiState.SignInFailed)
        }

    @Test
    fun `signing out discards the session and returns to the launch screen`() =
        runTest(dispatcher) {
            val session = FakeSession(hasSession = true)
            val viewModel =
                viewModelWith(
                    FakeIdentityApi(TenancyListing.Known(listOf(shop)), seat = ProbeOutcome.Accepted),
                    session = session,
                )
            advanceUntilIdle()

            viewModel.signOut()
            advanceUntilIdle()

            assertEquals(SignInUiState.SignedOut, viewModel.state.value)
            assertEquals(1, session.signOuts)
        }

    // ------------------------------------------------------------------------------------- fakes

    private fun viewModelWith(
        api: FakeIdentityApi,
        activeSite: InMemoryActiveSite = InMemoryActiveSite(),
        session: SignInSession = FakeSession(hasSession = true),
        deviceRegistrar: DeviceRegistrar = FakeDeviceRegistrar(),
        registrationScheduler: DeviceRegistrationScheduler = FakeDeviceRegistrationScheduler(),
    ): SignInViewModel =
        SignInViewModel(
            session = session,
            router = PostSignInRouter(api, activeSite),
            activeSite = activeSite,
            deviceRegistrar = deviceRegistrar,
            registrationScheduler = registrationScheduler,
            // `26-13`/`26-17`'s own connect-on-sign-in fix: `routeNow()` now really does call
            // `connect()` on this instance. A real `OperatorHubConnection` over a deliberately
            // unreachable host (`example.invalid`, RFC 2606) is still simpler than a second port just
            // for this constructor slot - the connect attempt fails fast and is swallowed
            // (`routeNow`'s own `runCatching`), landing on `Disconnected` exactly as a real device's
            // own failed attempt would.
            hubConnection =
                OperatorHubConnection(
                    hubUrl = "https://example.invalid/hubs/operator",
                    accessTokens = FakeAccessTokenProvider(),
                    activeSite = activeSite,
                ),
            ioDispatcher = dispatcher,
        )

    private class FakeSession(
        private val hasSession: Boolean,
    ) : SignInSession {
        var signOuts: Int = 0
            private set

        override suspend fun hasSession(): Boolean = hasSession

        override suspend fun beginAuthorization(): Intent = throw UnsupportedOperationException("not exercised here")

        override suspend fun completeAuthorization(data: Intent): Unit = throw UnsupportedOperationException("not exercised here")

        override suspend fun signOut() {
            signOuts++
        }
    }

    private class FakeIdentityApi(
        var tenancies: TenancyListing,
        var seat: ProbeOutcome = ProbeOutcome.Refused,
        var owner: ProbeOutcome = ProbeOutcome.Refused,
    ) : IdentityApi {
        var calls: Int = 0
            private set

        override suspend fun listMyTenancies(): TenancyListing {
            calls++
            return tenancies
        }

        override suspend fun probeOperatorSeat(): ProbeOutcome {
            calls++
            return seat
        }

        override suspend fun probeOwnerEligibility(): ProbeOutcome {
            calls++
            return owner
        }
    }

    private class InMemoryActiveSite : ActiveSiteSelection {
        private var current: String? = null

        override fun currentSiteId(): String? = current

        override fun select(siteId: String?) {
            current = siteId
        }
    }

    /** `connect()` is now really called (see `viewModelWith`'s own comment) but never reaches this
     * far - `hub.start()` fails resolving `example.invalid` before ever asking for a token. */
    private class FakeAccessTokenProvider : AccessTokenProvider {
        override suspend fun currentAccessToken(): String? = null

        override suspend fun refreshAccessToken(): String? = null
    }

    /** `26-06`: the identical "test through a narrow interface, never a concrete class touching
     * Android" reasoning `FakeAccessTokenProvider` above already applies. */
    private class FakeDeviceRegistrar : DeviceRegistrar {
        var registerCalls: Int = 0
            private set

        override val pushAvailability = MutableStateFlow<PushAvailability?>(null)

        override suspend fun registerThisDevice(): Boolean {
            registerCalls++
            return true
        }
    }

    /** The real implementation calls `WorkManager.getInstance(context)`, which this plain JVM test has
     * no Android runtime to satisfy - see [DeviceRegistrationScheduler]'s own doc comment for why this
     * is a separate interface from [DeviceRegistrar] rather than one more method on it. */
    private class FakeDeviceRegistrationScheduler : DeviceRegistrationScheduler {
        var scheduleCalls: Int = 0
            private set

        override fun schedulePeriodicRegistration() {
            scheduleCalls++
        }
    }
}
