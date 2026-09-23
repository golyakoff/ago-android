package ago.chat.android.core.domain.bookings

/**
 * `26-51`: one confirmed booking — an appointment, not a slot. Reduced from
 * `Ago.Calendar.Contracts.ConfirmedBookingResponse` to the fields Утверждены actually renders, the
 * identical "no field with nothing that reads it" discipline [PendingBooking]'s own doc comment
 * states — `phone`/`masked` are on the wire response too, and both are left off this type on purpose:
 * the masked-phone reveal is `26-53`, a separate item, and carrying the field here with no reveal flow
 * behind it would be exactly the "fabricated affordance" that doc comment warns against.
 *
 * Unlike [PendingBooking], every id here that has a name already carries one —
 * `workerDisplayName`/`serviceName`/`customerDisplayName` — because
 * `GetConfirmedBookingsForTenantHandler` gates the whole read on `customer:read` rather than joining
 * conditionally (`ago-console`'s own `ConfirmedBooking` interface, `calendarApi.ts:216-233`), so there
 * is no wire gap here the way `26-50` had to close for the pending queue.
 */
public data class ConfirmedBooking(
    val bookingId: String,
    val calendarId: String,
    val workerId: String,
    val workerDisplayName: String,
    val serviceId: String,
    val serviceName: String?,
    val customerId: String,
    val customerDisplayName: String?,
    val startsAt: String,
    val endsAt: String,
    /** `YYYY-MM-DD`, business-local — computed server-side, the identical reasoning [weekday]'s own
     * remarks give: the app never derives a calendar day from a bare instant in its own zone. */
    val localDate: String,
    /** 0 = Sunday, matching `ConfirmedBookingResponse.Weekday`'s own convention — computed
     * server-side from [localDate] so this app never derives a weekday from a bare date string in a
     * possibly different zone. */
    val weekday: Int,
)
