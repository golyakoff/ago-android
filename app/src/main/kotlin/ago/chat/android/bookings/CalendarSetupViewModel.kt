package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.calendarsetup.CalendarDraft
import ago.chat.android.core.domain.calendarsetup.CalendarSetupApi
import ago.chat.android.core.domain.calendarsetup.ConfiguredCalendar
import ago.chat.android.core.domain.calendarsetup.TenantSetupResult
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
 * `26-142`: the Настройка / Календари screen — the calendar roster a single Setup screen owns, over
 * [CalendarSetupApi]. A sibling of [MastersViewModel]/[ServicesViewModel], not a merge into either: it
 * reads and writes a different noun through a different port, gated server-side on `calendar:configure`
 * alone. Constructed only for an operator holding that permission
 * ([ago.chat.android.shell.AppShellScreen] computes the gate once and [BookingsRoute] calls
 * `hiltViewModel()` only inside that branch), the identical "does not even ask the server for it" gate
 * [MastersViewModel]'s own doc comment states.
 *
 * **The calendar create/update the form submits ([submitCalendar]) re-reads through
 * [CalendarSetupApi.fetchSetup] on success** rather than patching state from an echo, the identical
 * "the authoritative answer is always the next read" discipline the sibling view models follow.
 *
 * `26-158`: the allowed-origins save and the embed snippet this screen used to carry are gone — they were
 * a chat/channel setting wrongly shown here, now owned by the «Установка виджета» screen
 * ([ago.chat.android.channels.InstallWidgetViewModel], `26-159`). With them went this view model's only
 * reason to hold [ago.chat.android.session.OidcConfig] (it composed the `<script>` tag), so that
 * dependency is dropped too.
 */
@HiltViewModel
internal class CalendarSetupViewModel
    @Inject
    constructor(
        private val api: CalendarSetupApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<CalendarSetupUiState>(CalendarSetupUiState.Loading)
        val state: StateFlow<CalendarSetupUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, and the retry a [CalendarSetupUiState.Failed] screen offers. Clears any open
         * form: the roster about to arrive may no longer contain the calendar being edited. */
        fun refresh() {
            mutableState.update { CalendarSetupUiState.Loading }
            viewModelScope.launch {
                applyResult(withContext(ioDispatcher) { api.fetchSetup() }, actionError = null)
            }
        }

        /** Opens an empty create form (Moscow pre-selected, published on) over the roster. */
        fun startAddCalendar() {
            mutableState.update { current ->
                (current as? CalendarSetupUiState.Loaded)?.copy(calendarForm = blankCalendarForm(), actionError = null) ?: current
            }
        }

        /** Opens the edit form over one card, prefilled from what the server last said about it — name and
         * published only, the timezone shown nowhere because it cannot be changed. */
        fun editCalendar(calendar: ConfiguredCalendar) {
            mutableState.update { current ->
                (current as? CalendarSetupUiState.Loaded)?.copy(calendarForm = calendar.toForm(), actionError = null) ?: current
            }
        }

        /** Closes the form, discarding whatever was typed. No confirmation: nothing was sent, and the
         * roster underneath was never touched. */
        fun cancelCalendarEdit() {
            mutableState.update { current ->
                (current as? CalendarSetupUiState.Loaded)?.copy(calendarForm = null) ?: current
            }
        }

        /** Every keystroke and toggle in the open calendar form — the whole form, replaced. */
        fun onCalendarFormChanged(form: CalendarForm) {
            mutableState.update { current ->
                (current as? CalendarSetupUiState.Loaded)?.copy(calendarForm = form) ?: current
            }
        }

        /**
         * `26-142`: the calendar form's own write — create when [CalendarForm.isCreating], otherwise
         * update. The timezone travels only on the create ([CalendarSetupApi.createCalendar]); the update
         * carries name and published alone, because a calendar's zone is fixed at creation. A blank name
         * belongs to the server's own refusal, not a client-side rule.
         */
        fun submitCalendar(form: CalendarForm) {
            val loaded = mutableState.value as? CalendarSetupUiState.Loaded ?: return
            if (loaded.calendarFormBusy) return

            val draft = CalendarDraft(name = form.name.trim(), published = form.published)

            mutableState.update { loaded.copy(calendarFormBusy = true, actionError = null) }
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        if (form.isCreating) {
                            api.createCalendar(draft, timeZone = form.timeZoneId)
                        } else {
                            api.updateCalendar(form.calendarId!!, draft)
                        }
                    }
                when (result) {
                    BookingActionResult.Succeeded ->
                        applyResult(withContext(ioDispatcher) { api.fetchSetup() }, actionError = null)

                    is BookingActionResult.Refused -> failForm(BookingActionErrorUi.ServerRefusal(result.detail))
                    is BookingActionResult.Failed -> failForm(BookingActionErrorUi.Unavailable(result.reason))
                }
            }
        }

        /** A refused or failed calendar write leaves the form open with what the operator typed still in
         * it — blanking it would throw away the very edit they now have to fix. */
        private fun failForm(error: BookingActionErrorUi) {
            mutableState.update { current ->
                (current as? CalendarSetupUiState.Loaded)?.copy(calendarFormBusy = false, actionError = error) ?: current
            }
        }

        /** Turns one [TenantSetupResult] into the matching [CalendarSetupUiState] — the single funnel that
         * keeps [refresh] and the write path from building [CalendarSetupUiState.Loaded] slightly
         * differently. Closes any open form on every Loaded arm: a fresh read is exactly the moment the
         * server's own answer becomes the truth again. */
        private fun applyResult(
            result: TenantSetupResult,
            actionError: BookingActionErrorUi?,
        ) {
            mutableState.update {
                when (result) {
                    is TenantSetupResult.Loaded ->
                        CalendarSetupUiState.Loaded(
                            calendars = result.setup.calendars,
                            calendarForm = null,
                            calendarFormBusy = false,
                            actionError = actionError,
                        )

                    TenantSetupResult.NotConfigured -> CalendarSetupUiState.NotConfigured
                    is TenantSetupResult.Failed -> CalendarSetupUiState.Failed(result.reason)
                }
            }
        }
    }
