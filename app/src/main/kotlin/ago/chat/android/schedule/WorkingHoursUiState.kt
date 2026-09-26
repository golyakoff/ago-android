package ago.chat.android.schedule

import ago.chat.android.bookings.BookingActionErrorUi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.schedule.WorkingHoursReconciliation
import ago.chat.android.core.domain.schedule.WorkingHoursRule

/**
 * `26-97`: [WorkingHoursViewModel]'s whole state — the identical four-arm shape
 * [ago.chat.android.bookings.ContactsUiState] already establishes, restated rather than shared because
 * [Loaded] carries [WorkingHoursRule] and, unlike every sibling, a [notice].
 */
internal sealed interface WorkingHoursUiState {
    data object Loading : WorkingHoursUiState

    /**
     * @param busyRuleIds which rules have a write in flight — a set rather than a single nullable id,
     *   the identical reasoning [ago.chat.android.bookings.BookingsUiState.Loaded]'s own
     *   `busyBookingIds` records: a second rule's own Edit must stay tappable while a first is out on
     *   the network.
     * @param notice the last correction's own [WorkingHoursReconciliation], kept until the next one
     *   replaces it and never auto-dismissed. This is `26-97`'s one hard constraint made visible —
     *   see [WorkingHoursViewModel] for why an edit is always allowed and therefore owes the operator
     *   this instead of a refusal.
     * @param noticeWorkerId the [WorkingHoursRule.workerId] the write behind [notice] belonged to — `null`
     *   exactly when [notice] is. `26-172` (`26-155` part 4) added this: the Часы `RecutNotice` became
     *   tappable, opening the Masters «Пересчёт» drill-down for *that worker*, prefilled with
     *   [WorkingHoursReconciliation.recutFrom] — a fact [WorkingHoursReconciliation] itself has no field
     *   for (it is a per-write server answer with no worker id of its own on the wire), so
     *   [WorkingHoursViewModel] carries it alongside the notice rather than inside it.
     */
    data class Loaded(
        val rules: List<WorkingHoursRule>,
        val busyRuleIds: Set<String> = emptySet(),
        val notice: WorkingHoursReconciliation? = null,
        val noticeWorkerId: String? = null,
        val actionError: BookingActionErrorUi? = null,
    ) : WorkingHoursUiState

    data object NotConfigured : WorkingHoursUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : WorkingHoursUiState
}
