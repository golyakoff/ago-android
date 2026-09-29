package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingRevealSurface
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.ConfirmPhoneResult
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.core.domain.bookings.PersonBookingsResult
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.persons.PersonConversationsResult
import ago.chat.android.core.domain.persons.PersonsApi
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
import java.time.OffsetDateTime
import javax.inject.Inject

/**
 * `26-269`: the client-detail hub's own state — a pure read + navigation composition
 * (`docs/backlog/26-269-clients-redesign.md` §4: "the detail is a pure read + navigation hub"). Unlike
 * [ContactsViewModel]/[ConfirmedBookingsViewModel], [Contact] is never re-fetched here: there is no
 * "one contact" read on [BookingsApi] (only the tenant-wide list `26-52` already reads), so this class
 * takes the row the caller already had ([open]'s own [Contact] parameter — Клиенты's own row the operator
 * just tapped) and layers the two *new* per-client reads on top of it ([BookingsApi.fetchPersonBookings],
 * [PersonsApi.fetchPersonConversations]).
 *
 * Two independent reads with two different failure postures, not one merged read — the design's own
 * §5/§8#2 distinction, read onto this view model: a failed bookings read fails the whole hub (that is the
 * hub's own main content, and an operator needs to be able to retry it), while a failed or empty
 * conversations read degrades silently to "no dialog to open yet"
 * ([ClientDetailUiState.Loaded.dialogConversationId] `null`) — `adr/0184` decision 4's own "degrades to
 * name not shown yet" precedent, restated here for the dialog link instead of a name.
 */
