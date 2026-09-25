package ago.chat.android.core.domain.bookings

/**
 * `26-51`: one confirmed booking — an appointment, not a slot. Reduced from
 * `Ago.Calendar.Contracts.ConfirmedBookingResponse` to the fields Утверждены actually renders, the
 * identical "no field with nothing that reads it" discipline [PendingBooking]'s own doc comment
 * states.
 *
 * Unlike [PendingBooking], every id here that has a name already carries one —
 * `workerDisplayName`/`serviceName`/`customerDisplayName` — because
 * `GetConfirmedBookingsForTenantHandler` gates the whole read on `customer:read` rather than joining
 * conditionally (`ago-console`'s own `ConfirmedBooking` interface, `calendarApi.ts:216-233`), so there
 * is no wire gap here the way `26-50` had to close for the pending queue.
 *
 * `26-117` adds three fields this type used to leave off on purpose:
 * - [phone]/[masked] were withheld by `26-51` because the reveal flow (`26-53`) did not have a caller
 *   here yet — it now does, the booking-detail sheet's own «Показать» (`docs/backlog/26-117-*.md`'s own
 *   hard requirement 9), so carrying the field is no longer the "fabricated affordance" this class's own
 *   doc comment used to warn against. `ConfirmedBookingResponse.Phone` is never `null` on the wire
 *   (`ConsoleContracts.cs`'s own remarks: "Always populated — masked or real"), so [phone] is a plain
 *   `String`; the empty-string default below exists only for the wire DTO's own forward-compatibility,
 *   never as a value this app expects a real response to send.
 * - [originConversationId] is **on the wire as of `26-121`** — `ConfirmedBookingResponse.OriginConversationId`,
 *   the chat conversation a booking arrived through, or `null` for a booking with no chat origin. It drives
 *   both the detail sheet's «Источник» row (present → «Из чата») and this app's dialog-navigation affordance
 *   (`docs/backlog/26-117-*.md`'s own "Dialog link"), which now switches itself on for a real chat-origin
 *   booking instead of staying permanently disabled. The default below stays `null` for the wire DTO's own
 *   forward-compatibility, and honestly reflects a booking that carries no origin conversation.
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
    /** `26-117`: the contact's phone, masked or real — this class's own doc comment above states why it
     * is carried now. Never rendered directly except through [confirmedBookingIdentity]'s own fallback or
     * the detail sheet's own «Телефон» row — never a second, independent reveal control. */
    val phone: String = "",
    /** `26-117`: whether [phone] is still the tenant's own rung-masked display value — the identical
     * field [Contact.masked] already carries, read the identical way. */
    val masked: Boolean = false,
    /** `26-117`/`docs/design/26-112-*.md` GAP-C1 — see this class's own doc comment above. */
    val originConversationId: String? = null,
)
