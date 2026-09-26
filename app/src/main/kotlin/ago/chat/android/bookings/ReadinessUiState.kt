package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.readiness.CalendarReadiness

/**
 * `26-164`: [ReadinessViewModel]'s whole state — the identical four-arm shape [MastersUiState] and its
 * siblings establish, with no `editing`/`busy*`/`actionError` of any kind: this screen has no write of
 * its own ([ago.chat.android.core.domain.readiness.BookingReadinessApi]'s own doc comment) — its only
 * action, «Исправить», is an in-hub navigation swap [BookingsScreen] carries out, never a mutation this
 * state has to track.
 */
internal sealed interface ReadinessUiState {
    data object Loading : ReadinessUiState

    /** @param calendars one card per server entry, in server order — [CalendarReadiness]'s own doc
     *   comment on the `calendarId == null` synthetic placeholder for a tenant with no calendar. */
    data class Loaded(
        val calendars: List<CalendarReadiness>,
    ) : ReadinessUiState

    data object NotConfigured : ReadinessUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : ReadinessUiState
}
