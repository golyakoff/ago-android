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
     */
    data class Loaded(
        val days: List<DayGroup>,
        val strip: List<ConfirmedBookingsStripDay>,
        val selectedDate: String,
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
