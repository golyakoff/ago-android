package ago.chat.android.team

import ago.chat.android.core.domain.team.OperatorTeamApi
import ago.chat.android.core.domain.team.OperatorTeamResult
import ago.chat.android.core.domain.team.SeatSummaryResult
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-55`: Люди's whole state machine — one real network round trip (both reads in parallel, the
 * identical `Promise.all` shape `OperatorsTeamPage.tsx`'s own `load()` already uses), no cache, no
 * polling and no hub overlay, the same "a read-only screen needs none of that" posture
 * [ago.chat.android.bookings.BookingsViewModel]'s own doc comment states for Записи's «Ожидают».
 */
@HiltViewModel
public class PeopleViewModel
    @Inject
    constructor(
        private val api: OperatorTeamApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<PeopleUiState>(PeopleUiState.Loading)
        public val state: StateFlow<PeopleUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /**
         * The initial load, and the retry action a [PeopleUiState.Failed] screen offers — the same
         * function for both, [BookingsViewModel.refresh][ago.chat.android.bookings.BookingsViewModel.refresh]'s
         * own doc comment states why a retry is simply "ask again" rather than a second code path.
         *
         * Both reads run concurrently, not one after the other — there is no dependency between the
         * roster and the seat summary, so awaiting them in sequence would only cost latency for
         * nothing. When both fail, the roster's own [OperatorTeamResult.Failed] reason wins: an
         * arbitrary but stable choice (never "whichever `Deferred` happened to resolve first", which
         * would make this screen's own failure message nondeterministic across otherwise-identical
         * runs).
         */
        public fun refresh() {
            mutableState.update { PeopleUiState.Loading }
            viewModelScope.launch {
                val (teamResult, summaryResult) =
                    withContext(ioDispatcher) {
                        coroutineScope {
                            val team = async { api.fetchTeam() }
                            val summary = async { api.fetchSeatSummary() }
                            team.await() to summary.await()
                        }
                    }

                val next =
                    when (teamResult) {
                        is OperatorTeamResult.Failed -> PeopleUiState.Failed(teamResult.reason)
                        is OperatorTeamResult.Loaded ->
                            when (summaryResult) {
                                is SeatSummaryResult.Failed -> PeopleUiState.Failed(summaryResult.reason)
                                is SeatSummaryResult.Loaded ->
                                    PeopleUiState.Loaded(members = teamResult.members, seatSummary = summaryResult.roles)
                            }
                    }
                mutableState.update { next }
            }
        }
    }
