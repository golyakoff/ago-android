package ago.chat.android.signin

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.identity.IdentityApi
import ago.chat.android.core.domain.identity.PostSignInRouter
import ago.chat.android.core.domain.identity.ProbeFailure
import ago.chat.android.core.domain.identity.ProbeOutcome
import ago.chat.android.core.domain.identity.RoutingFailure
import ago.chat.android.core.domain.identity.RoutingStep
import ago.chat.android.core.domain.identity.Tenancy
import ago.chat.android.core.domain.identity.TenancyListing
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
                    seat = ProbeOutcome.Unanswered(ProbeFailure.UnexpectedStatus(503)),
                    owner = ProbeOutcome.Accepted,
                )
            val viewModel = viewModelWith(api)

            advanceUntilIdle()
            assertEquals(
                SignInUiState.Unavailable(
                    RoutingFailure.ProbeDidNotAnswer(
                        RoutingStep.OPERATOR_SEAT,
                        ProbeFailure.UnexpectedStatus(503),
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
    ): SignInViewModel =
        SignInViewModel(
            session = session,
            router = PostSignInRouter(api, activeSite),
            activeSite = activeSite,
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
}
