package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingRevealSurface
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.ContactsResult
import ago.chat.android.core.domain.bookings.RevealPhoneResult
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
 */
@HiltViewModel
internal class ContactsViewModel
    @Inject
    constructor(
        private val api: BookingsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ContactsUiState>(ContactsUiState.Loading)
        val state: StateFlow<ContactsUiState> = mutableState.asStateFlow()

        /** `26-53`: which customers have a reveal in flight right now — keyed by customer, not row, so
         * every row sharing one customer is disabled together
         * ([ContactsUiState.Loaded]'s own doc comment on [ContactsUiState.Loaded.revealingCustomerIds]). */
        private var revealingCustomerIds: Set<String> = emptySet()

        init {
            refresh()
        }

        /** The initial load, and the retry action a [ContactsUiState.Failed] screen offers — the
         * identical "asking again is the whole of retry" shape [BookingsViewModel.refresh]'s own doc
         * comment states. Clears [revealingCustomerIds] the identical reason
         * [BookingsViewModel.refresh]'s own doc comment gives for clearing `busyBookingIds`. */
        fun refresh() {
            mutableState.update { ContactsUiState.Loading }
            revealingCustomerIds = emptySet()
            viewModelScope.launch {
                applyContactsResult(withContext(ioDispatcher) { api.fetchContacts() }, actionError = null)
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
    }
