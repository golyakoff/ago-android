package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.Contact

/**
 * `26-52`: [ContactsViewModel]'s whole state — the identical four-arm shape
 * [ago.chat.android.bookings.BookingsUiState]/[ConfirmedBookingsUiState] already establish, restated
 * rather than shared because [Loaded] carries [Contact], a flat list with no day/range concept behind
 * it (this segment's own read has no range parameter at all, unlike Утверждены's).
 */
internal sealed interface ContactsUiState {
    data object Loading : ContactsUiState

    /**
     * `26-53`: [revealingCustomerIds]/[actionError] are this screen's own phone-reveal state, both
     * defaulted so every existing call site keeps compiling unchanged. [revealingCustomerIds] is a
     * *set*, not a single nullable id — [ago.chat.android.bookings.BookingsUiState.Loaded]'s own doc
     * comment on `busyBookingIds` states the identical reasoning for the pending queue's own three veto
     * actions: a second customer's own reveal must stay tappable while a first is still out on the
     * network. Keyed by *customer*, not by row: `docs/backlog/26-53-*.md`'s own "What is actually true
     * today" section cites `CalendarQueuePage.tsx`'s own `revealingCustomerId`, tracked by customer id
     * so that every row sharing one customer (a customer can have several pending bookings) is disabled
     * together, never independently.
     *
     * `26-269`: [searchQuery] is the operator's own live filter text — held here rather than as a
     * separate view-model field, the same reasoning [revealingCustomerIds] states: it is state about
     * *this loaded list*, meaningless in every other arm of this `sealed interface`, so it lives beside
     * [contacts] instead of forcing every other state to carry a field it never uses. [contacts] itself
     * stays the *full*, unfiltered list the server answered with — never mutated by a search — so
     * clearing the query always restores every row with no second read; [visibleContacts] is the one
     * place [searchQuery] is ever applied, computed rather than stored so it can never drift out of sync
     * with either field changing independently. Defaulted to `""` so every pre-existing call site (none
     * of which knows about search) keeps compiling unchanged.
     */
    data class Loaded(
        val contacts: List<Contact>,
        val searchQuery: String = "",
        val revealingCustomerIds: Set<String> = emptySet(),
        val actionError: BookingActionErrorUi? = null,
        /** `26-275`: which client's own swipe-to-delete is on the network right now — a *set*, the
         * identical [revealingCustomerIds] reasoning restated for the erase write instead of the phone
         * reveal: a second row's own delete must stay tappable while a first is still in flight.
         * Defaulted so every existing call site keeps compiling unchanged. */
        val deletingClientIds: Set<String> = emptySet(),
        /** `26-275`: non-null exactly while the blocked-delete explanation is on screen for this customer
         * id — the server refused with `person_erase.future_bookings` (§4/§6.1's own two-branch guard,
         * server-authoritative). `ContactsBody` reads this to draw the explain-and-navigate dialog; its
         * own «Перейти к записям» reopens [ClientDetailSheet] for the same id, which already defaults to
         * the Предстоящие segment ([ClientDetailUiState.Loaded.selectedSegment]'s own default) — no new
         * navigation state is needed to land the operator on the right list. */
        val blockedErasureClientId: String? = null,
        /** `26-282` (A8): the filter chip row's own selection — [ContactsFilter.All] by default, the
         * identical "the widest view is the default" posture [ContactsFilter]'s own doc comment states.
         * Held beside [searchQuery] for the identical reason that field is held here rather than in the
         * view model directly: state about *this loaded list*, meaningless in every other arm of
         * [ContactsUiState]. */
        val filter: ContactsFilter = ContactsFilter.All,
    ) : ContactsUiState {
        /** `26-269`/`26-282`: [contacts] filtered by [searchQuery] **and** [filter] — see [filterContacts]
         * for the search match rule and [ContactsFilter.matches] for the chip rule. A `get()`-only
         * property, not a constructor parameter, so it takes no part in this data class's generated
         * `equals`/`hashCode`/`copy` — every existing test that builds a [Loaded] by hand and compares it
         * keeps asserting on [contacts] exactly as before. */
        val visibleContacts: List<Contact>
            get() = filterContacts(contacts, searchQuery).filter(filter::matches)
    }

