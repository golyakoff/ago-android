package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.recut.RecutApi
import ago.chat.android.core.domain.recut.RecutPreviewResult
import ago.chat.android.core.domain.workerschedule.SaveWorkerScheduleResult
import ago.chat.android.core.domain.workerschedule.WorkerScheduleApi
import ago.chat.android.core.domain.workerschedule.WorkerScheduleResult
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
 * `26-170` (`26-155` part 2): the «График» drill-down's own view model, over [WorkerScheduleApi] (read
 * and write) and [RecutApi] (the minimal Q2 hook, preview only). Obtained by [BookingsRoute] via
 * `hiltViewModel()` **only inside its own drill-down branch** — the identical Hilt-avoidance-when-ungated
 * shape [MastersViewModel]'s own doc comment states, restated here for one more reason: this class is
 * scoped to the whole Записи route (not one open worker), so constructing it at all would run its
 * `init` even for an operator who has not opened a drill-down yet, the exact waste that shape exists to
 * avoid.
 *
 * **Scoped to the route, keyed by an explicit [open], not by `hiltViewModel(key = workerId)`.** This
 * mirrors [ago.chat.android.thread.ThreadViewModel] exactly — one instance survives across which worker
 * is open, and [open] is the `LaunchedEffect`-driven switch [ago.chat.android.thread.ThreadViewModel.open]'s
 * own doc comment already establishes for which conversation is open. A fresh `hiltViewModel()` per worker id was the
 * alternative; this app has no existing call site that keys `hiltViewModel()` by a business id at all,
 * so following its one existing precedent for "the same screen, a different subject" is the smaller
 * departure from what is already here.
 *
 * **Re-read, never patch.** A successful save is followed by a fresh [WorkerScheduleApi.fetchSchedule],
 * the identical "the authoritative answer is always the next GET" discipline [MastersViewModel] and
 * [ago.chat.android.schedule.WorkingHoursViewModel] both already follow — despite
 * [SaveWorkerScheduleResult.Saved] already carrying the stored schedule, so a screen that just saved
 * could in principle skip the round trip; re-reading anyway keeps exactly one code path
 * ([applyResult]) responsible for turning a server answer into [WorkerScheduleUiState], rather than a
 * second one for the save's own echo.
 */
