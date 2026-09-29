package ago.chat.android.core.domain.bookings

/**
 * `26-269`: one booking belonging to a single client — the client-detail hub's own Предстоящие/Прошедшие
 * read (`docs/backlog/26-269-clients-redesign.md` §4/§5, the design's own §8#1: "an operator GETs one
 * person's full booking history"). Reduced from `Ago.Calendar.Contracts.PersonBookingResponse` to the
 * fields the hub actually renders — the identical "no field with nothing that reads it" discipline
 * [Contact]/[ConfirmedBooking]'s own doc comments state, restated here rather than reusing either
 * sibling type: unlike [ConfirmedBooking] (a tenant-wide range, always `Booked`), this list spans every
 * status the calendar still holds a row for and carries no display name at all — the hub already has the
 * client's own name from [Contact], so [PersonBooking] need not display-merge one of its own.
 *
 * [personId] is on the wire (`PersonBookingResponse.PersonId`, echoed back verbatim) but not carried
 * here — the caller already knows which client this list belongs to (it asked for it by id), so a second
 * copy of that same id on every row would be a field nothing reads.
 */
public data class PersonBooking(
    val bookingId: String,
    val calendarId: String,
    val workerId: String,
    val workerDisplayName: String,
    val serviceId: String,
    val serviceName: String?,
    val startsAt: String,
    val endsAt: String,
    /** `YYYY-MM-DD`, business-local — the identical server-computed fact [ConfirmedBooking.localDate]
     * already carries, for the identical reason: this app never derives a calendar day from a bare
     * instant in its own zone. */
    val localDate: String,
    /** 0 = Sunday, matching [ConfirmedBooking.weekday]'s own convention. */
    val weekday: Int,
    val phone: String,
    val masked: Boolean,
    /** `26-269`'s own «Открыть диалог» is keyed off [Contact.customerId] plus a *separate* chat read
     * ([ago.chat.android.core.domain.persons.PersonsApi.fetchPersonConversations]), never off this field —
     * [originConversationId] stays on this row only because `PersonBookingResponse` carries it for parity
     * with [ConfirmedBooking], with no reader of its own on this screen today. */
    val originConversationId: String?,
    val status: PersonBookingStatus,
)

/**
 * `26-269`: `PersonBookingResponse.Status`'s own closed wire vocabulary
 * (`Ago.Calendar.Application.Abstractions.IPersonBookingReadStore`'s own remarks: "never `Cancelled` and
 * never `Available`" — a cancelled booking is not this client's history to show, and an `Available` slot
 * is not a booking at all). [Unknown] is not a fourth *server* value — it is this app's own honest
 * fallback for a status string a future server version might add before this app's own enum catches up,
 * the identical "never invented, never crashes the screen" posture
 * [ago.chat.android.bookings.ConfirmedBookingsViewModel.mergeDisplayNames]'s own doc comment states for an
 * unreachable dependency.
 */
public enum class PersonBookingStatus {
    PendingConfirmation,
    Booked,
    NoShow,
    Unknown,
}
