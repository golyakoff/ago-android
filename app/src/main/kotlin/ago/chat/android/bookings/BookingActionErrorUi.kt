package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure

/**
 * `26-49`: what a failed booking-queue write (reject/cancel/no-show, and `26-53`'s own phone reveal)
 * renders as — the identical two-arm split
 * [ago.chat.android.conversations.ClaimErrorUi] already establishes for the conversation queue's own
 * claim refusal, restated here because this screen's failure vocabulary is
 * [BookingsQueueFailure], not [ago.chat.android.core.domain.net.NetworkFailure].
 */
public sealed interface BookingActionErrorUi {
    /** A genuine, server-authored refusal — [ago.chat.android.core.domain.bookings.BookingActionResult.Refused]'s
     * own `detail`, shown to the operator exactly as the server wrote it. */
    public data class ServerRefusal(
        val detail: String,
    ) : BookingActionErrorUi

    /** Everything that is not a genuine refusal — rendered through the identical
     * classification-driven vocabulary [ago.chat.android.bookings.BookingsScreen]'s own `failureMessage`
     * already uses for a failed queue read. */
    public data class Unavailable(
        val reason: BookingsQueueFailure,
    ) : BookingActionErrorUi
}
