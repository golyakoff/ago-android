package ago.chat.android.core.domain.bookings

/**
 * `26-117`: what a confirmed-booking row's own "client line" shows — the hard requirement that replaces
 * `ConfirmedBookingRow`'s old fallback to [ago.chat.android.ui.components.IdentifierText] entirely.
 * `docs/backlog/26-117-*.md`'s own hard requirements 1-2, and `docs/design/26-112-*.md`'s own Q1: the hex
 * `shortId` must **never** be a customer's identity on this screen again, and the fallback when
 * [ConfirmedBooking.customerDisplayName] is `null` is a real, recognisable fact when one exists — the
 * masked phone — before ever falling back to a stated "no name" label.
 *
 * A closed, three-arm type rather than a nullable `String` because the row and the detail sheet both
 * need to render each arm slightly differently in principle (a name is prose, a phone is a value) even
 * though `26-117`'s own mockup renders all three with the identical bold style — encoding the fallback
 * order as data, not as an `if`/`else if`/`else` chain repeated at every call site, is what keeps the
 * list row and the detail-sheet header ([ConfirmedBookingDetailSheet]) from drifting apart on this rule.
 */
public sealed interface ConfirmedBookingIdentity {
    /** [ConfirmedBooking.customerDisplayName], non-blank. */
    public data class Name(
        val displayName: String,
    ) : ConfirmedBookingIdentity

    /** No name, but [ConfirmedBooking.phone] is a real, non-blank fact — safe to render as-is: never the
     * full, un-obscured number this class's own name promises against. [confirmedBookingIdentity] is the
     * one place that guarantee is made (`26-125`) — see its own doc comment for how. */
    public data class MaskedPhone(
        val phone: String,
    ) : ConfirmedBookingIdentity

    /** Neither a name nor a phone — the last resort, never the raw customer id. */
    public data object NoName : ConfirmedBookingIdentity
}

/** `26-117`: the fallback chain itself — name, then phone, then "no name at all". Blank strings (not
 * just `null`) are treated as absent on both counts: a display name or a phone that round-tripped as
 * `""` is not a fact worth showing either, and the whole point of this function is to never show an
 * empty or fabricated identity.
 *
 * `26-125` bug 4: [ConfirmedBooking.masked] cannot be trusted blindly here. `ConfirmedBookingResponse.Phone`
 * is documented as "always populated — masked or real", but `Ago.Calendar.Api`'s confirmed-bookings read
 * never actually applies the mask — a disclosed backend gap, `docs/backlog/26-125-*.md` — so a `false`
 * flag on first load means "unmasked by omission", not "safe to show". [maskPhoneForIdentity] is applied
 * whenever the flag says `false`, and skipped (the value trusted verbatim) whenever it says `true` —
 * matching what a genuinely masked wire value already looks like, and idempotent on one this function
 * masked itself, so a future backend fix that starts sending `masked = true` correctly needs no change
 * here.
 *
 * This deliberately does **not** distinguish "never masked" from "revealed on purpose"
 * ([ago.chat.android.bookings.ConfirmedBookingsViewModel.reveal] also leaves [ConfirmedBooking.masked]
 * `false`, with the customer's real number, once a reveal succeeds) — both look identical on the wire,
 * and this fallback *identity* line is not the surface a reveal is supposed to affect: the booking-detail
 * sheet's own «Телефон» row (`ConfirmedBookingDetailBody`) reads [ConfirmedBooking.phone] directly and
 * carries the reveal control, untouched by this function. Client-masking a revealed number back out of
 * *this* label, specifically, keeps a nameless row's own "who is this" text stable rather than turning
 * into a phone number the moment its detail sheet's separate reveal button is pressed — the reveal still
 * shows the real number exactly where the ticket asks it to (`docs/backlog/26-125-*.md`: "the reveal flow
 * — revealing still shows the full number on purpose").
 */
public fun confirmedBookingIdentity(booking: ConfirmedBooking): ConfirmedBookingIdentity {
    val name = booking.customerDisplayName
    if (!name.isNullOrBlank()) return ConfirmedBookingIdentity.Name(name)
    val phone = booking.phone
    if (phone.isNotBlank()) {
        return ConfirmedBookingIdentity.MaskedPhone(if (booking.masked) phone else maskPhoneForIdentity(phone))
    }
    return ConfirmedBookingIdentity.NoName
}

/**
 * `26-125`: keeps the first two and last two characters and replaces everything between with a fixed
 * run of bullets — the identical algorithm `ago-calendar`'s own `PhoneNumber.Masked()` uses
 * (`Ago.Calendar.Domain`, whose own doc comment states this is independently restated on each side that
 * needs it, `ago-chat`'s `ListVisitorContactDetailsHandler.Mask` included, rather than shared, because
 * `adr/0027` forbids `Ago.Calendar.*` and `Ago.Chat.*` — and by the same reasoning, `Ago.Platform.*` —
 * from referencing one another; restated a third time here for the identical reason, one repository
 * further out). The short-value branch is unreachable for any phone this app's own backends can produce
 * (a real number is always longer than four characters once digits and a `+` are counted), kept anyway so
 * this function cannot throw on a malformed value rather than depending on today's minimum length.
 */
private fun maskPhoneForIdentity(phone: String): String =
    if (phone.length <= 4) {
        "•".repeat(phone.length)
    } else {
        "${phone.take(2)}${"•".repeat(phone.length - 4)}${phone.takeLast(2)}"
    }
