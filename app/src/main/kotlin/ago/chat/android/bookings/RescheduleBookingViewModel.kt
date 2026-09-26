package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.workerslots.WorkerSlotStatus
import ago.chat.android.core.domain.workerslots.WorkerSlotsApi
import ago.chat.android.core.domain.workerslots.WorkerSlotsResult
import ago.chat.android.core.domain.workerslots.defaultWorkerSlotsRange
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
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * `26-209`/`adr/0187`: «Перенести оператором»'s own view model, over [WorkerSlotsApi] (the same-worker
 * available-slot read this screen is built on — reused rather than a new slot source,
 * `docs/backlog/26-209-*.md`'s own note) and [BookingsApi] (the reschedule write,
 * [BookingsApi.rescheduleBooking]). Obtained by [RescheduleBookingSheet] via `hiltViewModel()` **only
 * while the reschedule sheet is open** — the identical Hilt-avoidance-when-ungated shape
 * [WorkerSlotsViewModel]/[WorkerRecutViewModel]'s own doc comments state, restated here for a sheet
 * layered over the confirmed booking's own detail sheet rather than a Masters drill-down page.
 *
 * **Scoped to the sheet, keyed by an explicit [open], not by `hiltViewModel(key = bookingId)`** — the
 * identical [WorkerSlotsViewModel.openWorkerId]'s own doc comment states in full for why: one instance
 * survives across which booking is open, and re-opening the identical booking/worker pair is a no-op
 * rather than a wasted re-fetch.
 *
 * **A fixed range, the identical [WorkerSlotsViewModel]'s own Q5 default.** An adjustable day picker is a
 * follow-up, not this item's own one promise (`docs/backlog/26-209-*.md`'s own Scope: "pick a new time...
 * wires the reschedule API", not a calendar picker of its own).
 */
@HiltViewModel
internal class RescheduleBookingViewModel
    @Inject
    constructor(
        private val workerSlotsApi: WorkerSlotsApi,
        private val bookingsApi: BookingsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<RescheduleBookingUiState>(RescheduleBookingUiState.Loading)
        val state: StateFlow<RescheduleBookingUiState> = mutableState.asStateFlow()

        /** Which booking/worker this instance is currently open for — `null` until the first [open]. The
         * identical "a stray call before the sheet ever opened is a safe no-op" guard
         * [WorkerSlotsViewModel.openWorkerId]'s own doc comment states. */
        private var openBookingId: String? = null
        private var openWorkerId: String? = null

        /** Opens (or reopens) the sheet for [bookingId]/[workerId] — a no-op when it is already the one
         * showing, the identical one-deliberate-switch guard [WorkerSlotsViewModel.open]'s own doc comment
         * states in full. */
        fun open(
            bookingId: String,
            workerId: String,
        ) {
            if (openBookingId == bookingId && openWorkerId == workerId) return
            openBookingId = bookingId
            openWorkerId = workerId
            load(workerId)
        }

        /** The retry a [RescheduleBookingUiState.Failed]/[RescheduleBookingUiState.Refused] screen offers
         * — re-reads the same worker [open] already fixed. */
        fun refresh() {
            val workerId = openWorkerId ?: return
            load(workerId)
        }

        private fun load(workerId: String) {
            mutableState.update { RescheduleBookingUiState.Loading }
            viewModelScope.launch {
                val today = LocalDate.now(ZoneOffset.UTC)
                val range = defaultWorkerSlotsRange(today)
                when (val result = withContext(ioDispatcher) { workerSlotsApi.fetchSlots(workerId, range.from, range.to) }) {
                    is WorkerSlotsResult.Loaded ->
                        mutableState.update {
                            RescheduleBookingUiState.Loaded(
                                availableSlots = result.slots.filter { it.status == WorkerSlotStatus.Available },
                            )
                        }

                    WorkerSlotsResult.NotConfigured -> mutableState.update { RescheduleBookingUiState.NotConfigured }
                    is WorkerSlotsResult.Refused -> mutableState.update { RescheduleBookingUiState.Refused(result.detail) }
                    is WorkerSlotsResult.Failed -> mutableState.update { RescheduleBookingUiState.Failed(result.reason) }
                }
            }
        }

        /**
         * The operator's own tap on one available slot — [BookingsApi.rescheduleBooking]'s own
         * `204`-or-refusal-or-failure three-way split. A refusal (the slot was claimed by someone else in
         * the meantime, or belongs to a different worker) keeps the sheet open with the server's own
         * sentence, shown verbatim — the identical "never a fabricated message, never silently retried"
         * rule every other write on [BookingsApi] already follows.
         */
        fun reschedule(newStartEventId: String) {
            val bookingId = openBookingId ?: return
            val loaded = mutableState.value as? RescheduleBookingUiState.Loaded ?: return
            if (loaded.reschedulingEventId != null) return

            mutableState.update { loaded.copy(reschedulingEventId = newStartEventId, actionError = null) }
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { bookingsApi.rescheduleBooking(bookingId, newStartEventId) }) {
                    BookingActionResult.Succeeded -> mutableState.update { RescheduleBookingUiState.Saved }

                    is BookingActionResult.Refused ->
                        mutableState.update { current ->
                            (current as? RescheduleBookingUiState.Loaded)?.copy(
                                reschedulingEventId = null,
                                actionError = BookingActionErrorUi.ServerRefusal(result.detail),
                            ) ?: current
                        }

                    is BookingActionResult.Failed ->
                        mutableState.update { current ->
                            (current as? RescheduleBookingUiState.Loaded)?.copy(
                                reschedulingEventId = null,
                                actionError = BookingActionErrorUi.Unavailable(result.reason),
                            ) ?: current
                        }
                }
            }
        }
    }