@HiltViewModel
internal class WorkerScheduleViewModel
    @Inject
    constructor(
        private val api: WorkerScheduleApi,
        private val recutApi: RecutApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<WorkerScheduleUiState>(WorkerScheduleUiState.Loading)
        val state: StateFlow<WorkerScheduleUiState> = mutableState.asStateFlow()

        /** Which worker this instance is currently showing — `null` until the first [open]. Read by
         * every write path below so a stray call before a drill-down ever opened is a safe no-op rather
         * than a request for a blank worker id. */
        private var openWorkerId: String? = null

        /**
         * Opens (or reopens) the drill-down for [workerId] — a no-op when it is already the one showing,
         * the identical "one deliberate switch, not a re-fetch on every recomposition" guard
         * [ago.chat.android.thread.ThreadViewModel.open]'s own doc comment states. [BookingsRoute] calls this from a
         * `LaunchedEffect(workerId)`, so it runs exactly once per distinct worker opened, including the
         * very first one.
         */
        fun open(workerId: String) {
            if (openWorkerId == workerId) return
            openWorkerId = workerId
            load(workerId)
        }

        /** The retry a [WorkerScheduleUiState.Failed] screen offers — re-reads the same worker [open]
         * already fixed rather than accepting one, since a drill-down never retries for a different
         * subject than the one it is showing. */
        fun refresh() {
            val workerId = openWorkerId ?: return
            load(workerId)
        }

        private fun load(workerId: String) {
            mutableState.update { WorkerScheduleUiState.Loading }
            viewModelScope.launch {
                applyResult(withContext(ioDispatcher) { api.fetchSchedule(workerId) })
            }
        }

        /** Every keystroke, `FilterChip` tap and checkbox toggle in the open form — the whole form,
         * replaced, the identical "one state object the form already holds entire" rule
         * [MastersViewModel.onFormChanged]'s own doc comment states. */
        fun onFormChanged(form: WorkerScheduleForm) {
            mutableState.update { current -> (current as? WorkerScheduleUiState.Loaded)?.copy(form = form) ?: current }
        }

        /**
         * «Создать расписание» / «Сохранить расписание» — one verb either way
         * ([WorkerScheduleApi.saveSchedule]'s own doc comment: the server itself has one endpoint for
         * both). A form whose numbers do not yet parse refuses locally
         * ([WorkerScheduleForm.toDraftOrNull]) rather than sending a request the server would have to
         * refuse instead.
         */
        fun submit() {
            val workerId = openWorkerId ?: return
            val loaded = mutableState.value as? WorkerScheduleUiState.Loaded ?: return
            if (loaded.formBusy) return
            val draft = loaded.form.toDraftOrNull() ?: return

            mutableState.update { loaded.copy(formBusy = true, actionError = null) }
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.saveSchedule(workerId, draft) }) {
                    // The fresh schedule and the cleared busy flag land together, from the server's own
                    // answer - see this class's own doc comment for why this re-reads rather than
                    // trusting [SaveWorkerScheduleResult.Saved]'s own echo.
                    is SaveWorkerScheduleResult.Saved -> load(workerId)
                    is SaveWorkerScheduleResult.Refused -> failForm(BookingActionErrorUi.ServerRefusal(result.detail))
                    is SaveWorkerScheduleResult.Failed -> failForm(BookingActionErrorUi.Unavailable(result.reason))
                }
            }
        }

        /**
         * `26-155` Q2's minimal re-cut hook: a read-only [RecutApi.preview], anchored at the schedule's
         * own [ago.chat.android.core.domain.workerschedule.WorkerSchedule.materializeFrom] (its cursor -
         * the earliest date a re-cut could ever move, the same bound the full three-step screen's own
         * first step would default to when reached from here rather than from the Часы notice). No
         * decisions, no confirm/execute - [WorkerScheduleUiState.Loaded.recut]'s own doc comment states
         * why the rest is a follow-up slice.
         */
        fun previewRecut() {
            val workerId = openWorkerId ?: return
            val loaded = mutableState.value as? WorkerScheduleUiState.Loaded ?: return
            val from = loaded.existing?.materializeFrom ?: return
            if (loaded.recut is RecutHookUiState.Loading) return

            mutableState.update { loaded.copy(recut = RecutHookUiState.Loading) }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { recutApi.preview(workerId, from) }
                mutableState.update { current ->
                    val currentLoaded = current as? WorkerScheduleUiState.Loaded ?: return@update current
                    currentLoaded.copy(
                        recut =
                            when (result) {
                                is RecutPreviewResult.Loaded -> RecutHookUiState.Loaded(result.preview)
                                is RecutPreviewResult.Refused -> RecutHookUiState.Refused(result.detail)
                                is RecutPreviewResult.Failed -> RecutHookUiState.Failed(result.reason)
                                // Unreachable in practice - reading the schedule already proved AGO
                                // Calendar is configured for this tenant - kept as a real, typed arm
                                // rather than a `!!`/exception, the same defensive completeness every
                                // sibling `when` over a sealed result in this app already has.
                                RecutPreviewResult.NotConfigured ->
                                    RecutHookUiState.Failed(BookingsQueueFailure.Unexpected)
                            },
                    )
                }
            }
        }

        /** Closes the minimal re-cut preview dialog — nothing was sent, so there is nothing to undo. */
        fun dismissRecutPreview() {
            mutableState.update { current ->
                (current as? WorkerScheduleUiState.Loaded)?.copy(recut = RecutHookUiState.Idle) ?: current
            }
        }

        /** A refused or failed save leaves the form open with what the operator typed still in it -
         * blanking it would throw away the very edit they now have to fix, the identical rule
         * [MastersViewModel.failForm]'s own doc comment states. */
        private fun failForm(error: BookingActionErrorUi) {
            mutableState.update { current ->
                (current as? WorkerScheduleUiState.Loaded)?.copy(formBusy = false, actionError = error) ?: current
            }
        }

        /** Turns one [WorkerScheduleResult] into the matching [WorkerScheduleUiState] - the single
         * funnel that keeps [load] and [submit]'s own success path from building
         * [WorkerScheduleUiState.Loaded] two slightly different ways. */
        private fun applyResult(result: WorkerScheduleResult) {
            mutableState.update {
                when (result) {
                    is WorkerScheduleResult.Loaded ->
                        WorkerScheduleUiState.Loaded(existing = result.schedule, form = result.schedule.toForm())

                    // The real "nothing saved yet" state - an empty form, not a failure banner
                    // ([WorkerScheduleUiState]'s own doc comment).
                    WorkerScheduleResult.None ->
                        WorkerScheduleUiState.Loaded(existing = null, form = blankWorkerScheduleForm())

                    WorkerScheduleResult.NotConfigured -> WorkerScheduleUiState.NotConfigured
                    is WorkerScheduleResult.Failed -> WorkerScheduleUiState.Failed(result.reason)
                }
            }
        }
    }
