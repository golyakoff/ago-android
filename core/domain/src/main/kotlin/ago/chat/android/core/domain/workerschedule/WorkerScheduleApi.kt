package ago.chat.android.core.domain.workerschedule

import ago.chat.android.core.domain.bookings.BookingsQueueFailure

/**
 * `26-168` (part 1 of `26-155`): the port the График drill-down (a follow-up slice) reads and writes
 * through — declared here and implemented in `:core:network` (`KtorWorkerScheduleApi`), the identical
 * split [ago.chat.android.core.domain.workers.WorkersApi] and
 * [ago.chat.android.core.domain.schedule.WorkingHoursApi] already establish. The dependency rule is what
 * puts it here: a view model holding an `HttpClient` directly could not be tested without one, and every
 * HTTP-shaped decision (which status means what, which base URL to call) belongs on the far side of this
 * interface, in the adapter.
 *
 * **Its own port, not a fourth method on [ago.chat.android.core.domain.workers.WorkersApi] or a third on
 * [ago.chat.android.core.domain.schedule.WorkingHoursApi].** A worker's *schedule template* (weekly or
 * cycle, slot length, buffer, horizon) is a different noun from the roster and from a recurring
 * working-hours rule, behind the identical `calendar:configure` gate — the same "own port" reasoning both
 * of those interfaces' own doc comments already give for themselves. [BookingsQueueFailure] is reused
 * across the boundary rather than copied, deliberately: despite its name it answers only "is it me, or is
 * it broken", the one classification this port needs too.
 *
 * **The one adapter-side gap `docs/design/26-155-*.md` flagged.** Every existing calendar adapter reads
 * only RFC 7807 `detail`; this one must also read `type`, because `GET .../schedule` answering
 * `configuration.no_schedule` is a real, common "nothing saved yet" state a screen renders as an empty
 * form, not a failure banner — [WorkerScheduleResult.None] is that state, told apart from
 * [WorkerScheduleResult.Failed] by the server's own stable `type`, never by parsing its prose `detail`
 * (`ErrorExtensions.cs`: "clients branch on `type`, never on the message").
 */
public interface WorkerScheduleApi {
    /**
     * `GET /api/v1/console/workers/{workerId}/schedule` — one worker's schedule template, in full.
     * [WorkerScheduleResult.None] is the honest "no schedule yet" state a fresh worker starts in;
     * [WorkerScheduleResult.Failed] is everything else that kept the read from answering usefully.
     */
    public suspend fun fetchSchedule(workerId: String): WorkerScheduleResult

    /**
     * `PUT /api/v1/console/workers/{workerId}/schedule` — create-or-replace: the server itself has one
     * verb for both, so this port does too. Every field on [draft] is sent every time, the identical
     * replace-semantics discipline [ago.chat.android.core.domain.workers.WorkersApi.updateWorker]'s own
     * doc comment states, because the server has no partial-patch shape to send a subset to.
     *
     * A domain refusal an operator can act on — `MaterializeFrom` moving the cursor backwards, a missing
     * cycle field — comes back as [SaveWorkerScheduleResult.Refused] carrying the server's own sentence,
     * never a message this port invented. The caller re-reads through [fetchSchedule] to confirm a save,
     * the same "re-read, don't trust the echo" discipline this app's other configuration writes already
     * follow — but [SaveWorkerScheduleResult.Saved] still carries the server's response directly, since a
     * screen that just saved wants its horizon/cursor reflected without a second round trip.
     */
    public suspend fun saveSchedule(
        workerId: String,
        draft: WorkerScheduleDraft,
    ): SaveWorkerScheduleResult
}

/**
 * `Ago.Calendar.Domain.ScheduleKind` — exactly two values, both validated server-side (a third string is
 * refused with `configuration.invalid` before it ever reaches a schedule). A closed, two-way domain
 * choice rather than a raw wire string: the follow-up screen's own `FilterChip` pair (Недельный / Цикл)
 * needs to switch on it, and unlike [ago.chat.android.core.domain.readiness.BookingPrecondition] (which
 * keeps a defensive `Unknown` for a list the server could plausibly grow) this pair is fixed by the
 * server's own validation, so an unrecognised spelling is genuinely a shape mismatch — the adapter treats
 * it as one rather than inventing a third case nothing produces.
 */
public enum class ScheduleKind { Weekly, Cycle }

