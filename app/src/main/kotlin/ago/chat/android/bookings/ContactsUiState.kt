package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.Contact

/**
 * `26-52`: [ContactsViewModel]'s whole state — the identical four-arm shape
 * [ago.chat.android.bookings.BookingsUiState]/[ConfirmedBookingsUiState] already establish, restated
 * rather than shared because [Loaded] carries [Contact], a flat list with no day/range concept behind
 * it (this segment's own read has no range parameter at all, unlike Утверждены's).
 */
internal sealed interface ContactsUiState {
    data object Loading : ContactsUiState

    /**
     * `26-53`: [revealingCustomerIds]/[actionError] are this screen's own phone-reveal state, both
     * defaulted so every existing call site keeps compiling unchanged. [revealingCustomerIds] is a
     * *set*, not a single nullable id — [ago.chat.android.bookings.BookingsUiState.Loaded]'s own doc
     * comment on `busyBookingIds` states the identical reasoning for the pending queue's own three veto
     * actions: a second customer's own reveal must stay tappable while a first is still out on the
     * network. Keyed by *customer*, not by row: `docs/backlog/26-53-*.md`'s own "What is actually true
     * today" section cites `CalendarQueuePage.tsx`'s own `revealingCustomerId`, tracked by customer id
     * so that every row sharing one customer (a customer can have several pending bookings) is disabled
     * together, never independently.
     */
    data class Loaded(
        val contacts: List<Contact>,
        val revealingCustomerIds: Set<String> = emptySet(),
        val actionError: BookingActionErrorUi? = null,
    ) : ContactsUiState

    data object NotConfigured : ContactsUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : ContactsUiState
}
