package ago.chat.android.core.domain.workers

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfiguredService

/**
 * `26-139`: the port the Masters (Мастера) screen reads and writes the worker dictionary through —
 * declared here and implemented in `:core:network` ([ago.chat.android.core.network.workers.KtorWorkersApi]),
 * the identical split [ago.chat.android.core.domain.bookings.BookingsApi] and
 * [ago.chat.android.core.domain.schedule.WorkingHoursApi] already establish for `Ago.Calendar.Api`'s
 * own console surface. The dependency rule is what puts it here: a view model holding an `HttpClient`
 * directly could not be tested without one, and every HTTP-shaped decision (which status means what,
 * which base URL to call, how the two reads a card needs are stitched together) belongs on the far
 * side of this interface, in the adapter.
 *
 * **Its own port, not more methods on `BookingsApi`.** These are configuration writes on a different
 * noun, gated server-side on `calendar:configure` alone — the identical reasoning
 * [ago.chat.android.core.domain.schedule.WorkingHoursApi]'s own doc comment gives for being its own
 * port rather than a fourth method on the bookings interface. What *is* reused across the boundary,
 * deliberately rather than copied: [BookingsQueueFailure] (the two-way "is it me, or is it broken"
 * classification every calendar read already reduces to), [BookingActionResult] (the
 * succeeded/refused/failed shape every calendar write already reduces to), and [ConfiguredService]
 * (one row of the service dictionary, which the Masters screen's checkboxes render unchanged).
 */
public interface WorkersApi {
    /**
     * The one read the Masters screen opens on — `GET /api/v1/console/workers` for the roster, stitched
     * to `GET /api/v1/console/configuration` for the two things the roster response cannot carry: the
     * calendar each worker belongs to, and the calendars and services the add/edit form's picker and
     * checkboxes are built from.
     *
     * **Why one method makes both calls.** `Ago.Calendar.Contracts.WorkerResponse` carries no
     * `CalendarId` at all — a worker's calendar membership lives only in the configuration document's
     * `calendars[].workerIds`, the same place `ago-console`'s own `CalendarWorkersPage` reads it from
     * (it calls `listWorkers` alongside `getConfiguration`). Rather than expose that stitching to the
     * view model, the adapter does it here and hands back fully-formed [Worker]s. The screen re-reads
     * through this one method after every write (no optimistic update), so the calendar picker and the
     * service checkboxes always come back current in the same round trip as the roster.
     */
    public suspend fun fetchWorkers(): WorkersResult

    /**
     * `GET /api/v1/console/workers/{workerId}` — one worker's own row, for a caller that wants to
     * re-read a single record rather than the whole roster.
     *
     * [Worker.calendarId] comes back `null` here, and that is not a gap: a single `WorkerResponse`
     * carries no calendar membership (see [fetchWorkers] for where that fact lives), and this read makes
     * exactly one request rather than a second one to `configuration` for a field the roster read
     * already resolves. The Masters screen prefills its edit form from the [fetchWorkers] roster, whose
     * [Worker]s do carry the calendar; this single read exists for the name/active/services fields the
     * `WorkerResponse` does carry in full.
     */
    public suspend fun fetchWorker(workerId: String): WorkerDetailResult

    /**
     * `POST /api/v1/console/workers` (`Ago.Calendar.Contracts.CreateWorkerRequest`) — a new worker on
     * the calendar [WorkerDraft.calendarId] names. [WorkerDraft.isActive] is **not** sent: the server
     * has no `IsActive` on its create request because a freshly created worker is always active.
     *
     * Answers with [BookingActionResult], reused rather than restated: a `2xx` (the server answers
     * `201 Created`), a server-authored refusal shown verbatim (the worker-quota ceiling, a domain
     * validation an operator can act on), or a failure. The caller observes the new row by re-reading
     * through [fetchWorkers], never by trusting the created id back — the identical "re-read, don't
     * patch from the echo" discipline [WorkingHoursApi]'s own writes establish.
     */
    public suspend fun createWorker(draft: WorkerDraft): BookingActionResult

    /**
     * `PUT /api/v1/console/workers/{workerId}` (`Ago.Calendar.Contracts.UpdateWorkerRequest`) — replace
     * semantics: every field the request carries is sent every time, including the ones this edit did
     * not touch, because omitting one clears it rather than leaving it alone (the identical replace
     * discipline [ago.chat.android.core.domain.bookings.BookingsApi.updateService]'s own doc comment
     * states, and the reason [WorkerDraft.serviceIds] is the worker's *complete* desired set).
     *
     * [WorkerDraft.calendarId] is **not** sent: the update request has no `CalendarId` because a
     * worker's calendar is fixed at creation — the server refuses to move one, so sending it would
     * offer a choice that cannot happen. [WorkerDraft.isActive], unused by [createWorker], *is* sent
     * here: deactivation is the only way this product takes a worker with booking history out of
     * rotation (see [deleteWorker]).
     */
    public suspend fun updateWorker(
        workerId: String,
        draft: WorkerDraft,
    ): BookingActionResult

