package ago.chat.android.team

import ago.chat.android.core.domain.team.ChangeOperatorRoleResult
import ago.chat.android.core.domain.team.OperatorTeamApi
import ago.chat.android.core.domain.team.OperatorTeamResult
import ago.chat.android.core.domain.team.RemoveOperatorResult
import ago.chat.android.core.domain.team.SeatSummaryResult
import ago.chat.android.core.domain.team.ToggleOperatorSeatResult
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
 *
 * `26-253`: also the three operator-management writes `OperatorsTeamPage`'s own row actions offer —
 * change an operator's role, remove them, toggle one of their `(role)` seats. Each is guarded so only one
 * runs at a time ([PeopleUiState.Loaded.pendingWrite]), re-reads the whole roster on success rather than
 * mutating it in place (the server decides the new role/seat/row set, never this class), and surfaces a
 * refusal ([PeopleUiState.Loaded.writeRefusal]) pinned to the operator it targeted. The writes live here,
 * not in a second view model like the invite list's, because they act on the same roster this class owns:
 * a role change or a removal changes exactly what [OperatorTeamApi.fetchTeam]/[OperatorTeamApi.fetchSeatSummary]
 * return, so re-reading through this class's own [reload] is the whole of "reflect the change".
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
         * Both reads run concurrently (in [reload]), not one after the other — there is no dependency
         * between the roster and the seat summary, so awaiting them in sequence would only cost latency
         * for nothing. When both fail, the roster's own [OperatorTeamResult.Failed] reason wins: an
         * arbitrary but stable choice (never "whichever `Deferred` happened to resolve first", which
         * would make this screen's own failure message nondeterministic across otherwise-identical runs).
         */
        public fun refresh() {
            mutableState.update { PeopleUiState.Loading }
            viewModelScope.launch {
                val next = reload()
                mutableState.update { next }
            }
        }

        /**
         * `26-253`: change [operatorId]'s role to [newRoleName] (the row computes the opposite of the role
         * it currently holds, mirroring `ChangeOperatorRoleButton`'s own `isAdmin ? Operator : Admin`).
         * On success the whole roster is re-read; a refusal is pinned to this operator's row — the
         * last-manager guard and the Admin-pool-full `402` worded by [PeopleUiState] itself, any other
         * refusal shown verbatim.
         */
        public fun changeRole(
            operatorId: String,
            newRoleName: String,
        ) {
            if (!startWrite(OperatorWriteInFlight(operatorId, OperatorWriteAction.ChangeRole))) return
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.changeOperatorRole(operatorId, newRoleName) }
                finishWrite(operatorId) {
                    when (result) {
                        ChangeOperatorRoleResult.Changed -> null
                        ChangeOperatorRoleResult.LastManager -> OperatorWriteRefusalReason.LastManager
                        ChangeOperatorRoleResult.AdminSeatFull -> OperatorWriteRefusalReason.SeatFull(newRoleName)
                        is ChangeOperatorRoleResult.Refused -> OperatorWriteRefusalReason.ServerRefusal(result.detail)
                        is ChangeOperatorRoleResult.Failed -> OperatorWriteRefusalReason.Unavailable(result.reason)
                    }
                }
            }
        }

        /**
         * `26-253`: remove [operatorId] from the site. On success the roster is re-read and the row is
         * gone; the last-manager guard is the one refusal worded here, any other `detail`-bearing refusal
         * (a row already removed by the time the tap landed) shown verbatim.
         */
        public fun removeOperator(operatorId: String) {
            if (!startWrite(OperatorWriteInFlight(operatorId, OperatorWriteAction.Remove))) return
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.removeOperator(operatorId) }
                finishWrite(operatorId) {
                    when (result) {
                        RemoveOperatorResult.Removed -> null
                        RemoveOperatorResult.LastManager -> OperatorWriteRefusalReason.LastManager
                        is RemoveOperatorResult.Refused -> OperatorWriteRefusalReason.ServerRefusal(result.detail)
                        is RemoveOperatorResult.Failed -> OperatorWriteRefusalReason.Unavailable(result.reason)
                    }
                }
            }
        }

        /**
         * `26-253`: set whether [operatorId] holds the [roleName] pairing's own seat to [holdsSeat] (the
         * new value — the row passes the opposite of what it shows). On success the roster is re-read so
         * every role's `heldSeats`/`overLimit` reflects the toggle; a toggle-on refused for capacity is
         * worded as the named pool being full, any other refusal shown verbatim.
         */
        public fun toggleSeat(
            operatorId: String,
            roleName: String,
            holdsSeat: Boolean,
        ) {
            if (!startWrite(OperatorWriteInFlight(operatorId, OperatorWriteAction.ToggleSeat, roleName))) return
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.toggleOperatorSeat(operatorId, roleName, holdsSeat) }
                finishWrite(operatorId) {
                    when (result) {
                        ToggleOperatorSeatResult.Toggled -> null
                        is ToggleOperatorSeatResult.SeatFull -> OperatorWriteRefusalReason.SeatFull(result.roleName)
                        is ToggleOperatorSeatResult.Refused -> OperatorWriteRefusalReason.ServerRefusal(result.detail)
                        is ToggleOperatorSeatResult.Failed -> OperatorWriteRefusalReason.Unavailable(result.reason)
                    }
                }
            }
        }

        /**
         * Marks [inFlight] as the running write, clearing any prior refusal — but only when the roster is
         * [PeopleUiState.Loaded] and no write is already in flight (one at a time). Returns `true` when it
         * took, `false` when the write must not start at all (nothing loaded, or a write already running):
         * the caller returns without launching a coroutine in that case.
         */
        private fun startWrite(inFlight: OperatorWriteInFlight): Boolean {
            val current = mutableState.value
            if (current !is PeopleUiState.Loaded || current.pendingWrite != null) return false
            mutableState.update { current.copy(pendingWrite = inFlight, writeRefusal = null) }
            return true
        }

        /**
         * Applies the outcome of a write. [refusalOf] returns `null` for success (the whole roster is
         * re-read, dropping the in-flight marker with it) or the typed reason to pin to [operatorId]'s row
         * (the roster is left exactly as it was, only the marker cleared and the refusal set). Never
         * touches state that has since left [PeopleUiState.Loaded] — a refresh that raced this write wins.
         */
        private suspend fun finishWrite(
            operatorId: String,
            refusalOf: () -> OperatorWriteRefusalReason?,
        ) {
            when (val reason = refusalOf()) {
                null -> {
                    val reloaded = reload()
                    mutableState.update { reloaded }
                }
                else ->
                    mutableState.update {
                        val latest = it
                        if (latest is PeopleUiState.Loaded) {
                            latest.copy(
                                pendingWrite = null,
                                writeRefusal = OperatorWriteRefusal(operatorId, reason),
                            )
                        } else {
                            latest
                        }
                    }
            }
        }

        /** Both roster reads, run concurrently, folded into one [PeopleUiState] — shared by the initial
         * [refresh] and by every write's own success path. The roster's own failure wins over the seat
         * summary's when both fail, for the stable-choice reason [refresh]'s doc comment states. */
        private suspend fun reload(): PeopleUiState {
            val (teamResult, summaryResult) =
                withContext(ioDispatcher) {
                    coroutineScope {
                        val team = async { api.fetchTeam() }
                        val summary = async { api.fetchSeatSummary() }
                        team.await() to summary.await()
                    }
                }

            return when (teamResult) {
                is OperatorTeamResult.Failed -> PeopleUiState.Failed(teamResult.reason)
                is OperatorTeamResult.Loaded ->
                    when (summaryResult) {
                        is SeatSummaryResult.Failed -> PeopleUiState.Failed(summaryResult.reason)
                        is SeatSummaryResult.Loaded ->
                            PeopleUiState.Loaded(members = teamResult.members, seatSummary = summaryResult.roles)
                    }
            }
        }
    }