/**
 * `Ago.Calendar.Contracts.WorkerScheduleResponse`, field for field. Every cycle field is `null` while
 * [kind] is [ScheduleKind.Weekly] and vice versa — the server's own one-shape-for-either-kind contract,
 * carried here unchanged rather than split into two subtypes, so a caller never has to guess which fields
 * a given [kind] populates.
 */
public data class WorkerSchedule(
    val scheduleId: String,
    val workerId: String,
    val kind: ScheduleKind,
    /** ISO `yyyy-MM-dd`, required by [ScheduleKind.Cycle], `null` for [ScheduleKind.Weekly]. */
    val cycleAnchor: String?,
    val cycleWorkingDays: Int?,
    val cycleRestDays: Int?,
    /** Wall clock `"HH:mm"` in the worker's calendar's own zone — never an instant, the identical
     * convention [ago.chat.android.core.domain.schedule.WorkingHoursRule.startsAt] already states. */
    val cycleStartsAt: String?,
    val cycleEndsAt: String?,
    val slotMinutes: Int,
    val bufferMinutes: Int,
    val horizonDays: Int,
    /** ISO `yyyy-MM-dd` — the schedule's own cursor: nothing before this date is touched by an ordinary
     * save (`RecutApi` is the one deliberate exception, `docs/design/26-155-*.md`'s own scope). */
    val materializeFrom: String,
    val createdAt: String,
    val updatedAt: String,
    val buffersCountTowardServiceDuration: Boolean,
)

/**
 * `Ago.Calendar.Contracts.SaveWorkerScheduleRequest`, field for field — the one shape both create and
 * replace share, since the server itself has one verb for both. [buffersCountTowardServiceDuration]
 * defaults to `true`, mirroring the server's own default for a caller that has no opinion yet.
 */
public data class WorkerScheduleDraft(
    val kind: ScheduleKind,
    val cycleAnchor: String?,
    val cycleWorkingDays: Int?,
    val cycleRestDays: Int?,
    val cycleStartsAt: String?,
    val cycleEndsAt: String?,
    val slotMinutes: Int,
    val bufferMinutes: Int,
    val horizonDays: Int,
    val materializeFrom: String,
    val buffersCountTowardServiceDuration: Boolean = true,
)

/**
 * What reading one worker's schedule came back with. A four-arm shape rather than the usual three
 * ([ago.chat.android.core.domain.workers.WorkersResult] and its siblings): [None] is the server's own
 * `configuration.no_schedule`, a real "nothing saved yet" state the follow-up screen renders as an empty
 * form, told apart from [Failed] by the RFC 7807 `type` this port's own class doc comment explains.
 */
public sealed interface WorkerScheduleResult {
    public data class Loaded(
        val schedule: WorkerSchedule,
    ) : WorkerScheduleResult

    /** `configuration.no_schedule` — this worker exists but has never had a schedule saved. */
    public data object None : WorkerScheduleResult

    /** The identical "this deployment does not run AGO Calendar at all" fact
     * [ago.chat.android.core.domain.workers.WorkersResult.NotConfigured]'s own doc comment explains. */
    public data object NotConfigured : WorkerScheduleResult

    /** [BookingsQueueFailure] reused again — this read reduces to the same "is it me, or is it broken"
     * two-way question every other calendar read already answers with it. */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : WorkerScheduleResult
}

/**
 * What creating or replacing one worker's schedule came back with — the identical succeeded/refused/
 * failed three-way split [ago.chat.android.core.domain.bookings.BookingActionResult] establishes, with
 * the one difference every configuration write that returns something already has
 * ([ago.chat.android.core.domain.schedule.WorkingHoursChangeResult]'s own precedent): success here
 * carries the schedule the server actually stored, not a bare acknowledgement.
 */
public sealed interface SaveWorkerScheduleResult {
    public data class Saved(
        val schedule: WorkerSchedule,
    ) : SaveWorkerScheduleResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail` — a cycle field missing, a horizon over
     * 180 days, `materializeFrom` moving the cursor backwards — worded by the server itself and shown
     * verbatim, never a message this port invented. */
    public data class Refused(
        val detail: String,
    ) : SaveWorkerScheduleResult

    /** Everything that is not a genuine server refusal, classified the one way this app classifies
     * anything ([BookingsQueueFailure]). */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : SaveWorkerScheduleResult
}
