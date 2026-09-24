package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.ConfiguredService
import ago.chat.android.core.domain.bookings.ServicesResult
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
 * `26-96`: Записи's own «Услуги» state — the tenant's service dictionary, and the two writes this
 * product had no endpoint for until that item: correcting a service, and taking one out of rotation.
 *
 * A sibling of [ContactsViewModel], not a merge into it: this reads a fourth endpoint into a fourth
 * shape. Constructed only for an operator holding `calendar:configure`
 * ([ago.chat.android.shell.AppShellScreen] computes that once and [BookingsRoute] only calls
 * `hiltViewModel()` inside that branch), the identical "does not even ask the server for it" gate
 * [ContactsViewModel]'s own doc comment states.
 *
 * **Both writes go through the same [submit], and there is no delete.** «Снять с продажи» is
 * [submit] with [ServiceDraft.isActive] cleared, not a second code path — the endpoint has replace
 * semantics, so a row toggle and a full edit produce the identical request with one field different,
 * and giving them two methods here would mean two places for that body to be built slightly wrong.
 * There is no delete because the server offers none: four of its read models resolve a past booking's
 * service *name* through the `services` row (`Ago.Calendar.Domain.Service.IsActive`).
 *
 * **Nothing is applied optimistically.** Every write is followed by a fresh [BookingsApi.fetchServices];
 * the authoritative answer is always the next read, the identical discipline
 * `ago-console`'s `CalendarServicesPage` states for the same screen.
 */
@HiltViewModel
internal class ServicesViewModel
    @Inject
    constructor(
        private val api: BookingsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ServicesUiState>(ServicesUiState.Loading)
        val state: StateFlow<ServicesUiState> = mutableState.asStateFlow()

        /** Which services have a write in flight — plain instance state until [applyServicesResult]
         * folds it in, the identical "no lock needed, one view model, one main thread"
         * shape [ContactsViewModel]'s own `revealingCustomerIds` is. */
        private var busyServiceIds: Set<String> = emptySet()

        init {
            refresh()
        }

        /** The initial load, and the retry a [ServicesUiState.Failed] screen offers. Clears the open
         * edit form along with the busy set: the list about to arrive may no longer contain the
         * service being edited, and an edit form over a row that is gone is worse than no form. */
        fun refresh() {
            mutableState.update { ServicesUiState.Loading }
            busyServiceIds = emptySet()
            viewModelScope.launch {
                applyServicesResult(withContext(ioDispatcher) { api.fetchServices() }, actionError = null)
            }
        }

        /** Opens the edit form over one row, prefilled from what the server last said about it. */
        fun edit(service: ConfiguredService) {
            mutableState.update { current ->
                (current as? ServicesUiState.Loaded)?.copy(editing = service.toDraft(), actionError = null) ?: current
            }
        }

        /** Closes the edit form, discarding whatever was typed. No confirmation: nothing has been sent,
         * and the list underneath was never touched ([ServicesUiState.Loaded.editing]'s own remarks). */
        fun cancelEdit() {
            mutableState.update { current ->
                (current as? ServicesUiState.Loaded)?.copy(editing = null) ?: current
            }
        }

        /** Every keystroke in the open form — the whole draft, replaced. A per-field setter API would
         * be six methods for one state object the form already holds entire. */
        fun onDraftChanged(draft: ServiceDraft) {
            mutableState.update { current ->
                (current as? ServicesUiState.Loaded)?.copy(editing = draft) ?: current
            }
        }

        /**
         * `26-96`: the one write — used both by the edit form's save and by the row's own
         * «Снять с продажи»/«Вернуть в продажу» toggle (this class's own doc comment says why they are
         * not two methods).
         *
         * A service already in [busyServiceIds] is a no-op, the identical "one deliberate tap, one
         * server call" discipline [BookingsViewModel.act]'s own doc comment states. A draft with no
         * parseable duration is refused *here* rather than sent, because there is no request to make
         * without a number to put in it — every other rule (a zero, a negative, longer than a working
         * day) belongs to the server and arrives back as its own sentence.
         */
        fun submit(draft: ServiceDraft) {
            if (draft.serviceId in busyServiceIds) return
            val loaded = mutableState.value as? ServicesUiState.Loaded ?: return
            val durationMinutes = draft.durationMinutesOrNull()
            if (durationMinutes == null) {
                mutableState.update { loaded.copy(actionError = BookingActionErrorUi.InvalidDuration) }
                return
            }

            busyServiceIds = busyServiceIds + draft.serviceId
            mutableState.update { loaded.copy(busyServiceIds = busyServiceIds, actionError = null) }

            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        api.updateService(
                            serviceId = draft.serviceId,
                            name = draft.name,
                            durationMinutes = durationMinutes,
                            priceMinorUnits = draft.priceMinorUnits(),
                            // Normalised here too, not only server-side: "от" beside no price is a
                            // combination `Ago.Calendar.Domain.Service` refuses to store, and a form
                            // that can send it would report success on a value that was discarded.
                            priceIsFrom = if (draft.priceMinorUnits() == null) false else draft.priceIsFrom,
                            description = draft.descriptionOrNull(),
                            isActive = draft.isActive,
                        )
                    }
                busyServiceIds = busyServiceIds - draft.serviceId

                when (result) {
                    // The fresh list and the closed form land together, from the server's own answer -
                    // never from what this class assumed it had just written.
                    BookingActionResult.Succeeded ->
                        applyServicesResult(withContext(ioDispatcher) { api.fetchServices() }, actionError = null)

                    is BookingActionResult.Refused -> failEdit(BookingActionErrorUi.ServerRefusal(result.detail))
                    is BookingActionResult.Failed -> failEdit(BookingActionErrorUi.Unavailable(result.reason))
                }
            }
        }

        /** A refused or failed write leaves the form open with what the operator typed still in it -
         * blanking it would throw away the very edit they now have to fix. */
        private fun failEdit(error: BookingActionErrorUi) {
            mutableState.update { current ->
                (current as? ServicesUiState.Loaded)?.copy(busyServiceIds = busyServiceIds, actionError = error) ?: current
            }
        }

        /** Turns one [ServicesResult] into the matching [ServicesUiState] — the identical single-funnel
         * shape [ContactsViewModel]'s own `applyContactsResult` establishes, so no second caller ever
         * builds [ServicesUiState.Loaded] slightly differently. Closes the edit form on every arm: a
         * fresh list is exactly the moment an open form's own subject may have changed underneath it. */
        private fun applyServicesResult(
            result: ServicesResult,
            actionError: BookingActionErrorUi?,
        ) {
            mutableState.update {
                when (result) {
                    is ServicesResult.Loaded ->
                        ServicesUiState.Loaded(
                            services = result.services,
                            editing = null,
                            busyServiceIds = busyServiceIds,
                            actionError = actionError,
                        )

                    ServicesResult.NotConfigured -> ServicesUiState.NotConfigured
                    is ServicesResult.Failed -> ServicesUiState.Failed(result.reason)
                }
            }
        }
    }
