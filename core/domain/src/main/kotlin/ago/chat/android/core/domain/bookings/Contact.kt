package ago.chat.android.core.domain.bookings

/**
 * `26-52`: one of this tenant's own customers — reduced from `Ago.Calendar.Contracts.ContactResponse`
 * to the fields Клиенты actually renders, the same "no field with nothing that reads it" discipline
 * [PendingBooking]'s own doc comment states. `notes`/`firstSeenAt`/`lastSeenAt`/
 * `duplicatePhoneCustomerIds` are all on the wire too (`ago-console`'s own `Contact`,
 * `calendarApi.ts:367`) and are left off this type on purpose: notes and the two timestamps have no
 * card slot this item's own Scope draws, and `duplicatePhoneCustomerIds` only feeds Объединение
 * клиентов — gated on `customer:edit`, a separate future item (`docs/backlog/26-52-*.md`'s own Out of
 * scope).
 *
 * `phone`/`masked` are both carried, but this item draws no reveal control at all — [masked] is what
 * tells the two possible shapes of [phone] apart; the card renders [phone] exactly as the server sent
 * it either way ("Masked is masked", `docs/backlog/26-52-*.md`'s own Scope item 4). Показать, the
 * audited reveal that would ever turn a masked [phone] into a real one, is `26-53`.
 */
public data class Contact(
    val customerId: String,
    val phone: String,
    val masked: Boolean,
    val displayName: String?,
    val noShowCount: Int,
    /** When this number was proven reachable by an SMS code, or `null` if it never has been — kept a
     * fact entirely separate from [phoneConfirmedByOperatorAt], never merged into one "verified" badge
     * (this class's own doc comment; the split itself is `calendarApi.ts`'s own `Contact.phoneVerifiedAt`
     * remarks, carried over verbatim: "proven reachable by SMS" is a different fact from "an operator
     * called and it is them"). */
    val phoneVerifiedAt: String?,
    /** When an operator recorded "I called and it is them", or `null` if nobody has —
     * `docs/backlog/26-52-*.md`'s own Scope item 3: this fact and [phoneVerifiedAt] stay two facts, no
     * matter how tempting collapsing them is on a small screen. */
    val phoneConfirmedByOperatorAt: String?,
)
