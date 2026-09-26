package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingRevealSurface
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.workerslots.WorkerSlot
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
 * `26-171` (`26-155` part 3): the «Слоты» drill-down's own view model, over [WorkerSlotsApi] (the read)
 * and [BookingsApi] (the shared, audited phone reveal — [ago.chat.android.core.domain.bookings.BookingsApi.revealCustomerPhone]
 * is not a `WorkerSlotsApi` method of its own; every caller reuses the one endpoint, keyed by its own
 * [BookingRevealSurface]). Obtained by [BookingsRoute] via `hiltViewModel()` **only inside its own
 * drill-down branch** — the identical Hilt-avoidance-when-ungated shape [WorkerScheduleViewModel]'s own
 * doc comment states, restated here for the identical reason and for the identical `26-162` androidTest
 * landmine.
 *
 * **Scoped to the route, keyed by an explicit [open], not by `hiltViewModel(key = workerId)`.** The
 * identical [WorkerScheduleViewModel]'s own doc comment states in full for the same reason: one instance
 * survives across which worker is open, mirroring [ago.chat.android.thread.ThreadViewModel] rather than
 * inventing a business-id-keyed `hiltViewModel()` call this app has no other precedent for.
 *
 * **A fixed range, never a picker.** Q5's accepted default (`docs/design/26-155-*.md`) is what this
 * item ships — [defaultWorkerSlotsRange] read against `LocalDate.now(ZoneOffset.UTC)`, the identical
 * "the console's own `new Date()` is a UTC calendar date" choice
 * [ConfirmedBookingsViewModel.refresh]'s own doc comment restates for the same reason. An adjustable
 * range is a follow-up, not this item's own promise.
 */
@HiltViewModel
internal class WorkerSlotsViewModel
    @Inject
    constructor(
        private val api: WorkerSlotsApi,
        private val bookingsApi: BookingsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<WorkerSlotsUiState>(WorkerSlotsUiState.Loading)
        val state: StateFlow<WorkerSlotsUiState> = mutableState.asStateFlow()

        /** Which worker this instance is currently showing — `null` until the first [open]. The identical
         * "a stray call before a drill-down ever opened is a safe no-op" guard
         * [WorkerScheduleViewModel.openWorkerId]'s own doc comment states. */
        private var openWorkerId: String? = null

        /** `26-117`/`26-51`'s own plain-instance-state shape for a reveal in flight, restated here keyed
         * by [WorkerSlot.personId] rather than a booking's own customer id — see [reveal]'s own doc
         * comment. */
        private var revealingPersonIds: Set<String> = emptySet()

        /** Opens (or reopens) the drill-down for [workerId] — a no-op when it is already the one showing,
         * the identical one-deliberate-switch guard [WorkerScheduleViewModel.open]'s own doc comment
         * states in full. */
        fun open(workerId: String) {
            if (openWorkerId == workerId) return
            openWorkerId = workerId
            revealingPersonIds = emptySet()
            load(workerId)
        }

        /** The retry a [WorkerSlotsUiState.Failed]/[WorkerSlotsUiState.Refused] screen offers — re-reads
         * the same worker [open] already fixed. */
        fun refresh() {
            val workerId = openWorkerId ?: return
            load(workerId)
        }

        private fun load(workerId: String) {
            mutableState.update { WorkerSlotsUiState.Loading }
            viewModelScope.launch {
                val today = LocalDate.now(ZoneOffset.UTC)
                val range = defaultWorkerSlotsRange(today)
                applyResult(withContext(ioDispatcher) { api.fetchSlots(workerId, range.from, range.to) })
            }
        }

        /**
         * `26-171`'s own «Показать» — Q7's accepted audit surface,
         * [BookingRevealSurface.ANDROID_WORKER_SLOTS]. The identical one-reveal-per-customer-at-a-time,
         * match-by-person-not-row shape [ConfirmedBookingsViewModel.reveal]'s own doc comment states in
         * full — [replacePhone] below is this screen's own version of that class's `replacePhone`: a flat
         * list of slots rather than a nested [ago.chat.android.core.domain.bookings.DayGroup]/
         * [ago.chat.android.core.domain.bookings.WorkerGroup], so every slot sharing [personId] (a
         * multi-slot booking run, `WorkerSlot.bookingId`'s own doc comment) is unmasked together in one
         * pass.
         */
        fun reveal(personId: String) {
            if (personId in revealingPersonIds) return
            val loaded = mutableState.value as? WorkerSlotsUiState.Loaded ?: return
            revealingPersonIds = revealingPersonIds + personId
            mutableState.update { loaded.copy(revealingPersonIds = revealingPersonIds, actionError = null) }

            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        bookingsApi.revealCustomerPhone(personId, BookingRevealSurface.ANDROID_WORKER_SLOTS)
                    }
                revealingPersonIds = revealingPersonIds - personId

                mutableState.update { current ->
                    val currentLoaded = current as? WorkerSlotsUiState.Loaded ?: return@update current
                    when (result) {
                        is RevealPhoneResult.Revealed ->
                            currentLoaded.copy(
                                slots = replacePhone(currentLoaded.slots, personId, result.phone),
                                revealingPersonIds = revealingPersonIds,
                                actionError = null,
                            )

                        is RevealPhoneResult.Refused ->
                            currentLoaded.copy(
                                revealingPersonIds = revealingPersonIds,
                                actionError = BookingActionErrorUi.ServerRefusal(result.detail),
                            )

                        is RevealPhoneResult.Failed ->
                            currentLoaded.copy(
                                revealingPersonIds = revealingPersonIds,
                                actionError = BookingActionErrorUi.Unavailable(result.reason),
                            )
                    }
                }
            }
        }

        /** [reveal]'s own in-place unmask — every row sharing [personId], never one row alone, the
         * identical "match by customer, not row" rule [ConfirmedBookingsViewModel.replacePhone]'s own doc
         * comment states. */
        private fun replacePhone(
            slots: List<WorkerSlot>,
            personId: String,
            phone: String,
        ): List<WorkerSlot> = slots.map { slot -> if (slot.personId == personId) slot.copy(phone = phone, masked = false) else slot }

        /** Turns one [WorkerSlotsResult] into the matching [WorkerSlotsUiState] - the single funnel every
         * sibling view model in this package already keeps for the same reason
         * ([WorkerScheduleViewModel.applyResult]'s own doc comment). */
        private fun applyResult(result: WorkerSlotsResult) {
            mutableState.update {
                when (result) {
                    is WorkerSlotsResult.Loaded -> WorkerSlotsUiState.Loaded(slots = result.slots)
                    WorkerSlotsResult.NotConfigured -> WorkerSlotsUiState.NotConfigured
                    is WorkerSlotsResult.Refused -> WorkerSlotsUiState.Refused(result.detail)
                    is WorkerSlotsResult.Failed -> WorkerSlotsUiState.Failed(result.reason)
                }
            }
        }
    }
