package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.workerslots.WorkerSlot

/**
 * `26-209`/`adr/0187`: [RescheduleBookingViewModel]'s whole state for «Перенести оператором» — the
 * identical four-arm shape [WorkerSlotsUiState] already establishes for the read this screen is built on
 * ([ago.chat.android.core.domain.workerslots.WorkerSlotsApi.fetchSlots], reused rather than a second slot
 * source — `docs/backlog/26-209-*.md`'s own note), plus [Saved] for this screen's own write half
 * [WorkerSlotsUiState] has none of.
 */
internal sealed interface RescheduleBookingUiState {
    data object Loading : RescheduleBookingUiState

    /**
     * @param availableSlots [WorkerSlot.status] ==
     *   [ago.chat.android.core.domain.workerslots.WorkerSlotStatus.Available] only — every other status is
     *   not a legal reschedule target, so this screen filters them out at the source rather than showing
     *   the operator a slot the very next tap would refuse (`docs/backlog/26-209-*.md`'s own scope: "pick
     *   a new time... for the same worker").
     * @param reschedulingEventId the target slot's own [WorkerSlot.eventId] while a reschedule write is in
     *   flight for it — `null` otherwise. Keyed by event id, not a bare boolean, so every *other* slot's
     *   own row stays tappable rather than the whole list locking for one write.
     */
    data class Loaded(
        val availableSlots: List<WorkerSlot>,
        val reschedulingEventId: String? = null,
        val actionError: BookingActionErrorUi? = null,
    ) : RescheduleBookingUiState

    /** The identical "this deployment does not run AGO Calendar at all" fact every sibling
     * `NotConfigured` arm in this app already states — unreachable in practice here (reaching this sheet
     * at all already proved AGO Calendar is configured for this tenant, the identical reasoning
     * [WorkerRecutUiState.NotConfigured]'s own doc comment states), kept as a real, typed arm rather than
     * a `!!`/exception. */
    data object NotConfigured : RescheduleBookingUiState

    /** `worker_slots.invalid_range` — the identical unreachable-in-practice arm
     * [WorkerSlotsUiState.Refused]'s own doc comment explains, restated here because this screen reads
     * through the identical [ago.chat.android.core.domain.workerslots.WorkerSlotsApi.fetchSlots]. */
    data class Refused(
        val detail: String,
    ) : RescheduleBookingUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : RescheduleBookingUiState

    /** `204` — the booking moved. The sheet's own job ends here; a fresh
     * [ago.chat.android.bookings.ConfirmedBookingsViewModel.refresh] (triggered by the caller, not this
     * class — this view model holds no reference to that one) is how the confirmed list observes its own
     * booking's new time, the identical "the caller re-reads, the write result carries no fresh reading
     * back" shape [ago.chat.android.core.domain.bookings.BookingActionResult.Succeeded]'s own doc comment
     * already states for every veto write on this port. */
    data object Saved : RescheduleBookingUiState
}
