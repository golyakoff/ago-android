package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.calendarsetup.CalendarDraft
import ago.chat.android.core.domain.calendarsetup.CalendarSetupApi
import ago.chat.android.core.domain.calendarsetup.ConfiguredCalendar
import ago.chat.android.core.domain.calendarsetup.TenantSetup
import ago.chat.android.core.domain.calendarsetup.TenantSetupResult
import ago.chat.android.di.IoDispatcher
import ago.chat.android.session.OidcConfig
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
 * `26-142`: the Настройка / Календари screen — the tenant-configuration writes a single Setup screen
 * owns (the embed's allowed origins, and the calendar roster), over [CalendarSetupApi]. A sibling of
 * [MastersViewModel]/[ServicesViewModel], not a merge into either: it reads and writes a different noun
 * through a different port, gated server-side on `calendar:configure` alone. Constructed only for an
 * operator holding that permission ([ago.chat.android.shell.AppShellScreen] computes the gate once and
 * [BookingsRoute] calls `hiltViewModel()` only inside that branch), the identical "does not even ask the
 * server for it" gate [MastersViewModel]'s own doc comment states.
 *
 * **Two independent write surfaces, one discipline.** The allowed-origins save ([saveOrigins]) and the
 * calendar create/update the form submits ([submitCalendar]) carry separate busy flags
 * ([CalendarSetupUiState.Loaded.originsBusy] versus [CalendarSetupUiState.Loaded.calendarFormBusy]) so one
 * being out on the network never disables the other. Every one of them re-reads through
 * [CalendarSetupApi.fetchSetup] on success rather than patching state from an echo, the identical
 * "the authoritative answer is always the next read" discipline the sibling view models follow.
 *
 * **[OidcConfig] is injected only to compose the embed snippet.** The `<script>` tag is built here, once,
 * from the tenant's `publicKey` and the widget host ([OidcConfig.apiBaseUrl]) — the identical shape
 * `ago-console`'s own `InstallSnippetPage` builds it, `${apiBaseUrl}/widget/widget.js` with the key as
 * `data-site`. It is composed in the view model rather than the composable so the [CalendarSetupBody] stays
 * a pure renderer of a ready string, with no config dependency of its own.
 */
@HiltViewModel
internal class CalendarSetupViewModel
    @Inject
    constructor(
        private val api: CalendarSetupApi,
        config: OidcConfig,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val widgetHost: String = config.apiBaseUrl.trimEnd('/')

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

        /** Every keystroke in the allowed-origins field — the whole text, replaced. Held on the Loaded
         * state rather than in the composable so a re-read after a save can re-seed it from the server's
         * own answer. */
        fun onOriginsTextChanged(text: String) {
            mutableState.update { current ->
                (current as? CalendarSetupUiState.Loaded)?.copy(originsText = text) ?: current
            }
        }

        /**
         * `26-142`: «Сохранить источники» — replaces the whole allowed-origins list
         * ([CalendarSetupApi.saveAllowedOrigins]'s own replace semantics). The text is split into one origin
         * per line, trimmed, blanks dropped — the identical parse `ago-console`'s own `OriginsForm` submit
         * performs. Every rule about what a valid origin is belongs to the server and comes back as a
         * [BookingActionResult.Refused] in its own words; this app invents no origin validation of its own.
         */
        fun saveOrigins() {
            val loaded = mutableState.value as? CalendarSetupUiState.Loaded ?: return
            if (loaded.originsBusy) return

            val origins =
                loaded.originsText
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

            mutableState.update { loaded.copy(originsBusy = true, actionError = null) }
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.saveAllowedOrigins(origins) }) {
                    BookingActionResult.Succeeded ->
                        applyResult(withContext(ioDispatcher) { api.fetchSetup() }, actionError = null)

                    is BookingActionResult.Refused -> failOrigins(BookingActionErrorUi.ServerRefusal(result.detail))
                    is BookingActionResult.Failed -> failOrigins(BookingActionErrorUi.Unavailable(result.reason))
                }
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

        /** A refused or failed origins save keeps the typed text and shows the error above it. */
        private fun failOrigins(error: BookingActionErrorUi) {
            mutableState.update { current ->
                (current as? CalendarSetupUiState.Loaded)?.copy(originsBusy = false, actionError = error) ?: current
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
         * keeps [refresh] and the write paths from building [CalendarSetupUiState.Loaded] slightly
         * differently. Re-seeds the origins text and closes any open form on every Loaded arm: a fresh read
         * is exactly the moment the server's own answer becomes the truth again. */
        private fun applyResult(
            result: TenantSetupResult,
            actionError: BookingActionErrorUi?,
        ) {
            mutableState.update {
                when (result) {
                    is TenantSetupResult.Loaded ->
                        CalendarSetupUiState.Loaded(
                            tenantName = result.setup.tenantName,
                            embedSnippet = embedSnippet(result.setup),
                            originsText = result.setup.allowedOrigins.joinToString("\n"),
                            calendars = result.setup.calendars,
                            calendarForm = null,
                            originsBusy = false,
                            calendarFormBusy = false,
                            actionError = actionError,
                        )

                    TenantSetupResult.NotConfigured -> CalendarSetupUiState.NotConfigured
                    is TenantSetupResult.Failed -> CalendarSetupUiState.Failed(result.reason)
                }
            }
        }

        private fun embedSnippet(setup: TenantSetup): String =
            "<script src=\"$widgetHost/widget/widget.js\" data-site=\"${setup.publicKey}\" async></script>"
    }
