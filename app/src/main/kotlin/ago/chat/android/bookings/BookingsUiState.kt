package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.PendingBooking

/**
 * `26-48`: [BookingsViewModel]'s whole state — a direct reflection of
 * [ago.chat.android.core.domain.bookings.PendingBookingsResult] plus the one state that result type has
 * no reason to know about, [Loading] (before the first answer has come back at all).
 */
public sealed interface BookingsUiState {
    public data object Loading : BookingsUiState

    /** Already sorted oldest-deadline-first — [BookingsViewModel] applies
     * [ago.chat.android.core.domain.bookings.oldestDeadlineFirst] before this state is ever built, so
     * the screen never re-sorts what it is handed. */
    public data class Loaded(
        val bookings: List<PendingBooking>,
    ) : BookingsUiState

    public data object NotConfigured : BookingsUiState

    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : BookingsUiState
}
