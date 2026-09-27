package ago.chat.android.core.domain.bookings

/**
 * `26-74`: one row of this tenant's own phone-reveal audit trail — reduced from
 * `Ago.Calendar.Contracts.ContactPhoneRevealResponse` to its own five fields verbatim, the same
 * "field names match the wire response" discipline [Contact]/[PendingBooking] already state.
 *
 * **No name for [customerId]/[operatorId] — `ContactPhoneRevealResponse` carries none for either**, so
 * both render through [ago.chat.android.ui.components.IdentifierText], never a fabricated label
 * (`docs/backlog/26-74-*.md`'s own Done-when: "no name is invented for a customer or an operator").
 *
 * **Never a phone number.** This audit row does not carry one at all — [surface] names which screen
 * *performed* the reveal ([BookingRevealSurface]'s own closed vocabulary for this app's own callers,
 * read back here verbatim whatever the recording caller sent, console callers included), never *what*
 * was revealed (`docs/backlog/26-74-*.md`'s own Out of scope: "it never shows a phone number at all").
 */
public data class PhoneReveal(
    val id: String,
    val occurredAt: String,
    val customerId: String,
    val operatorId: String,
    val surface: String,
    /** `26-203`: [customerId]'s own stored emoji pair, merged onto this row client-side from chat's own
     * person registry — [Contact.emojiCreature]'s own doc comment states the full contract this restates.
     * `ContactPhoneRevealResponse` carries neither field, `null` until
     * [PhoneRevealsReportViewModel][ago.chat.android.analytics.PhoneRevealsReportViewModel]'s own merge
     * runs or when chat has no pair for [customerId], never hash-derived from it. **[operatorId] gets no
     * such pair** — an operator is staff, never a visitor, so the emoji-pair fallback this class's own
     * two fields exist for does not apply to it; it keeps rendering through
     * [ago.chat.android.ui.components.IdentifierText] alone. */
    val emojiCreature: String? = null,
    /** The other half of [emojiCreature]'s own pair. */
    val emojiFood: String? = null,
)