@HiltViewModel
internal class ClientDetailViewModel
    @Inject
    constructor(
        private val api: BookingsApi,
        private val personsApi: PersonsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ClientDetailUiState>(ClientDetailUiState.Loading)
        val state: StateFlow<ClientDetailUiState> = mutableState.asStateFlow()

        /** The [Contact] [retry] re-opens with, and the id [reveal]/[confirmPhone] act on — `null` only
         * before [open] is ever called. */
        private var currentContact: Contact? = null

        /**
         * Opens (or re-opens) the hub for [contact] — the caller's own `LaunchedEffect(personId)` is what
         * decides when this fires, so switching from one client's own hub straight to another's (unlikely
         * in this app's own navigation, kept honest anyway) starts a clean [ClientDetailUiState.Loading]
         * rather than mixing one client's bookings into another's leftover state.
         */
        fun open(contact: Contact) {
            currentContact = contact
            mutableState.update { ClientDetailUiState.Loading }
            viewModelScope.launch {
                val bookingsResult = withContext(ioDispatcher) { api.fetchPersonBookings(contact.customerId) }
                // `adr/0184` decision 4: the dialog link degrades silently - a failed or empty
                // conversations read is never what fails this hub, only a missing "Открыть диалог".
                val conversationsResult = withContext(ioDispatcher) { personsApi.fetchPersonConversations(contact.customerId) }
                val dialogConversationId =
                    (conversationsResult as? PersonConversationsResult.Loaded)?.conversations?.firstOrNull()?.conversationId

                mutableState.update { current ->
                    when (bookingsResult) {
                        is PersonBookingsResult.Loaded -> {
                            val (upcoming, past) = splitPersonBookings(bookingsResult.bookings, OffsetDateTime.now())
                            ClientDetailUiState.Loaded(
                                contact = contact,
                                upcoming = upcoming,
                                past = past,
                                dialogConversationId = dialogConversationId,
                            )
                        }

                        PersonBookingsResult.NotConfigured -> ClientDetailUiState.NotConfigured
                        is PersonBookingsResult.Failed -> ClientDetailUiState.Failed(bookingsResult.reason)
                    }
                }
            }
        }

        /** The [ClientDetailUiState.Failed] screen's own retry - re-reads for [currentContact], the
         * identical "asking again is the whole of retry" shape every sibling view model on this port
         * already states. A no-op before [open] has ever been called (there is nothing to retry yet). */
        fun retry() {
            currentContact?.let(::open)
        }

        /** `26-269`: which of Предстоящие/Прошедшие the body shows - a pure state update over data
         * already in hand, the identical no-second-network-call shape
         * [ConfirmedBookingsViewModel.onDaySelected]'s own doc comment states for its own date-strip tap.
         * A no-op outside [ClientDetailUiState.Loaded] (there is no segmented control to have been tapped
         * yet). */
        fun onSegmentSelected(segment: ClientDetailSegment) {
            mutableState.update { current ->
                (current as? ClientDetailUiState.Loaded)?.copy(selectedSegment = segment) ?: current
            }
        }

        /**
         * `26-269`: the hub's own «Показать» - the identical audited reveal `26-53` already established,
         * keyed by [BookingRevealSurface.ANDROID_CLIENT_DETAIL] so the audit trail can tell this screen's
         * own reveals apart from Клиенты's ([BookingRevealSurface]'s own doc comment). The identical
         * one-reveal-at-a-time guard every sibling `reveal()` on this port already applies, simplified to
         * a single flag rather than a `Set` - this screen holds exactly one [Contact], never a list of
         * them, so there is only ever one reveal to be mid-flight.
         */
        fun reveal() {
            val loaded = mutableState.value as? ClientDetailUiState.Loaded ?: return
            if (loaded.revealing) return
            val customerId = loaded.contact.customerId
            mutableState.update { loaded.copy(revealing = true, actionError = null) }

            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.revealCustomerPhone(customerId, BookingRevealSurface.ANDROID_CLIENT_DETAIL) }
                mutableState.update { current ->
                    val currentLoaded = current as? ClientDetailUiState.Loaded ?: return@update current
                    when (result) {
                        is RevealPhoneResult.Revealed ->
                            currentLoaded.copy(
                                contact = currentLoaded.contact.copy(phone = result.phone, masked = false),
                                revealing = false,
                                actionError = null,
                            )

                        is RevealPhoneResult.Refused ->
                            currentLoaded.copy(revealing = false, actionError = BookingActionErrorUi.ServerRefusal(result.detail))

                        is RevealPhoneResult.Failed ->
                            currentLoaded.copy(revealing = false, actionError = BookingActionErrorUi.Unavailable(result.reason))
                    }
                }
            }
        }

        /**
         * `23-12`/`26-269`: "I called and it is them" — this app's first real caller of the existing
         * `ConfirmOperatorVerifiedPhone` endpoint. The identical one-in-flight guard [reveal] already
         * applies, over the same single-[Contact] reasoning that method's own doc comment states.
         * [ConfirmPhoneResult.Confirmed.confirmedAt] replaces
         * [Contact.phoneConfirmedByOperatorAt] with the server's own timestamp on success — never a
         * client-side clock reading standing in for it (rule 11), and the warning glyph disappears the
         * moment [Contact.phoneNeedsAttention] reads that new, non-null value.
         */
        fun confirmPhone() {
            val loaded = mutableState.value as? ClientDetailUiState.Loaded ?: return
            if (loaded.confirmingPhone) return
            val customerId = loaded.contact.customerId
            mutableState.update { loaded.copy(confirmingPhone = true, actionError = null) }

            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.confirmOperatorVerifiedPhone(customerId) }
                mutableState.update { current ->
                    val currentLoaded = current as? ClientDetailUiState.Loaded ?: return@update current
                    when (result) {
                        is ConfirmPhoneResult.Confirmed ->
                            currentLoaded.copy(
                                contact = currentLoaded.contact.copy(phoneConfirmedByOperatorAt = result.confirmedAt),
                                confirmingPhone = false,
                                actionError = null,
                            )

                        is ConfirmPhoneResult.Refused ->
                            currentLoaded.copy(confirmingPhone = false, actionError = BookingActionErrorUi.ServerRefusal(result.detail))

                        is ConfirmPhoneResult.Failed ->
                            currentLoaded.copy(confirmingPhone = false, actionError = BookingActionErrorUi.Unavailable(result.reason))
                    }
                }
            }
        }
    }
