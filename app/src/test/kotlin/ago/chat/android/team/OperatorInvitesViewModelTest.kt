package ago.chat.android.team

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.team.ChangeOperatorRoleResult
import ago.chat.android.core.domain.team.CreateInviteResult
import ago.chat.android.core.domain.team.OperatorInviteListItem
import ago.chat.android.core.domain.team.OperatorInviteStatus
import ago.chat.android.core.domain.team.OperatorInvitesResult
import ago.chat.android.core.domain.team.OperatorTeamApi
import ago.chat.android.core.domain.team.OperatorTeamFailure
import ago.chat.android.core.domain.team.OperatorTeamResult
import ago.chat.android.core.domain.team.RemoveOperatorResult
import ago.chat.android.core.domain.team.RevokeInviteResult
import ago.chat.android.core.domain.team.SeatSummaryResult
import ago.chat.android.core.domain.team.ToggleOperatorSeatResult
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `26-242`: the sent-invite list's own state machine — load (happy, empty, error), and revoke (success
 * refreshes the list, error leaves it intact and raises a retryable flag, a second revoke while one is
 * in flight is ignored). The `StandardTestDispatcher`/`Dispatchers.setMain` shape [PeopleViewModelTest]
 * already establishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OperatorInvitesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun invite(
        id: String,
        status: OperatorInviteStatus,
        email: String = "$id@example.com",
    ) = OperatorInviteListItem(
        operatorInviteId = id,
        email = email,
        createdAt = "2026-09-20T10:00:00Z",
        expiresAt = "2026-09-27T10:00:00Z",
        status = status,
        smtpErrorCode = null,
    )

    @Test
    fun `starts Loading before the list comes back`() =
        runTest(dispatcher) {
            val api = FakeOperatorTeamApi(hangListInvites = true)
            val viewModel = OperatorInvitesViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(OperatorInvitesUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a loaded list lands as Loaded, carrying the invites verbatim`() =
        runTest(dispatcher) {
            val invites = listOf(invite("inv-1", OperatorInviteStatus.Sent), invite("inv-2", OperatorInviteStatus.Redeemed))
            val api = FakeOperatorTeamApi(invitesResult = OperatorInvitesResult.Loaded(invites))
            val viewModel = OperatorInvitesViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(OperatorInvitesUiState.Loaded(invites), viewModel.state.value)
        }

    @Test
    fun `an empty list is Loaded with no entries, never a failure`() =
        runTest(dispatcher) {
            val api = FakeOperatorTeamApi(invitesResult = OperatorInvitesResult.Loaded(emptyList()))
            val viewModel = OperatorInvitesViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(OperatorInvitesUiState.Loaded(emptyList()), viewModel.state.value)
        }

    @Test
    fun `a failed read lands as Failed, carrying the reason`() =
        runTest(dispatcher) {
            val api = FakeOperatorTeamApi(invitesResult = OperatorInvitesResult.Failed(OperatorTeamFailure.Transport))
            val viewModel = OperatorInvitesViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(OperatorInvitesUiState.Failed(OperatorTeamFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `refresh asks the server again`() =
        runTest(dispatcher) {
            val api = FakeOperatorTeamApi(invitesResult = OperatorInvitesResult.Failed(OperatorTeamFailure.Unexpected))
            val viewModel = OperatorInvitesViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.listInvitesCalls)

            api.invitesResult = OperatorInvitesResult.Loaded(emptyList())
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(2, api.listInvitesCalls)
            assertEquals(OperatorInvitesUiState.Loaded(emptyList()), viewModel.state.value)
        }

    @Test
    fun `a successful revoke re-reads the list so the row shows its new status`() =
        runTest(dispatcher) {
            // First read: the invite is still pending. After a revoke it comes back Revoked.
            val api =
                FakeOperatorTeamApi(
                    invitesResult = OperatorInvitesResult.Loaded(listOf(invite("inv-1", OperatorInviteStatus.Sent))),
                    revokeResult = RevokeInviteResult.Revoked,
                )
            val viewModel = OperatorInvitesViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            api.invitesResult = OperatorInvitesResult.Loaded(listOf(invite("inv-1", OperatorInviteStatus.Revoked)))
            viewModel.revoke("inv-1")
            advanceUntilIdle()

            assertEquals(1, api.revokeInviteCalls)
            assertEquals("the list is re-read after a revoke", 2, api.listInvitesCalls)
            assertEquals(
                OperatorInvitesUiState.Loaded(listOf(invite("inv-1", OperatorInviteStatus.Revoked))),
                viewModel.state.value,
            )
        }

    @Test
    fun `a failed revoke leaves the list intact and raises a retryable flag, never re-reading`() =
        runTest(dispatcher) {
            val loaded = listOf(invite("inv-1", OperatorInviteStatus.Sent))
            val api =
                FakeOperatorTeamApi(
                    invitesResult = OperatorInvitesResult.Loaded(loaded),
                    revokeResult = RevokeInviteResult.Failed(OperatorTeamFailure.Transport),
                )
            val viewModel = OperatorInvitesViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.revoke("inv-1")
            advanceUntilIdle()

            assertEquals(1, api.revokeInviteCalls)
            assertEquals("a failed revoke never re-reads the list", 1, api.listInvitesCalls)
            assertEquals(
                OperatorInvitesUiState.Loaded(invites = loaded, revokingId = null, revokeFailed = true),
                viewModel.state.value,
            )
        }

    @Test
    fun `a second revoke while one is already in flight is ignored`() =
        runTest(dispatcher) {
            val api =
                FakeOperatorTeamApi(
                    invitesResult = OperatorInvitesResult.Loaded(listOf(invite("inv-1", OperatorInviteStatus.Sent))),
                    hangRevoke = true,
                )
            val viewModel = OperatorInvitesViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.revoke("inv-1")
            dispatcher.scheduler.runCurrent()
            val inFlight = viewModel.state.value
            assertTrue(inFlight is OperatorInvitesUiState.Loaded && inFlight.revokingId == "inv-1")

            viewModel.revoke("inv-1")
            dispatcher.scheduler.runCurrent()

            assertEquals("the in-flight guard must stop a second revoke", 1, api.revokeInviteCalls)
        }

    private class FakeOperatorTeamApi(
        var invitesResult: OperatorInvitesResult = OperatorInvitesResult.Loaded(emptyList()),
        var revokeResult: RevokeInviteResult = RevokeInviteResult.Revoked,
        private val hangListInvites: Boolean = false,
        private val hangRevoke: Boolean = false,
    ) : OperatorTeamApi {
        var listInvitesCalls: Int = 0
            private set
        var revokeInviteCalls: Int = 0
            private set

        override suspend fun listInvites(): OperatorInvitesResult {
            listInvitesCalls++
            if (hangListInvites) awaitCancellation()
            return invitesResult
        }

        override suspend fun revokeInvite(operatorInviteId: String): RevokeInviteResult {
            revokeInviteCalls++
            if (hangRevoke) awaitCancellation()
            return revokeResult
        }

        // Not exercised here — this suite drives only the invite list and revoke.
        override suspend fun fetchTeam(): OperatorTeamResult = OperatorTeamResult.Loaded(emptyList())

        override suspend fun fetchSeatSummary(): SeatSummaryResult = SeatSummaryResult.Loaded(emptyList())

        override suspend fun createInvite(
            roleNames: Set<String>,
            email: String,
        ): CreateInviteResult = CreateInviteResult.Failed(NetworkFailure.Unexpected)

        // `26-253`: this suite drives neither of the three roster writes ([PeopleViewModel] does) — the
        // fake still answers them to satisfy the interface.
        override suspend fun changeOperatorRole(
            operatorId: String,
            newRoleName: String,
        ): ChangeOperatorRoleResult = ChangeOperatorRoleResult.Changed

        override suspend fun removeOperator(operatorId: String): RemoveOperatorResult = RemoveOperatorResult.Removed

        override suspend fun toggleOperatorSeat(
            operatorId: String,
            roleName: String,
            holdsSeat: Boolean,
        ): ToggleOperatorSeatResult = ToggleOperatorSeatResult.Toggled
    }
}
