package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.workers.Worker
import ago.chat.android.core.domain.workers.WorkerDraft
import ago.chat.android.core.domain.workers.WorkersApi
import ago.chat.android.core.domain.workers.WorkersResult
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
 * `26-140`: Записи's own «Мастера» segment — the worker dictionary and its full CRUD, over
 * [WorkersApi]. A sibling of [ServicesViewModel], not a merge into it: this reads and writes a different
 * noun through a different port ([WorkersApi], not [ago.chat.android.core.domain.bookings.BookingsApi]),
 * gated server-side on `calendar:configure` alone. Constructed only for an operator holding that
 * permission ([ago.chat.android.shell.AppShellScreen] computes the gate once and [BookingsRoute] calls
 * `hiltViewModel()` only inside that branch), the identical "does not even ask the server for it" gate
 * [ServicesViewModel]'s own doc comment states.
 *
 * **Two write paths, one discipline.** The create/update the form submits ([submit]) and the list-row
 * writes ([toggleActive]/[delete]) are separate methods because they act on different UI — a form over
 * the list, versus a card in it — and carry different busy state ([MastersUiState.Loaded.formBusy] versus
 * [MastersUiState.Loaded.busyWorkerIds]). Every one of them re-reads through [WorkersApi.fetchWorkers] on
 * success rather than patching the roster from the write's own echo, the identical
 * "the authoritative answer is always the next read" discipline [ServicesViewModel] follows.
 *
 * **Replace semantics mean every field travels on every write.** [WorkerDraft] is built whole for a
 * toggle exactly as for a full edit — omitting a field would clear it server-side
 * ([WorkersApi.updateWorker]'s own doc comment), so [toggleActive] carries the worker's current name,
 * calendar and services beside the one flag it flips.
 */
@HiltViewModel
internal class MastersViewModel
    @Inject
    constructor(
        private val api: WorkersApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<MastersUiState>(MastersUiState.Loading)
        val state: StateFlow<MastersUiState> = mutableState.asStateFlow()

        /** Which workers have a *list-row* write in flight — plain instance state, not a lock: every
         * mutation happens on the main dispatcher inside [viewModelScope], the identical "no lock needed"
         * shape [ServicesViewModel]'s own `busyServiceIds` records. */
        private var busyWorkerIds: Set<String> = emptySet()

        init {
            refresh()
        }

        /** The initial load, and the retry a [MastersUiState.Failed] screen offers. Clears the open form
         * along with the busy set: the roster about to arrive may no longer contain the worker being
         * edited, and a form over a row that is gone is worse than no form. */
        fun refresh() {
            mutableState.update { MastersUiState.Loading }
            busyWorkerIds = emptySet()
            viewModelScope.launch {
                applyResult(withContext(ioDispatcher) { api.fetchWorkers() }, actionError = null)
            }
        }

        /** Opens an empty create form, with the first calendar pre-selected. A no-op when there is no
         * calendar to put a worker on — the UI disables Add with a stated note in that case, and this
         * guard is the second half of that same rule. */
        fun startAdd() {
            mutableState.update { current ->
                val loaded = current as? MastersUiState.Loaded ?: return@update current
                if (loaded.calendars.isEmpty()) return@update current
                loaded.copy(editing = blankWorkerForm(loaded.calendars.first().calendarId), actionError = null)
            }
        }

        /** Opens the edit form over one row, prefilled from what the server last said about it. */
        fun edit(worker: Worker) {
            mutableState.update { current ->
                (current as? MastersUiState.Loaded)?.copy(editing = worker.toForm(), actionError = null) ?: current
            }
        }

        /** Closes the form, discarding whatever was typed. No confirmation: nothing has been sent, and
         * the list underneath was never touched ([MastersUiState.Loaded.editing]'s own remarks). */
        fun cancelEdit() {
            mutableState.update { current ->
                (current as? MastersUiState.Loaded)?.copy(editing = null) ?: current
            }
        }

        /** Every keystroke and every checkbox toggle in the open form — the whole form, replaced. A
         * per-field setter API would be many methods for one state object the form already holds entire. */
        fun onFormChanged(form: WorkerForm) {
            mutableState.update { current ->
                (current as? MastersUiState.Loaded)?.copy(editing = form) ?: current
            }
        }

        /**
         * `26-140`: the form's own write — create when [WorkerForm.isCreating], otherwise update. A
         * create without a chosen calendar is refused *here* rather than sent, for the identical
         * "no request to make at all" reason [ServicesViewModel.submit] refuses a blank duration: there
         * is no `POST /workers` body without a calendar to name. Every other rule (a blank name, a
         * duplicate) belongs to the server and arrives back as its own sentence.
         */
        fun submit(form: WorkerForm) {
            val loaded = mutableState.value as? MastersUiState.Loaded ?: return
            if (loaded.formBusy) return
            val calendarId = form.calendarId
            if (form.isCreating && calendarId == null) return

            val draft =
                WorkerDraft(
                    lastName = form.lastName.trim(),
                    firstName = form.firstName.trim(),
                    middleName = form.middleName.trim().ifEmpty { null },
                    displayName = form.displayName.trim().ifEmpty { null },
                    // Ignored by [WorkersApi.updateWorker]; `""` is never reached for a create, which the
                    // guard above already refused when no calendar was chosen.
                    calendarId = calendarId.orEmpty(),
                    isActive = form.isActive,
                    serviceIds = form.serviceIds.toList(),
                )

            mutableState.update { loaded.copy(formBusy = true, actionError = null) }
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        if (form.isCreating) api.createWorker(draft) else api.updateWorker(form.workerId!!, draft)
                    }
                when (result) {
                    // The fresh roster and the closed form land together, from the server's own answer.
                    BookingActionResult.Succeeded ->
                        applyResult(withContext(ioDispatcher) { api.fetchWorkers() }, actionError = null)

                    is BookingActionResult.Refused -> failForm(BookingActionErrorUi.ServerRefusal(result.detail))
                    is BookingActionResult.Failed -> failForm(BookingActionErrorUi.Unavailable(result.reason))
                }
            }
        }

        /**
         * `26-140`: «Снять с активных»/«Вернуть» — an [WorkersApi.updateWorker] with [WorkerDraft.isActive]
         * flipped, carrying every other field verbatim (replace semantics, see this class's own doc
         * comment). Deactivation is this product's way of taking a worker with booking history out of
         * rotation, the counterpart to a [delete] the server refuses for exactly that worker.
         *
         * The current display name is sent as-is to preserve the visible label: the domain [Worker] no
         * longer carries whether that name was human-set or derived (`26-139`'s dropped
         * `displayNameIsCustom`), so preserving what the operator sees is the honest choice over guessing.
         */
        fun toggleActive(worker: Worker) {
            writeRow(worker.workerId) {
                api.updateWorker(
                    worker.workerId,
                    WorkerDraft(
                        lastName = worker.lastName,
                        firstName = worker.firstName,
                        middleName = worker.middleName,
                        displayName = worker.displayName,
                        calendarId = worker.calendarId.orEmpty(),
                        isActive = !worker.isActive,
                        serviceIds = worker.serviceIds,
                    ),
                )
            }
        }

        /**
         * `26-140`: hard-deletes a worker who was never booked. The server refuses ([BookingActionResult.Refused])
         * a worker with any booking history and says to deactivate him instead; that refusal is shown
         * verbatim and the row stays on screen — never a delete that silently did nothing
         * ([WorkersApi.deleteWorker]'s own doc comment). The confirmation dialog is the UI's; this only
         * ever runs once the operator has confirmed.
         */
        fun delete(workerId: String) {
            writeRow(workerId) { api.deleteWorker(workerId) }
        }

        /** The one place [toggleActive] and [delete] share: mark the row busy, call, then either re-read
         * the whole roster or leave it exactly as it was and show the refusal. A refusal never reloads —
         * the rows the operator was looking at stay put, the identical reasoning
         * [ago.chat.android.schedule.WorkingHoursViewModel]'s own `write` records. */
        private fun writeRow(
            workerId: String,
            call: suspend () -> BookingActionResult,
        ) {
            if (workerId in busyWorkerIds) return
            val loaded = mutableState.value as? MastersUiState.Loaded ?: return
            busyWorkerIds = busyWorkerIds + workerId
            mutableState.update { loaded.copy(busyWorkerIds = busyWorkerIds, actionError = null) }

            viewModelScope.launch {
                val result = withContext(ioDispatcher) { call() }
                busyWorkerIds = busyWorkerIds - workerId
                when (result) {
                    BookingActionResult.Succeeded ->
                        applyResult(withContext(ioDispatcher) { api.fetchWorkers() }, actionError = null)

                    is BookingActionResult.Refused -> failRow(BookingActionErrorUi.ServerRefusal(result.detail))
                    is BookingActionResult.Failed -> failRow(BookingActionErrorUi.Unavailable(result.reason))
                }
            }
        }

        /** A refused or failed form write leaves the form open with what the operator typed still in it —
         * blanking it would throw away the very edit they now have to fix. */
        private fun failForm(error: BookingActionErrorUi) {
            mutableState.update { current ->
                (current as? MastersUiState.Loaded)?.copy(formBusy = false, actionError = error) ?: current
            }
        }

        /** A refused or failed list-row write keeps the roster exactly as it was and shows the error
         * above it — the row the operator acted on stays visible. */
        private fun failRow(error: BookingActionErrorUi) {
            mutableState.update { current ->
                (current as? MastersUiState.Loaded)?.copy(busyWorkerIds = busyWorkerIds, actionError = error) ?: current
            }
        }

        /** Turns one [WorkersResult] into the matching [MastersUiState] — the single funnel that keeps
         * [refresh] and the write paths from building [MastersUiState.Loaded] slightly differently. Closes
         * the form on every arm: a fresh roster is exactly the moment an open form's own subject may have
         * changed underneath it. */
        private fun applyResult(
            result: WorkersResult,
            actionError: BookingActionErrorUi?,
        ) {
            mutableState.update {
                when (result) {
                    is WorkersResult.Loaded ->
                        MastersUiState.Loaded(
                            workers = result.workers,
                            calendars = result.calendars,
                            services = result.services,
                            editing = null,
                            busyWorkerIds = busyWorkerIds,
                            formBusy = false,
                            actionError = actionError,
                        )

                    WorkersResult.NotConfigured -> MastersUiState.NotConfigured
                    is WorkersResult.Failed -> MastersUiState.Failed(result.reason)
                }
            }
        }
    }
