package ago.chat.android.team

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.team.CreateInviteResult
import ago.chat.android.core.domain.team.OperatorRoleSeat
import ago.chat.android.core.domain.team.OperatorTeamApi
import ago.chat.android.core.domain.team.OperatorTeamFailure
import ago.chat.android.core.domain.team.OperatorTeamMember
import ago.chat.android.core.domain.team.OperatorTeamResult
import ago.chat.android.core.domain.team.RoleSeatSummary
import ago.chat.android.core.domain.team.SeatSummaryResult
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
import org.junit.Before
import org.junit.Test

/**
 * `26-55`: the whole state machine — both reads in parallel, landing on one of the three
 * [PeopleUiState] arms, and a retry that asks again. The `StandardTestDispatcher`/`Dispatchers.setMain`
 * shape `BookingsViewModelTest` already establishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts Loading before either answer comes back`() =
        runTest(dispatcher) {
            val api = FakeOperatorTeamApi(hangFetchTeam = true)
            val viewModel = PeopleViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(PeopleUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `both reads land together as one Loaded state`() =
        runTest(dispatcher) {
            val member =
                OperatorTeamMember(
                    operatorId = "op-1",
                    displayName = "Аня",
                    email = "anya@example.com",
                    roles = listOf(OperatorRoleSeat(roleName = "Admin", holdsSeat = true)),
                )
            val role = RoleSeatSummary(roleName = "Admin", heldSeats = 1, limit = 2, overLimit = false)
            val api =
                FakeOperatorTeamApi(
                    teamResult = OperatorTeamResult.Loaded(listOf(member)),
                    summaryResult = SeatSummaryResult.Loaded(listOf(role)),
                )
            val viewModel = PeopleViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(PeopleUiState.Loaded(members = listOf(member), seatSummary = listOf(role)), viewModel.state.value)
        }

    @Test
    fun `a failed roster read wins over a loaded summary`() =
        runTest(dispatcher) {
            val api =
                FakeOperatorTeamApi(
                    teamResult = OperatorTeamResult.Failed(OperatorTeamFailure.Transport),
                    summaryResult = SeatSummaryResult.Loaded(emptyList()),
                )
            val viewModel = PeopleViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(PeopleUiState.Failed(OperatorTeamFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `a failed summary read is reported when the roster itself loaded`() =
        runTest(dispatcher) {
            val api =
                FakeOperatorTeamApi(
                    teamResult = OperatorTeamResult.Loaded(emptyList()),
                    summaryResult = SeatSummaryResult.Failed(OperatorTeamFailure.Unexpected),
                )
            val viewModel = PeopleViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(PeopleUiState.Failed(OperatorTeamFailure.Unexpected), viewModel.state.value)
        }

    @Test
    fun `refresh asks the server again`() =
        runTest(dispatcher) {
            val api =
                FakeOperatorTeamApi(
                    teamResult = OperatorTeamResult.Failed(OperatorTeamFailure.Unexpected),
                    summaryResult = SeatSummaryResult.Failed(OperatorTeamFailure.Unexpected),
                )
            val viewModel = PeopleViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.fetchTeamCalls)

            api.teamResult = OperatorTeamResult.Loaded(emptyList())
            api.summaryResult = SeatSummaryResult.Loaded(emptyList())
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(2, api.fetchTeamCalls)
            assertEquals(PeopleUiState.Loaded(emptyList(), emptyList()), viewModel.state.value)
        }

    private class FakeOperatorTeamApi(
        var teamResult: OperatorTeamResult = OperatorTeamResult.Loaded(emptyList()),
        var summaryResult: SeatSummaryResult = SeatSummaryResult.Loaded(emptyList()),
        private val hangFetchTeam: Boolean = false,
    ) : OperatorTeamApi {
        var fetchTeamCalls: Int = 0
            private set

        override suspend fun fetchTeam(): OperatorTeamResult {
            fetchTeamCalls++
            if (hangFetchTeam) awaitCancellation()
            return teamResult
        }

        override suspend fun fetchSeatSummary(): SeatSummaryResult = summaryResult

        // `26-56`: never exercised from this suite — [PeopleViewModel] never calls it, only
        // [ago.chat.android.team.InviteColleagueViewModel] does — but this fake still has to answer the
        // interface's third method to compile.
        override suspend fun createInvite(
            roleName: String,
            email: String,
        ): CreateInviteResult = CreateInviteResult.Failed(NetworkFailure.Unexpected)
    }
}
