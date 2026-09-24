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

    /**
     * Already sorted oldest-deadline-first — [BookingsViewModel] applies
     * [ago.chat.android.core.domain.bookings.oldestDeadlineFirst] before this state is ever built, so
     * the screen never re-sorts what it is handed.
     *
     * `26-49`: [busyBookingIds]/[actionError] are this class's own veto-write state, both defaulted so
     * every existing call site (this file's own tests included) keeps compiling unchanged.
     * [busyBookingIds] is a *set*, not [ago.chat.android.conversations.ConversationRowUi.isClaiming]'s
     * single id, because this screen's three actions (unlike claiming a conversation) can be aimed at
     * more than one row before any of them answers — never the whole list disabled at once
     * (`docs/backlog/26-49-*.md`'s own Scope item 2).
     */
    public data class Loaded(
        val bookings: List<PendingBooking>,
        val busyBookingIds: Set<String> = emptySet(),
        val actionError: BookingActionErrorUi? = null,
    ) : BookingsUiState

    public data object NotConfigured : BookingsUiState

    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : BookingsUiState
}
