package ago.chat.android.core.domain.bookings

/**
 * `26-48`: one row of the pending-booking queue, reduced to the fields this wave's card actually
 * renders — `Ago.Calendar.Contracts.PendingBookingResponse` also carries `customerId`, `localDate`,
 * `isOverdue`, `phone` and `masked`; none of those has a screen here yet (the customer contact fields
 * are gated on `customer:read` and belong to a reveal flow this item does not build, and `isOverdue`
 * has no distinct rendering this wave — see [ConfirmationCountdown]'s own doc comment for why a
 * deadline already in the past still reads as an honest, non-negative countdown rather than needing a
 * second visual state). Carrying a field with nothing that reads it would be the same "no name
 * invented" discipline `docs/backlog/26-48-*.md` states for worker/service/calendar, pointed at an
 * unused property instead of a fabricated one.
 *
 * `workerId`/`serviceId`/`calendarId` are ids, never names — `PendingBookingResponse` does not carry a
 * name for any of them yet (`26-50` is the item that adds one), so every one of the three is rendered
 * through [ago.chat.android.ui.components.IdentifierText], the one place this app turns an id into UI.
 */
public data class PendingBooking(
    val bookingId: String,
    val calendarId: String,
    val workerId: String,
    val serviceId: String,
    val startsAt: String,
    val endsAt: String,
    val confirmationDeadline: String,
)
