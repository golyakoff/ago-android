package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.calendarsetup.ConfiguredCalendar

/**
 * `26-142`: [CalendarSetupViewModel]'s whole state — the identical four-arm shape
 * [MastersUiState]/[ServicesUiState]/[ago.chat.android.schedule.WorkingHoursUiState] already establish,
 * restated rather than shared because [Loaded] carries this screen's own two write surfaces (the embed's
 * allowed origins and the calendar roster) that no sibling state has a field for.
 */
internal sealed interface CalendarSetupUiState {
    data object Loading : CalendarSetupUiState

    /**
     * @param tenantName the shop's own name, shown read-only for context.
     * @param embedSnippet the ready-to-paste `<script>` tag, composed once in the view model from the
     *   tenant's [ago.chat.android.core.domain.calendarsetup.TenantSetup.publicKey] and the widget host —
     *   shown read-only, never editable, the identical "the console is the only place the key appears"
     *   posture the port's own `publicKey` doc comment records.
     * @param originsText the allowed-origins field's *current* text — one origin per line, seeded from the
     *   server list and edited freely. A copy the field owns, not a live view of [calendars] or the server
     *   list: the list underneath stays what the server last said while the operator types, the identical
     *   discipline [ServicesUiState.Loaded.editing] records for its own form.
     * @param calendars every calendar this tenant has, in the server's own order — a card each.
     * @param calendarForm the calendar create/edit form, or `null` when the roster is showing. A create
     *   form when [CalendarForm.isCreating]; an edit form otherwise.
     * @param originsBusy whether the allowed-origins save is in flight — its own flag, separate from
     *   [calendarFormBusy], because the two write surfaces submit independently and one being out on the
     *   network must not disable the other.
     * @param calendarFormBusy whether the open calendar form's own create/update is in flight.
     * @param actionError the one banner a refused or failed write shows, above everything else.
     */
    data class Loaded(
        val tenantName: String,
        val embedSnippet: String,
        val originsText: String,
        val calendars: List<ConfiguredCalendar>,
        val calendarForm: CalendarForm? = null,
        val originsBusy: Boolean = false,
        val calendarFormBusy: Boolean = false,
        val actionError: BookingActionErrorUi? = null,
    ) : CalendarSetupUiState

    data object NotConfigured : CalendarSetupUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : CalendarSetupUiState
}

/**
 * `26-142`: what the calendar create/edit form currently holds — one type for both, the identical
 * "one draft feeds either write" shape [ago.chat.android.core.domain.calendarsetup.CalendarDraft] already
 * establishes on the port side, widened here with the two things a form has that a draft does not: the
 * form's create/edit identity and the create-only timezone.
 *
 * **[calendarId] `null` is the create/edit switch**, exactly as [WorkerForm.workerId] is its own. A `null`
 * id is a calendar being created — the form then shows the timezone dropdown ([timeZoneId] is live). A
 * non-null id is an edit — the timezone is fixed at creation and never sent again
 * ([ago.chat.android.core.domain.calendarsetup.CalendarSetupApi.updateCalendar] omits it), so the form
 * hides the dropdown and [timeZoneId] rides along unused.
 */
internal data class CalendarForm(
    val calendarId: String?,
    val name: String,
    /** IANA zone id — meaningful only while [isCreating]; ignored on an edit, where the calendar's zone
     * is fixed and the update request carries no field for it. */
    val timeZoneId: String,
    val published: Boolean,
) {
    val isCreating: Boolean get() = calendarId == null
}

/** `26-142`: the edit form, opened on one calendar card, prefilled from what the server last said about
 * it. The timezone is carried only so an edit round-trip keeps the value on the form object; it is never
 * shown and never sent. */
internal fun ConfiguredCalendar.toForm(): CalendarForm =
    CalendarForm(
        calendarId = id,
        name = name,
        timeZoneId = timeZone,
        published = published,
    )

/** `26-142`: an empty create form — the console's own defaults for a new calendar (`CalendarSetupPage`'s
 * own `CalendarForm`): Moscow pre-selected so the dropdown always opens on a real choice, and published
 * on by default (a calendar created to be used is created visible). */
internal fun blankCalendarForm(): CalendarForm =
    CalendarForm(
        calendarId = null,
        name = "",
        timeZoneId = DEFAULT_CALENDAR_TIME_ZONE,
        published = true,
    )
