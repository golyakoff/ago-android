package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmedBookingsStripDay
import ago.chat.android.core.domain.bookings.DayGroup

/**
 * `26-51`: [ConfirmedBookingsViewModel]'s whole state — the identical four-arm shape
 * [ago.chat.android.bookings.BookingsUiState] already establishes for Ожидают, restated rather than
 * shared because [Loaded] carries a genuinely different shape (a date strip and a day selection, not a
 * flat list).
 */
internal sealed interface ConfirmedBookingsUiState {
    data object Loading : ConfirmedBookingsUiState

    /**
     * [days] is every [DayGroup] the whole range read produced — the screen renders only
     * [selectedDate]'s own group (or the stated empty state, when [days] has none for it), but [strip]
     * needs the whole range to know which other days carry a dot.
     *
     * `26-117`: [revealingCustomerIds]/[actionError] are the booking-detail sheet's own phone-reveal
     * state — the identical, defaulted-so-every-call-site-keeps-compiling shape
     * [ago.chat.android.bookings.ContactsUiState.Loaded] already establishes for Клиенты's own reveal,
     * restated here because a customer with a confirmed booking can appear under more than one
     * [WorkerGroup]/[DayGroup] (several bookings, possibly with different masters or on different days)
     * and every one of those rows must grey out together while that customer's own reveal is in flight —
     * the identical "keyed by customer, not by row" reasoning [ContactsViewModel.reveal]'s own doc
     * comment states in full.
     *
     * `26-233`: [edgeLoading] is the day strip's own rubber-band edge-pull state — non-null while a week
     * fetched off that gesture is in flight, naming which side pulled it. Deliberately a field on
     * [Loaded] rather than a second top-level [ConfirmedBookingsUiState] arm: the whole point of the
     * gesture (`docs/backlog` `26-233`) is that the operator keeps looking at the days they already have
     * while one more week loads at the edge — swapping the whole screen to [Loading] would throw that
     * away for no reason, the identical "a secondary action's own in-flight state rides alongside the
     * data it does not invalidate" shape [revealingCustomerIds] already establishes for a reveal in
     * flight.
     */
    data class Loaded(
        val days: List<DayGroup>,
        val strip: List<ConfirmedBookingsStripDay>,
        val selectedDate: String,
        val revealingCustomerIds: Set<String> = emptySet(),
        val actionError: BookingActionErrorUi? = null,
        val edgeLoading: DateStripEdgeLoad? = null,
    ) : ConfirmedBookingsUiState {
        /** The one [DayGroup] the body actually renders — `null` is the stated empty-day state, not a
         * missing read: [ago.chat.android.core.domain.bookings.groupByDayThenWorker] never produces a
         * group for a day with nothing booked. */
        val selectedDay: DayGroup?
            get() = days.find { it.localDate == selectedDate }
    }

    data object NotConfigured : ConfirmedBookingsUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : ConfirmedBookingsUiState
}

/**
 * `26-233`: which edge of the day strip a rubber-band pull loaded a week from — [Previous] pulls the
 * window one week earlier, [Next] one week later. A plain two-arm enum, the identical "UI-layer
 * vocabulary with nothing outside this package that needs to know it exists" reasoning
 * [ago.chat.android.bookings.MastersDrillDownKind]'s own doc comment gives for itself
 * (`BookingsScreen.kt`), restated here because this one is a property of [ConfirmedBookingsUiState.Loaded]
 * rather than of navigation state.
 */
internal enum class DateStripEdgeLoad {
    Previous,
    Next,
}