    /**
     * `DELETE /api/v1/console/workers/{workerId}` — hard-deletes a worker who was **never** booked.
     *
     * **The refusal is the point of this method.** A worker with any pending, confirmed or no-show
     * booking in his history cannot be deleted — the server answers a `409` whose RFC 7807 `detail`
     * says so and says what to do instead ("Deactivate him instead."). That comes back as
     * [BookingActionResult.Refused] and is shown verbatim: the row stays on screen, and the operator is
     * pointed at the deactivate toggle ([updateWorker] with [WorkerDraft.isActive] `= false`) rather
     * than left staring at a delete that silently did nothing. Everything that is not a genuine
     * server refusal — a dropped connection, a bare non-2xx with no `detail` — is
     * [BookingActionResult.Failed].
     */
    public suspend fun deleteWorker(workerId: String): BookingActionResult
}

/**
 * `26-139`: one worker as the roster renders and the edit form prefills — `Ago.Calendar.Contracts.WorkerResponse`
 * reduced to the fields the Masters screen reads, plus [calendarId] which that response does not carry
 * and [WorkersApi.fetchWorkers] resolves from the configuration document.
 *
 * The three name parts are kept apart, not pre-joined: the edit form has a field for each
 * (Фамилия/Имя/Отчество), and [displayName] is the server's own resolved label (derived from the parts
 * until a human overrides it — `Worker.DisplayNameIsCustom`), rendered as the card's title. [middleName]
 * is `null` exactly when the worker has no patronymic, never an empty string standing in for one.
 */
public data class Worker(
    val workerId: String,
    val lastName: String,
    val firstName: String,
    val middleName: String?,
    val displayName: String,
    val isActive: Boolean,
    /** The service dictionary ids this worker performs today — what the edit form's checkbox set
     * pre-checks. May be empty while a shop is still being set up. */
    val serviceIds: List<String>,
    /** The calendar this worker belongs to, resolved from the configuration's `calendars[].workerIds`.
     * `null` only when no configuration read placed him on a calendar (a single [WorkersApi.fetchWorker]
     * read, or the defensive case of a roster worker no calendar lists — kept visible rather than
     * dropped, the identical "a row nobody can see is a row nobody can correct" resilience
     * [ago.chat.android.core.domain.schedule.WorkingHoursApi]'s own read establishes). */
    val calendarId: String?,
)

/**
 * `26-139`: the human-typed input for creating or editing a worker — the union of
 * `Ago.Calendar.Contracts.CreateWorkerRequest` and `UpdateWorkerRequest`, one type for both forms.
 *
 * The two server requests differ in exactly two fields, and this draft carries both so a single form
 * feeds either write: [calendarId] is sent only by [WorkersApi.createWorker] (a worker's calendar is
 * fixed at creation), and [isActive] only by [WorkersApi.updateWorker] (a new worker is always active).
 * Each method's own doc comment states which fields it draws.
 */
public data class WorkerDraft(
    val lastName: String,
    val firstName: String,
    /** Отчество — `null` (never sent) when the worker has no patronymic. */
    val middleName: String?,
    /** `null` lets the server derive the display name from the first and last names; a non-null value
     * is a display name a human typed by hand — `CreateWorkerRequest.DisplayName`'s own remarks. */
    val displayName: String?,
    /** Create-only: the calendar the new worker joins. Ignored by [WorkersApi.updateWorker], whose
     * request has no calendar field. */
    val calendarId: String,
    /** Edit-only: whether the worker is in rotation. Ignored by [WorkersApi.createWorker], whose
     * request has no active field (a new worker is always active). */
    val isActive: Boolean,
    /** The worker's complete, desired set of service dictionary ids — replace semantics on both writes. */
    val serviceIds: List<String>,
)

/**
 * `26-139`: one calendar the add/edit form's picker offers — the identity and the label, and nothing
 * else the picker does not draw. Resolved from `Ago.Calendar.Contracts.ConfiguredCalendarResponse`.
 *
 * The Masters screen disables its Add action, with a stated note, exactly when this list is empty:
 * a worker cannot be created without a calendar to put him on, and the honest reason is "make a
 * calendar first", not a picker with no options.
 */
public data class WorkerCalendar(
    val calendarId: String,
    val name: String,
)

/**
 * `26-139`: what the Masters screen's one read came back with — the identical three-arm shape
 * [ago.chat.android.core.domain.bookings.PendingBookingsResult]/[ago.chat.android.core.domain.schedule.WorkingHoursResult]
 * already establish, restated rather than shared because [Loaded] carries three collections none of
 * those siblings' own `Loaded` carries.
 */
public sealed interface WorkersResult {
    /** The roster, plus the two lists the add/edit form is built from. All three come from the same
     * pair of reads, so a screen that got the workers always got the picker and the checkboxes too. */
    public data class Loaded(
        val workers: List<Worker>,
        val calendars: List<WorkerCalendar>,
        val services: List<ConfiguredService>,
    ) : WorkersResult

    /** The identical "this deployment does not run AGO Calendar at all" fact
     * [ago.chat.android.core.domain.bookings.PendingBookingsResult.NotConfigured]'s own doc comment
     * explains. */
    public data object NotConfigured : WorkersResult

    /** [BookingsQueueFailure] reused again — this read reduces to the same "is it me, or is it broken"
     * two-way question every other calendar read already answers with it. */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : WorkersResult
}

/**
 * `26-139`: what reading one worker by id came back with — the identical three-arm shape [WorkersResult]
 * establishes, restated for the single-worker read whose [Loaded] carries one [Worker] rather than the
 * whole roster and its form data.
 */
public sealed interface WorkerDetailResult {
    public data class Loaded(
        val worker: Worker,
    ) : WorkerDetailResult

    public data object NotConfigured : WorkerDetailResult

    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : WorkerDetailResult
}
