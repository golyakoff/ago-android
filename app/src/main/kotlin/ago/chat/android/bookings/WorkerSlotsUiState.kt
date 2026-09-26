package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.workerslots.WorkerSlot

/**
 * `26-171` (`26-155` part 3): [WorkerSlotsViewModel]'s whole state for the «Слоты» drill-down — a
 * four-arm shape over [ago.chat.android.core.domain.workerslots.WorkerSlotsResult]'s own three arms plus
 * [Refused], the identical reason [ago.chat.android.core.domain.workerslots.WorkerSlotsApi]'s own class
 * doc comment states for why that read needs a fourth arm no other sibling drill-down carries:
 * `worker_slots.invalid_range` already comes
 * with a genuine, caller-actionable `detail`, so it is shown verbatim rather than folded into [Failed]'s
 * generic wording. In practice this is unreachable — [WorkerSlotsViewModel] always asks for the fixed
 * Q5 default range (today..+14), which never fails that check — but it is kept as a real, typed arm
 * rather than a `!!`/exception, the identical defensive completeness every sibling `when` over a sealed
 * result in this app already has ([WorkerRecutViewModel.preview]'s own unreachable
 * `RecutPreviewResult.NotConfigured` arm is the most recent precedent for this exact call).
 */
internal sealed interface WorkerSlotsUiState {
    data object Loading : WorkerSlotsUiState

    /**
     * @param slots every slot the fixed default range returned, whatever its status — Q6's accepted
     *   decision (`docs/design/26-155-*.md`) keeps `Cancelled` rows in this list rather than filtering
     *   them out, server/console parity.
     * @param revealingPersonIds which customers have a phone reveal in flight right now — the identical
     *   plain-instance-state shape [ConfirmedBookingsViewModel]'s own field of the same name already
     *   establishes, restated here because this screen's own rows key a reveal by
     *   [WorkerSlot.personId] rather than a booking's own customer id.
     */
    data class Loaded(
        val slots: List<WorkerSlot>,
        val revealingPersonIds: Set<String> = emptySet(),
        val actionError: BookingActionErrorUi? = null,
    ) : WorkerSlotsUiState

    /** The identical "this deployment does not run AGO Calendar at all" fact every sibling
     * `NotConfigured` arm in this app already states. */
    data object NotConfigured : WorkerSlotsUiState

    /** `worker_slots.invalid_range` — see this interface's own class doc comment for why this read gets
     * its own [Refused] arm rather than folding into [Failed]. Shown verbatim, the identical rule every
     * other genuine server refusal in this app follows. */
    data class Refused(
        val detail: String,
    ) : WorkerSlotsUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : WorkerSlotsUiState
}
