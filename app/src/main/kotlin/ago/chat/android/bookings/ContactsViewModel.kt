package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingRevealSurface
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.core.domain.bookings.ContactsResult
import ago.chat.android.core.domain.bookings.DeleteClientResult
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.persons.PersonsApi
import ago.chat.android.core.domain.persons.PersonsResult
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-52`: Записи's own «Клиенты» state — one plain read, no range and no polling, the identical
 * one-call shape [BookingsViewModel] already establishes for Ожидают, restated here rather than merged
 * into either sibling class because this reads a third, genuinely different endpoint into a third
 * shape ([ago.chat.android.core.domain.bookings.Contact], not a booking).
 *
 * A sibling of [BookingsViewModel]/[ConfirmedBookingsViewModel], not a merge into either: this class is
 * only ever constructed at all for an operator holding `calendar:configure` or `customer:read`
 * ([ago.chat.android.shell.AppShellScreen] computes that once, from the permission set it already has
 * in hand, and [BookingsRoute] only calls `hiltViewModel()` for this class inside that branch) — the
 * identical "does not even ask the server for it" discipline [ConfirmedBookingsViewModel]'s own doc
 * comment states for its own gate.
 *
 * `26-53`: [reveal] is the one write this class now makes — the audited phone unmask.
 * [revealingCustomerIds] is plain instance state, not part of [ContactsUiState] itself until
 * [applyContactsResult] folds it in, the identical "no lock needed" shape
 * [BookingsViewModel]'s own class doc comment states for its own `busyBookingIds`.
 *
 * `26-162`/`adr/0184`: [personsApi] is the display-merge this screen now performs on every load —
 * [api]'s own `ContactResponse` carries a bare `personId` and no name at all any more (the calendar
 * stopped holding a person copy); [mergePersonDetails] is the one place that gap is closed, reading
 * chat's own person registry for the ids this page's own read just came back with. Reachability of that
 * second call is never allowed to fail the whole screen — see [mergePersonDetails]'s own doc comment.
 *
 * `26-203`: the same merge now also carries [ago.chat.android.core.domain.persons.PersonProfile.emojiCreature]/
 * `.emojiFood` onto [Contact] — `26-202`'s own additive pair, read off the identical registry response
 * [mergePersonDetails] already fetches for the name, so [ContactCard]'s own fallback for a nameless
 * customer can render the stored emoji pair rather than a bare id.
 */
@HiltViewModel
internal class ContactsViewModel
    @Inject
    constructor(
        private val api: BookingsApi,
        private val personsApi: PersonsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ContactsUiState>(ContactsUiState.Loading)
        val state: StateFlow<ContactsUiState> = mutableState.asStateFlow()

        /** `26-53`: which customers have a reveal in flight right now — keyed by customer, not row, so
         * every row sharing one customer is disabled together
         * ([ContactsUiState.Loaded]'s own doc comment on [ContactsUiState.Loaded.revealingCustomerIds]). */
        private var revealingCustomerIds: Set<String> = emptySet()

        /** `26-275`: the identical [revealingCustomerIds] shape, for the swipe-to-delete write instead of
         * the phone reveal — see [ContactsUiState.Loaded.deletingClientIds]'s own doc comment. */
        private var deletingClientIds: Set<String> = emptySet()

        init {
            refresh()
        }

        /**
         * `26-269`: every keystroke in the Клиенты search field. A no-op outside [ContactsUiState.Loaded]
         * (there is nothing to filter while the list is still loading, not configured, or failed) — the
         * identical "only the loaded arm carries this field" guard [reveal] already applies to
         * [ContactsUiState.Loaded]'s own `revealingCustomerIds`. Never re-fetches: [filterContacts] runs
         * client-side over the list already in memory (`docs/backlog/26-269-*.md` §1.5.3's own "no backend
         * change" decision), so this is a plain state update, not a coroutine.
         */
        fun onSearchQueryChange(query: String) {
            mutableState.update { current ->
                (current as? ContactsUiState.Loaded)?.copy(searchQuery = query) ?: current
            }
        }

        /** `26-282` (A8): a tap on one of the three filter chips — the identical "plain state update over
         * data already in memory" shape [onSearchQueryChange] states above, restated for [ContactsFilter]
         * instead of a search string. Never re-fetches: [ContactsUiState.Loaded.visibleContacts] applies
         * this alongside the search query, both over the one list already on hand. */
        fun onFilterChange(filter: ContactsFilter) {
            mutableState.update { current ->
                (current as? ContactsUiState.Loaded)?.copy(filter = filter) ?: current
            }
        }

        /** The initial load, and the retry action a [ContactsUiState.Failed] screen offers — the
         * identical "asking again is the whole of retry" shape [BookingsViewModel.refresh]'s own doc
         * comment states. Clears [revealingCustomerIds] the identical reason
         * [BookingsViewModel.refresh]'s own doc comment gives for clearing `busyBookingIds`. */
        fun refresh() {
            mutableState.update { ContactsUiState.Loading }
            revealingCustomerIds = emptySet()
            deletingClientIds = emptySet()
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.fetchContacts() }
                val merged =
                    if (result is ContactsResult.Loaded) {
                        ContactsResult.Loaded(mergePersonDetails(result.contacts))
                    } else {
                        result
                    }
                applyContactsResult(merged, actionError = null)
            }
        }

        /**
         * `26-162`/`adr/0184`: reads chat's own person registry for every distinct id [contacts] carries
         * and copies a real [Contact.displayName] onto the rows that got one back — never the other way
         * round. `26-203` widens the same pass to also copy [Contact.emojiCreature]/[Contact.emojiFood]
         * off the identical [PersonProfile] answer — one request, two facts merged from it, rather than a
         * second round trip for the pair alone. A person id with nobody in the answer, or [personsApi]
         * itself failing or being unreachable, simply leaves that row exactly as [api] already gave it
         * (`displayName` `null`, since `ContactResponse` carries no name of its own any more, and the
         * emoji fields at their own `null` default) — `adr/0184`'s own Consequences: "degrades to name not
         * shown yet", restated here for the pair too, never a failed Клиенты read. [ContactCard] already
         * renders a `null` name/pair through [ago.chat.android.ui.components.VisitorIdentityText], so this
         * merge is the only place that decision needs making.
         */
        private suspend fun mergePersonDetails(contacts: List<Contact>): List<Contact> {
            val personIds = contacts.map { it.customerId }.distinct()
            if (personIds.isEmpty()) return contacts

            val persons =
                when (val result = withContext(ioDispatcher) { personsApi.fetchPersons(personIds) }) {
                    is PersonsResult.Loaded -> result.persons
                    is PersonsResult.Failed -> return contacts
                }

            val personsById = persons.associateBy { it.personId }
            if (personsById.isEmpty()) return contacts

            return contacts.map { contact ->
                val person = personsById[contact.customerId] ?: return@map contact
                contact.copy(
                    displayName = person.displayName ?: contact.displayName,
                    emojiCreature = person.emojiCreature ?: contact.emojiCreature,
                    emojiFood = person.emojiFood ?: contact.emojiFood,
                )
            }
        }

        /**
         * `26-53`: reveals one customer's real phone number — `docs/backlog/26-53-*.md`'s own Scope
         * items 1-4:
         *
         * 1. **One reveal per customer at a time.** A customer already in [revealingCustomerIds] is a
         *    no-op — the identical "one deliberate tap, one server call" discipline
         *    [BookingsViewModel.act]'s own doc comment states for the pending queue's veto actions,
         *    applied here per customer rather than per booking.
         * 2. [BookingRevealSurface.ANDROID_CONTACTS] is passed verbatim — this screen's own, permanent
         *    surface name, never a value computed or passed in by a caller.
         * 3. On success, [applyContactsResult] is *not* used (there is no fresh [ContactsResult] to
         *    fold in — a reveal returns only a phone number, not a whole contact list); instead every
         *    row belonging to [customerId] is replaced in place, unmasked, the identical
         *    "match by customerId, not row" shape `CalendarContactsPage.tsx`'s own `handleReveal`
         *    establishes.
         * 4. On refusal, the masked value is left exactly as it was — nothing here ever guesses at an
         *    unmasked number from a failure.
         */
        fun reveal(customerId: String) {
            if (customerId in revealingCustomerIds) return
            val loaded = mutableState.value as? ContactsUiState.Loaded ?: return
            revealingCustomerIds = revealingCustomerIds + customerId
            // `CalendarQueuePage.tsx`'s own `handleReveal` clears its error the moment a new reveal
            // starts (`setError(null)`, before the request is even sent) - a different moment than
            // `act`'s own veto writes, which only ever clear it via a successful reload. Ported here
            // verbatim rather than merged into that other shape: a stale refusal about a *previous*
            // reveal has no business staying on screen once the operator has clearly moved on to a new
            // attempt, on this same customer or another one.
            mutableState.update { loaded.copy(revealingCustomerIds = revealingCustomerIds, actionError = null) }

            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.revealCustomerPhone(customerId, BookingRevealSurface.ANDROID_CONTACTS) }
                revealingCustomerIds = revealingCustomerIds - customerId

                mutableState.update { current ->
                    val currentLoaded = current as? ContactsUiState.Loaded ?: return@update current
                    when (result) {
                        is RevealPhoneResult.Revealed ->
                            currentLoaded.copy(
                                contacts =
                                    currentLoaded.contacts.map {
                                        if (it.customerId == customerId) it.copy(phone = result.phone, masked = false) else it
                                    },
                                revealingCustomerIds = revealingCustomerIds,
                                actionError = null,
                            )

                        is RevealPhoneResult.Refused ->
                            currentLoaded.copy(
                                revealingCustomerIds = revealingCustomerIds,
                                actionError = BookingActionErrorUi.ServerRefusal(result.detail),
                            )

                        is RevealPhoneResult.Failed ->
                            currentLoaded.copy(
                                revealingCustomerIds = revealingCustomerIds,
                                actionError = BookingActionErrorUi.Unavailable(result.reason),
                            )
                    }
                }
            }
        }

        /** Turns one [ContactsResult] into the matching [ContactsUiState], carrying [revealingCustomerIds]
         * along for the [ContactsUiState.Loaded] arm — the identical single-funnel shape
         * [BookingsViewModel]'s own `applyPendingResult` establishes, so [refresh] never drifts from
         * building [ContactsUiState.Loaded] slightly differently than any future second caller would. */
        private fun applyContactsResult(
            result: ContactsResult,
            actionError: BookingActionErrorUi?,
        ) {
            mutableState.update {
                when (result) {
                    is ContactsResult.Loaded ->
                        ContactsUiState.Loaded(
                            contacts = result.contacts,
                            revealingCustomerIds = revealingCustomerIds,
                            actionError = actionError,
                        )

                    ContactsResult.NotConfigured -> ContactsUiState.NotConfigured
                    is ContactsResult.Failed -> ContactsUiState.Failed(result.reason)
                }
            }
        }

        /**
         * `26-275`/`adr/0189`: the Клиенты row's own swipe-to-delete, confirmed. `docs/backlog/26-275-*.md`
         * §6.1's own two branches:
         *
         * 1. **`204`** ([DeleteClientResult.Deleted]) — the row is simply dropped from [ContactsUiState.Loaded.contacts];
         *    there is nothing left on the server to re-fetch it from.
         * 2. **`409 person_erase.future_bookings`** ([DeleteClientResult.Refused] whose [DeleteClientResult.Refused.code]
         *    matches) — the client is **not** deleted; [ContactsUiState.Loaded.blockedErasureClientId] is set
         *    so [ContactsBody] draws the explain-and-navigate dialog rather than [BookingActionErrorUi] (§4:
         *    the server is the one and only gate, so this branch is read off its own typed `code`, never
         *    guessed from [DeleteClientResult.Refused.detail]'s own sentence). Every other refusal code
         *    (`person_erase.forbidden`/`person_erase.not_found`, neither reachable in practice once the
         *    swipe is itself gated on `customer:erase` and the row came from this tenant's own list) falls
         *    back to the ordinary [BookingActionErrorUi.ServerRefusal] banner every other write on this
         *    screen already shows.
         *
         * The identical one-in-flight-per-customer guard [reveal] already applies, over [deletingClientIds]
         * rather than [revealingCustomerIds] — the two writes are independent, so a client mid-delete does
         * not block a *different* client's own reveal, or vice versa.
         */
        fun deleteClient(customerId: String) {
            if (customerId in deletingClientIds) return
            val loaded = mutableState.value as? ContactsUiState.Loaded ?: return
            deletingClientIds = deletingClientIds + customerId
            mutableState.update { loaded.copy(deletingClientIds = deletingClientIds, actionError = null) }

            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.deleteClient(customerId) }
                deletingClientIds = deletingClientIds - customerId

                mutableState.update { current ->
                    val currentLoaded = current as? ContactsUiState.Loaded ?: return@update current
                    when (result) {
                        DeleteClientResult.Deleted ->
                            currentLoaded.copy(
                                contacts = currentLoaded.contacts.filterNot { it.customerId == customerId },
                                deletingClientIds = deletingClientIds,
                                actionError = null,
                            )

                        is DeleteClientResult.Refused ->
                            if (result.code == PERSON_ERASE_FUTURE_BOOKINGS_CODE) {
                                currentLoaded.copy(
                                    deletingClientIds = deletingClientIds,
                                    blockedErasureClientId = customerId,
                                    actionError = null,
                                )
                            } else {
                                currentLoaded.copy(
                                    deletingClientIds = deletingClientIds,
                                    actionError = BookingActionErrorUi.ServerRefusal(result.detail),
                                )
                            }

                        is DeleteClientResult.Failed ->
                            currentLoaded.copy(
                                deletingClientIds = deletingClientIds,
                                actionError = BookingActionErrorUi.Unavailable(result.reason),
                            )
                    }
                }
            }
        }

        /** Dismisses the blocked-delete explanation [ContactsBody] drew for
         * [ContactsUiState.Loaded.blockedErasureClientId] — both the plain "never mind" close and the
         * «Перейти к записям» navigation itself dismiss it (the navigation's own doc comment on
         * [ContactsUiState.Loaded.blockedErasureClientId] states why no separate id needs to survive the
         * dialog closing: [ContactsBody] captures the id into its own `selectedClientId` before calling
         * this). A no-op outside [ContactsUiState.Loaded] or when nothing is blocked. */
        fun dismissBlockedErasure() {
            mutableState.update { current ->
                (current as? ContactsUiState.Loaded)?.copy(blockedErasureClientId = null) ?: current
            }
        }
    }

/** `26-275`/`adr/0189`: `ErasePersonErrors.FutureBookingsExist`'s own stable `type`
 * (`Ago.Calendar.Application/UseCases/ErasePerson/ErasePersonErrors.cs`) — the one
 * [DeleteClientResult.Refused.code] this screen branches on; every other code shows [DeleteClientResult.Refused.detail]
 * verbatim instead (`ContactsViewModel.deleteClient`'s own doc comment). */
private const val PERSON_ERASE_FUTURE_BOOKINGS_CODE = "person_erase.future_bookings"
