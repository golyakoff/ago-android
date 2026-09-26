package ago.chat.android.schedule

import ago.chat.android.bookings.BookingActionErrorUi
import ago.chat.android.core.domain.schedule.WorkingHoursApi
import ago.chat.android.core.domain.schedule.WorkingHoursChangeResult
import ago.chat.android.core.domain.schedule.WorkingHoursReconciliation
import ago.chat.android.core.domain.schedule.WorkingHoursResult
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-97`: Записи's own «Часы» segment — the list of working-hours rules, and the two writes that did
 * not exist in this product until this item.
 *
 * **Why the writes are never refused on "somebody has already booked that day".** A working-hours rule
 * is the materialiser's *input*. `MaterializeAvailabilityHandler` only ever inserts into business-local
 * days that have no event row at all, and only ever forward of the worker schedule's own cursor — so
 * correcting or removing a rule cannot touch, move or destroy a single already-materialised slot, let
 * alone a booked one. The hazard is not corruption, it is silence: the operator fixes 09:00 to 19:00,
 * the screen accepts it, and the next three weeks keep selling the old hours with nothing anywhere to
 * say so. The server therefore answers every write with a [WorkingHoursReconciliation], and this class
 * keeps it in [WorkingHoursUiState.Loaded.notice] until the next write replaces it. Dropping it would
 * be the one thing `26-97` explicitly must not do.
 *
 * **Re-read, never patch.** A successful write is followed by a fresh [WorkingHoursApi.fetchWorkingHours],
 * the identical "the authoritative answer is always the next GET" discipline
 * [ago.chat.android.bookings.BookingsViewModel] already follows for its own veto writes — which is also
 * why the adapter does not bother reading the corrected rule back out of the write's own echo.
 */
@HiltViewModel
internal class WorkingHoursViewModel
    @Inject
    constructor(
        private val api: WorkingHoursApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<WorkingHoursUiState>(WorkingHoursUiState.Loading)
        val state: StateFlow<WorkingHoursUiState> = mutableState.asStateFlow()

        /** Which rules have a write in flight — plain instance state, not a lock: every mutation of it
         * happens on the main dispatcher inside [viewModelScope], the identical "no lock needed" shape
         * [ago.chat.android.bookings.ContactsViewModel]'s own `revealingCustomerIds` records. */
        private var busyRuleIds: Set<String> = emptySet()

        init {
            refresh()
        }

        /** The initial load, and the retry a [WorkingHoursUiState.Failed] screen offers. Clears the
         * notice along with everything else: a reconciliation describes one particular write, and
         * carrying it across a deliberate reload would make it look like a property of the list. */
        fun refresh() {
            mutableState.update { WorkingHoursUiState.Loading }
            busyRuleIds = emptySet()
            viewModelScope.launch {
                applyResult(
                    withContext(ioDispatcher) { api.fetchWorkingHours() },
                    notice = null,
                    noticeWorkerId = null,
                    actionError = null,
                )
            }
        }

        /**
         * Corrects one rule — the weekday and the two wall-clock times, and nothing else. A rule already
         * in [busyRuleIds] is a no-op, the identical "one deliberate tap, one server call" discipline
         * [ago.chat.android.bookings.BookingsViewModel.act] records.
         */
        fun save(
            ruleId: String,
            dayOfWeek: Int,
            startsAt: String,
            endsAt: String,
        ) {
            write(ruleId) { api.updateWorkingHoursRule(ruleId, dayOfWeek, startsAt, endsAt) }
        }

        /** Removes one rule. Never refused on booking history, unlike deleting a worker — see this
         * class's own doc comment for why a rule's removal cannot reach a booked slot. */
        fun delete(ruleId: String) {
            write(ruleId) { api.deleteWorkingHoursRule(ruleId) }
        }

        /**
         * The one place [save] and [delete] share: mark busy, call, then either re-read the whole list
         * carrying the server's own reconciliation forward, or leave the list exactly as it was and
         * show the refusal. A refusal never reloads — the rows the operator was looking at stay put,
         * the identical reasoning [ago.chat.android.bookings.BookingsViewModel]'s own error banner
         * records.
         */
        private fun write(
            ruleId: String,
            call: suspend () -> WorkingHoursChangeResult,
        ) {
            if (ruleId in busyRuleIds) return
            val loaded = mutableState.value as? WorkingHoursUiState.Loaded ?: return
            // Captured from the list as it stood *before* this write - a delete removes the rule from the
            // next GET's own answer, so this is the one place its `workerId` is still known
            // ([WorkingHoursUiState.Loaded.noticeWorkerId]'s own doc comment).
            val writtenWorkerId = loaded.rules.firstOrNull { it.ruleId == ruleId }?.workerId
            busyRuleIds = busyRuleIds + ruleId
            mutableState.update { loaded.copy(busyRuleIds = busyRuleIds, actionError = null) }

            viewModelScope.launch {
                val result = withContext(ioDispatcher) { call() }
                busyRuleIds = busyRuleIds - ruleId

                when (result) {
                    is WorkingHoursChangeResult.Changed -> {
                        val notice = result.reconciliation.takeIf { it.recutFrom != null }
                        applyResult(
                            withContext(ioDispatcher) { api.fetchWorkingHours() },
                            notice = notice,
                            noticeWorkerId = notice?.let { writtenWorkerId },
                            actionError = null,
                        )
                    }

                    is WorkingHoursChangeResult.Refused ->
                        mutableState.update { current ->
                            (current as? WorkingHoursUiState.Loaded ?: return@update current)
                                .copy(busyRuleIds = busyRuleIds, actionError = BookingActionErrorUi.ServerRefusal(result.detail))
                        }

                    is WorkingHoursChangeResult.Failed ->
                        mutableState.update { current ->
                            (current as? WorkingHoursUiState.Loaded ?: return@update current)
                                .copy(busyRuleIds = busyRuleIds, actionError = BookingActionErrorUi.Unavailable(result.reason))
                        }
                }
            }
        }

        /** Turns one [WorkingHoursResult] into the matching [WorkingHoursUiState] — the single funnel
         * that keeps [refresh] and [write] from building [WorkingHoursUiState.Loaded] two slightly
         * different ways. */
        private fun applyResult(
            result: WorkingHoursResult,
            notice: WorkingHoursReconciliation?,
            noticeWorkerId: String?,
            actionError: BookingActionErrorUi?,
        ) {
            mutableState.update {
                when (result) {
                    is WorkingHoursResult.Loaded ->
                        WorkingHoursUiState.Loaded(
                            rules = result.rules,
                            busyRuleIds = busyRuleIds,
                            notice = notice,
                            noticeWorkerId = noticeWorkerId,
                            actionError = actionError,
                        )

                    WorkingHoursResult.NotConfigured -> WorkingHoursUiState.NotConfigured
                    is WorkingHoursResult.Failed -> WorkingHoursUiState.Failed(result.reason)
                }
            }
        }
    }