    data object NotConfigured : ContactsUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : ContactsUiState
}

/**
 * `26-269`: the Клиенты search's own match rule — a blank/whitespace-only [query] (the field's own
 * default) returns [contacts] untouched, otherwise a row survives when [query] is a case-insensitive
 * substring of either [Contact.displayName] **or** [Contact.phone] (`docs/backlog/26-269-*.md` §1.5.3:
 * "client-side filtering... by name and by phone"). A `null` [Contact.displayName] (no chat-side name
 * merged yet, [ContactsViewModel.mergePersonDetails]'s own doc comment) never matches by name — there is
 * no name to compare against — but the row still matches by phone, so a nameless customer stays findable.
 * [Contact.phone] is matched exactly as the server/reveal left it — masked or not — never re-derived or
 * digit-normalised here: this is a filter over what the operator can already read on the row, not a new
 * fact about the phone number.
 *
 * A plain top-level function, not a method on [ContactsUiState.Loaded] itself, so a unit test can assert
 * the match rule directly against a bare [List] of [Contact] with no [ContactsUiState] wrapper to build
 * first — the identical "plain function over plain data" reasoning `BookingsTab.kt`'s own
 * `visibleBookingsSegments` states for its own list-filtering logic.
 */
internal fun filterContacts(
    contacts: List<Contact>,
    query: String,
): List<Contact> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return contacts
    return contacts.filter { contact ->
        contact.displayName?.contains(trimmed, ignoreCase = true) == true ||
            contact.phone.contains(trimmed, ignoreCase = true)
    }
}

/**
 * `26-269`: the row's own warning-glyph rule — `true` iff [Contact.phoneVerifiedAt] **and**
 * [Contact.phoneConfirmedByOperatorAt] are both `null` (`docs/backlog/26-269-*.md` §3.3: "the single
 * actionable state... When it is verified *either* way, show no icon"). A plain `Boolean` property on
 * [Contact] rather than logic inlined in [ContactCard]'s own phone-line `@Composable` body (`26-268`
 * follow-up: originally `PhoneStatusAndNoShowRow`'s body, before that glyph moved inline with the phone
 * number itself), so a plain JVM unit test can assert the rule directly against a bare [Contact] — the
 * identical "pull the rule out of the composable so it is testable without Compose" reasoning
 * [filterContacts] states for the search match rule above. Never renamed to "verified"/"confirmed"
 * singular: it answers "does this row need the glyph", not "is the phone verified" — that remains two
 * separate facts on [Contact] itself.
 */
internal val Contact.phoneNeedsAttention: Boolean
    get() = phoneVerifiedAt == null && phoneConfirmedByOperatorAt == null

/**
 * `26-282` (A8): Клиенты's own three-way filter chip row — «Все» / «С предстоящей записью» / «Без
 * записей», each a plain predicate over [Contact.upcomingBookingCount] rather than a second server read:
 * the count already rides on every row ([Contact.upcomingBookingCount]'s own doc comment), so narrowing
 * the list to it is exactly the same client-side, in-memory operation [filterContacts] already performs
 * for the search box, one `filter` call further. [All] matches every contact and is the default —
 * the identical "the widest view first" posture every other list filter in this app opens on.
 */
internal enum class ContactsFilter {
    All,
    HasUpcoming,
    NoUpcoming,
    ;

    fun matches(contact: Contact): Boolean =
        when (this) {
            All -> true
            HasUpcoming -> contact.upcomingBookingCount > 0
            NoUpcoming -> contact.upcomingBookingCount == 0
        }
}
