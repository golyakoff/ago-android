package ago.chat.android.team

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.team.CreateInviteResult
import ago.chat.android.core.domain.team.OperatorTeamApi
import ago.chat.android.core.domain.team.OperatorTeamResult
import ago.chat.android.core.domain.team.ROLE_ADMIN
import ago.chat.android.core.domain.team.ROLE_OPERATOR
import ago.chat.android.core.domain.team.RoleSeatSummary
import ago.chat.android.core.domain.team.SeatSummaryResult
import ago.chat.android.session.OidcConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `26-56`: the invite form's own state machine — the at-capacity pre-flight (never a round trip once the
 * chosen role is already full), the three-arm [CreateInviteResult] folded into [InviteRefusalUi], and
 * [InviteColleagueViewModel.state] never once holding the one-shot [CreateInviteResult.Created] itself
 * (that class's own doc comment says why) — only [onCreated] ever sees it, exactly once, per [submit]
 * call that reaches the server at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteColleagueViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val oidcConfig =
        OidcConfig(
            issuer = "https://keycloak.example.invalid/realms/ago",
            clientId = "ago-android",
            redirectUri = "ago-android://callback",
            postLogoutRedirectUri = "ago-android://logout-callback",
            apiBaseUrl = "https://chat-api.example.invalid",
            consoleUrl = "https://office.example.invalid",
            calendarApiBaseUrl = null,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts on the Operator role with an empty email and no refusal`() =
        runTest(dispatcher) {
            val viewModel = InviteColleagueViewModel(api = FakeOperatorTeamApi(), oidcConfig = oidcConfig, ioDispatcher = dispatcher)

            assertEquals(
                InviteColleagueFormState(roleName = ROLE_OPERATOR, email = "", submitting = false, refusal = null),
                viewModel.state.value,
            )
        }

    @Test
    fun `emailChanged updates the email and clears a stale refusal`() =
        runTest(dispatcher) {
            val viewModel = InviteColleagueViewModel(api = FakeOperatorTeamApi(), oidcConfig = oidcConfig, ioDispatcher = dispatcher)
            // A blank email is refused before ever reaching the network - see the dedicated test below -
            // so this is a safe way to put a refusal on `state` without a pending coroutine to race.
            viewModel.submit(emptyList()) { _, _ -> }
            assertEquals(InviteRefusalUi.EmptyEmail, viewModel.state.value.refusal)

            viewModel.emailChanged("kolya@example.com")

            assertEquals("kolya@example.com", viewModel.state.value.email)
            assertNull("changing the email clears a stale refusal", viewModel.state.value.refusal)
        }

    @Test
    fun `roleSelected updates the role and clears a stale refusal`() =
        runTest(dispatcher) {
            val viewModel = InviteColleagueViewModel(api = FakeOperatorTeamApi(), oidcConfig = oidcConfig, ioDispatcher = dispatcher)
            viewModel.submit(emptyList()) { _, _ -> }
            assertEquals(InviteRefusalUi.EmptyEmail, viewModel.state.value.refusal)

            viewModel.roleSelected(ROLE_ADMIN)

            assertEquals(ROLE_ADMIN, viewModel.state.value.roleName)
            assertNull("changing the role clears a stale refusal", viewModel.state.value.refusal)
        }

    @Test
    fun `an empty email is refused before ever calling the server`() =
        runTest(dispatcher) {
            val api = FakeOperatorTeamApi()
            val viewModel = InviteColleagueViewModel(api = api, oidcConfig = oidcConfig, ioDispatcher = dispatcher)

            viewModel.emailChanged("   ")
            viewModel.submit(emptyList()) { _, _ -> }

            assertEquals(InviteRefusalUi.EmptyEmail, viewModel.state.value.refusal)
            assertEquals(0, api.createInviteCalls)
        }

    @Test
    fun `a role already at heldSeats greater or equal to limit is refused before the call, naming the role`() =
        runTest(dispatcher) {
            val api = FakeOperatorTeamApi()
            val viewModel = InviteColleagueViewModel(api = api, oidcConfig = oidcConfig, ioDispatcher = dispatcher)
            viewModel.emailChanged("kolya@example.com")
            val seatSummary = listOf(RoleSeatSummary(roleName = ROLE_OPERATOR, heldSeats = 5, limit = 5, overLimit = false))

            viewModel.submit(seatSummary) { _, _ -> }

            assertEquals(InviteRefusalUi.AtCapacity(roleName = ROLE_OPERATOR, limit = 5), viewModel.state.value.refusal)
            assertEquals("the at-capacity pre-flight must never make the round trip", 0, api.createInviteCalls)
        }

    @Test
    fun `overLimit heldSeats one below the limit is not at capacity - the boundary is exact`() =
        runTest(dispatcher) {
            val api = FakeOperatorTeamApi(createInviteResult = CreateInviteResult.Created("i", "c", "2026-10-01T00:00:00Z", false))
            val viewModel = InviteColleagueViewModel(api = api, oidcConfig = oidcConfig, ioDispatcher = dispatcher)
            viewModel.emailChanged("kolya@example.com")
            val seatSummary = listOf(RoleSeatSummary(roleName = ROLE_OPERATOR, heldSeats = 4, limit = 5, overLimit = false))

            viewModel.submit(seatSummary) { _, _ -> }
            advanceUntilIdle()

            assertEquals(1, api.createInviteCalls)
        }

    @Test
    fun `a created invite never appears in state - it is handed to the caller once, via onCreated`() =
        runTest(dispatcher) {
            val api = FakeOperatorTeamApi(createInviteResult = CreateInviteResult.Created("inv-1", "secret", "2026-10-01T00:00:00Z", false))
            val viewModel = InviteColleagueViewModel(api = api, oidcConfig = oidcConfig, ioDispatcher = dispatcher)
            viewModel.emailChanged("kolya@example.com")
            var reportedShareUrl: String? = null
            var reportedSendFailed: Boolean? = null

            viewModel.submit(emptyList()) { shareUrl, sendFailed ->
                reportedShareUrl = shareUrl
                reportedSendFailed = sendFailed
            }
            advanceUntilIdle()

            assertEquals("https://office.example.invalid/invite/secret", reportedShareUrl)
            assertEquals(false, reportedSendFailed)
            assertEquals(
                InviteColleagueFormState(roleName = ROLE_OPERATOR, email = "", submitting = false, refusal = null),
                viewModel.state.value,
            )
        }

    @Test
    fun `sendFailed true still reaches onCreated - never rendered as a refusal`() =
        runTest(dispatcher) {
            val api = FakeOperatorTeamApi(createInviteResult = CreateInviteResult.Created("inv-2", "c2", "2026-10-01T00:00:00Z", true))
            val viewModel = InviteColleagueViewModel(api = api, oidcConfig = oidcConfig, ioDispatcher = dispatcher)
            viewModel.emailChanged("kolya@example.com")
            var reportedSendFailed: Boolean? = null

            viewModel.submit(emptyList()) { _, sendFailed -> reportedSendFailed = sendFailed }
            advanceUntilIdle()

            assertEquals(true, reportedSendFailed)
            assertNull(viewModel.state.value.refusal)
        }

    @Test
    fun `a genuine server refusal is shown verbatim`() =
        runTest(dispatcher) {
            val api = FakeOperatorTeamApi(createInviteResult = CreateInviteResult.Refused("Укажите корректный email."))
            val viewModel = InviteColleagueViewModel(api = api, oidcConfig = oidcConfig, ioDispatcher = dispatcher)
            viewModel.emailChanged("not-an-email")

            viewModel.submit(emptyList()) { _, _ -> }
            advanceUntilIdle()

            assertEquals(InviteRefusalUi.ServerRefusal("Укажите корректный email."), viewModel.state.value.refusal)
            assertEquals(false, viewModel.state.value.submitting)
        }

    @Test
    fun `transport trouble is Unavailable, never a fabricated server message`() =
        runTest(dispatcher) {
            val api = FakeOperatorTeamApi(createInviteResult = CreateInviteResult.Failed(NetworkFailure.NoConnection))
            val viewModel = InviteColleagueViewModel(api = api, oidcConfig = oidcConfig, ioDispatcher = dispatcher)
            viewModel.emailChanged("kolya@example.com")

            viewModel.submit(emptyList()) { _, _ -> }
            advanceUntilIdle()

            assertEquals(InviteRefusalUi.Unavailable(NetworkFailure.NoConnection), viewModel.state.value.refusal)
        }

    @Test
    fun `a second submit while one is already in flight is ignored`() =
        runTest(dispatcher) {
            val api = FakeOperatorTeamApi(hangCreateInvite = true)
            val viewModel = InviteColleagueViewModel(api = api, oidcConfig = oidcConfig, ioDispatcher = dispatcher)
            viewModel.emailChanged("kolya@example.com")

            viewModel.submit(emptyList()) { _, _ -> }
            dispatcher.scheduler.runCurrent()
            assertTrue(viewModel.state.value.submitting)

            viewModel.submit(emptyList()) { _, _ -> }
            dispatcher.scheduler.runCurrent()

            assertEquals("the in-flight guard must stop a second network attempt", 1, api.createInviteCalls)
        }

    private class FakeOperatorTeamApi(
        private val createInviteResult: CreateInviteResult = CreateInviteResult.Failed(NetworkFailure.Unexpected),
        private val hangCreateInvite: Boolean = false,
    ) : OperatorTeamApi {
        var createInviteCalls: Int = 0
            private set

        override suspend fun fetchTeam(): OperatorTeamResult = OperatorTeamResult.Loaded(emptyList())

        override suspend fun fetchSeatSummary(): SeatSummaryResult = SeatSummaryResult.Loaded(emptyList())

        override suspend fun createInvite(
            roleName: String,
            email: String,
        ): CreateInviteResult {
            createInviteCalls++
            if (hangCreateInvite) awaitCancellation()
            return createInviteResult
        }
    }
}
