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

    data class Loaded(
        val contacts: List<Contact>,
    ) : ContactsUiState

    data object NotConfigured : ContactsUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : ContactsUiState
}
