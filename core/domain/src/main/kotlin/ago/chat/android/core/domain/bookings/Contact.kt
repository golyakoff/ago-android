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
 *
 * `26-162`/`adr/0184`: [customerId] now carries the account's opaque `PersonId` — `ContactResponse`
 * dropped its own `CustomerId`/`DisplayName` when the calendar stopped holding a person copy, so this
 * field keeps its name (the identical "rename deferred, the concept is not" precedent `adr/0184`'s own
 * Consequences section states for `Visitor -> Person` itself) while what it actually identifies changes
 * underneath it. [displayName] is no longer read off `ContactResponse` at all — [KtorBookingsApi] always
 * maps it `null`; [ago.chat.android.bookings.ContactsViewModel] fills it back in, when chat's own
 * [ago.chat.android.core.domain.persons.PersonsApi] has a name for this id, the same display-merge
 * `adr/0184` decision 4 describes for `ago-console`. A lookup miss or a reachability failure simply
 * leaves it `null` — [ContactCard] already renders that through [ago.chat.android.ui.components.IdentifierText],
 * never a blank card (`adr/0184`'s own Consequences: "degrades to name not shown yet").
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
    /** `26-203`: the visitor's own stored emoji pair (`26-202`'s additive fields on `Ago.Chat.Api`'s
     * person registry), merged onto this row client-side the identical way [displayName] already is
     * ([ago.chat.android.bookings.ContactsViewModel]'s own merge step) — `ContactResponse` itself carries
     * neither field, since the pair lives on chat's own visitor row, not the calendar's. `null` until the
     * merge runs, or when chat has no pair for this id at all (a row that predates `26-202`, or a lookup
     * miss/failure) — never hash-derived from [customerId]. Both fields are always present together or
     * both `null`, the same "additive pair, never one without the other" contract [emojiFood] restates. */
    val emojiCreature: String? = null,
    /** The other half of [emojiCreature]'s own pair — see its doc comment, which this parameter shares in
     * full. */
    val emojiFood: String? = null,
)
