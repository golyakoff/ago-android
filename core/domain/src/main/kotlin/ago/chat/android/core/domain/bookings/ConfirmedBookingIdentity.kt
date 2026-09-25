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

    /** No name, but [ConfirmedBooking.phone] is a real, non-blank fact — masked or not, rendered exactly
     * as the server sent it, never unmasked by this function. */
    public data class MaskedPhone(
        val phone: String,
    ) : ConfirmedBookingIdentity

    /** Neither a name nor a phone — the last resort, never the raw customer id. */
    public data object NoName : ConfirmedBookingIdentity
}

/** `26-117`: the fallback chain itself — name, then phone, then "no name at all". Blank strings (not
 * just `null`) are treated as absent on both counts: a display name or a phone that round-tripped as
 * `""` is not a fact worth showing either, and the whole point of this function is to never show an
 * empty or fabricated identity. */
public fun confirmedBookingIdentity(booking: ConfirmedBooking): ConfirmedBookingIdentity {
    val name = booking.customerDisplayName
    if (!name.isNullOrBlank()) return ConfirmedBookingIdentity.Name(name)
    val phone = booking.phone
    if (phone.isNotBlank()) return ConfirmedBookingIdentity.MaskedPhone(phone)
    return ConfirmedBookingIdentity.NoName
}
