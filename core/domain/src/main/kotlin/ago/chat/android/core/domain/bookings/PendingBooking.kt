package ago.chat.android.core.domain.bookings

/**
 * `26-48`: one row of the pending-booking queue, reduced to the fields Ожидают actually renders —
 * `Ago.Calendar.Contracts.PendingBookingResponse` also carries `isOverdue`, which has no distinct
 * rendering here (see [ConfirmationCountdown]'s own doc comment for why a deadline already in the past
 * still reads as an honest, non-negative countdown rather than needing a second visual state). Carrying
 * a field with nothing that reads it would be the same "no name invented" discipline
 * `docs/backlog/26-48-*.md` states, pointed at an unused property instead of a fabricated one.
 *
 * `26-163`: **names, not ids, the way [ConfirmedBooking] already carries them.** `26-50` put
 * `WorkerDisplayName`/`ServiceName` on `PendingBookingResponse` (never gated - a worker's name and a
 * service's name are the shop's own roster, not personal data), and `adr/0184` replaced the calendar-held
 * customer name with an opaque `PersonId`; this class used to predate both and rendered every one of
 * `workerId`/`serviceId`/`calendarId` through `IdentifierText` as a hex short id. That was the "raw
 * engineering view" `docs/backlog/26-163-*.md` names: an operator with seconds to decide was shown
 * `Мастер 01a084ec` instead of a name the wire already had. The ids stay (they are what a write
 * addresses); they are simply no longer what the screen shows.
 *
 * [customerDisplayName] is never read off the wire - `PendingBookingResponse` carries no name, only
 * [customerId] (`personId` on the wire; the property keeps [ConfirmedBooking.customerId]'s own name for the
 * identical "rename deferred, the concept is not" reason [Contact]'s own doc comment states).
 * [ago.chat.android.bookings.BookingsViewModel] fills it in from chat's own
 * [ago.chat.android.core.domain.persons.PersonsApi], the identical display-merge
 * [ago.chat.android.bookings.ConfirmedBookingsViewModel] already does for Утверждены. [pendingBookingIdentity]
 * falls back from a `null` name to the masked phone, then to a stated "no name" - never to the hex id.
 *
 * [phone]/[masked] differ from [ConfirmedBooking]'s in one honest way: **[phone] is nullable here.**
 * `PendingBookingResponse.Phone` is `null` exactly when the caller does not hold `customer:read` for this
 * tenant (`ConsoleContracts.cs`'s own remarks: "the only thing a null here can mean") - the pending queue
 * is readable by an operator with a veto permission alone, unlike the confirmed read, which is gated on
 * `customer:read` outright. So a `null` phone is a real, reachable state on this screen, not a wire
 * forward-compatibility default, and [pendingBookingIdentity] treats it exactly as "no phone to fall back
 * on".
 *
 * [localDate] is the business-local `YYYY-MM-DD` computed server-side, the identical reasoning
 * [ConfirmedBooking.localDate]'s own remarks give; unlike that class there is no server-side `weekday`
 * on this response, and [businessLocalWeekdayOrNull] derives one from the date alone - a calendrical fact
 * with no zone in it, which is what makes that derivation honest where deriving from an instant would not
 * be (`docs/conventions/date-and-time.md`).
 */
public data class PendingBooking(
    val bookingId: String,
    val calendarId: String,
    val workerId: String,
    val workerDisplayName: String,
    val serviceId: String,
    val serviceName: String?,
    val customerId: String,
    val startsAt: String,
    val endsAt: String,
    val localDate: String,
    val confirmationDeadline: String,
    val customerDisplayName: String? = null,
    val phone: String? = null,
    val masked: Boolean = false,
)
