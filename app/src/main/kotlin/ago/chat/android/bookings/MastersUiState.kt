package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfiguredService
import ago.chat.android.core.domain.workers.Worker
import ago.chat.android.core.domain.workers.WorkerCalendar

/**
 * `26-140`: [MastersViewModel]'s whole state — the identical four-arm shape
 * [ServicesUiState]/[ContactsUiState]/[ago.chat.android.schedule.WorkingHoursUiState] already establish,
 * restated rather than shared because [Loaded] carries the worker roster and the two lists the add/edit
 * form is built from ([ago.chat.android.core.domain.workers.WorkersResult.Loaded]'s own three
 * collections).
 */
internal sealed interface MastersUiState {
    data object Loading : MastersUiState

    /**
     * @param workers the roster, active and inactive alike — an inactive worker is a row to render
     *   marked, never one to drop, the identical "the server keeps returning it on purpose" reasoning
     *   [ServicesUiState.Loaded]'s own withdrawn-service handling records.
     * @param calendars the calendars the create form's picker offers. When empty, Add is disabled with a
     *   stated note ([ago.chat.android.core.domain.workers.WorkerCalendar]'s own doc comment): there is
     *   no calendar to put a worker on.
     * @param services the service dictionary the form's checkboxes are built from, and the source a card
     *   resolves its [Worker.serviceIds] into names through.
     * @param editing the add/edit form, or `null` when the list is showing. A *copy* the form edits
     *   freely, not a reference into [workers] — the identical "the list stays what the server last said
     *   while the operator types" discipline [ServicesUiState.Loaded.editing]'s own doc comment records.
     * @param busyWorkerIds which workers have a *list-row* write in flight (the active toggle, the
     *   delete) — a set, not a single id, so a second row stays tappable while a first is still out on
     *   the network ([ServicesUiState.Loaded.busyServiceIds]'s own reasoning).
     * @param formBusy whether the create/update the form itself submits is in flight — a single flag
     *   rather than an id, because a creating form has no worker id yet and only ever one form is open.
     */
    data class Loaded(
        val workers: List<Worker>,
        val calendars: List<WorkerCalendar>,
        val services: List<ConfiguredService>,
        val editing: WorkerForm? = null,
        val busyWorkerIds: Set<String> = emptySet(),
        val formBusy: Boolean = false,
        val actionError: BookingActionErrorUi? = null,
    ) : MastersUiState

    data object NotConfigured : MastersUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : MastersUiState
}

/**
 * `26-140`: what the add/edit form currently holds — the union of the create and the edit form, one type
 * for both, the identical "one draft feeds either write" shape
 * [ago.chat.android.core.domain.workers.WorkerDraft] already establishes on the port side.
 *
 * **[workerId] `null` is the create/edit switch.** A `null` id is a worker being created — the form then
 * shows the calendar picker (a worker's calendar is fixed at creation) and hides the Активен toggle (a
 * new worker is always active). A non-null id is an edit — the calendar is fixed and shown read-only,
 * and the Активен toggle appears. This mirrors exactly which fields each server request carries
 * ([ago.chat.android.core.domain.workers.WorkersApi.createWorker]/[ago.chat.android.core.domain.workers.WorkersApi.updateWorker]).
 *
 * The three name parts and the display name are carried as the strings the text fields hold; blank
 * middle and display names normalise to `null` at submit ([WorkerDraft]'s own "null, never an empty
 * string" rule), never here, so a half-typed field is a real state this type can hold.
 */
internal data class WorkerForm(
    val workerId: String?,
    val lastName: String,
    val firstName: String,
    val middleName: String,
    val displayName: String,
    /** The calendar the new worker joins. On an edit this is the worker's fixed calendar, carried only so
     * the picker can show it selected and read-only — the update request never sends it. `null` only
     * before any calendar is chosen, which the submit path refuses locally (there is no create request to
     * make without a calendar to put the worker on). */
    val calendarId: String?,
    val isActive: Boolean,
    /** The worker's complete desired set of service dictionary ids — replace semantics on both writes. */
    val serviceIds: Set<String>,
) {
    val isCreating: Boolean get() = workerId == null
}

/** `26-140`: the edit form, opened on one row, prefilled from what the server last said about it. A copy
 * rather than a reference — see [MastersUiState.Loaded.editing]. */
internal fun Worker.toForm(): WorkerForm =
    WorkerForm(
        workerId = workerId,
        lastName = lastName,
        firstName = firstName,
        middleName = middleName.orEmpty(),
        displayName = displayName,
        calendarId = calendarId,
        isActive = isActive,
        serviceIds = serviceIds.toSet(),
    )

/** `26-140`: an empty create form, with the first available calendar pre-selected so the picker always
 * opens on a real choice and the create path never has to refuse a missing calendar in practice. */
internal fun blankWorkerForm(defaultCalendarId: String?): WorkerForm =
    WorkerForm(
        workerId = null,
        lastName = "",
        firstName = "",
        middleName = "",
        displayName = "",
        calendarId = defaultCalendarId,
        isActive = true,
        serviceIds = emptySet(),
    )
