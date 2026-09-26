package ago.chat.android.core.domain.workerslots

import ago.chat.android.core.domain.bookings.BookingsQueueFailure

/**
 * `26-168` (part 1 of `26-155`): the port the Слоты drill-down (a follow-up slice) reads through —
 * declared here and implemented in `:core:network` (`KtorWorkerSlotsApi`), the identical split
 * [ago.chat.android.core.domain.workers.WorkersApi] and
 * [ago.chat.android.core.domain.workerschedule.WorkerScheduleApi] already establish.
 *
 * **Its own port, not a fourth method on [ago.chat.android.core.domain.workers.WorkersApi].** The
 * materialised slot view is a different noun again — read-only, one worker, one date range — behind the
 * identical `calendar:configure` gate; [BookingsQueueFailure] is reused across the boundary rather than
 * copied, for the identical "is it me, or is it broken" reason every sibling port already gives.
 *
 * **No `type`-reading here**, unlike [ago.chat.android.core.domain.workerschedule.WorkerScheduleApi] and
 * [ago.chat.android.core.domain.recut.RecutApi]: `worker_slots.invalid_range` is the one refusal this
 * read can produce, and it already carries a genuine, actionable RFC 7807 `detail` ("the range must end
 * on or after it starts") — there is no second refusal for a `type` comparison to tell apart from it.
 */
public interface WorkerSlotsApi {
    /**
     * `GET /api/v1/console/workers/{workerId}/slots?from&to` — every materialised slot in `[from, to]`,
     * both bounds inclusive, whatever its status. [WorkerSlotsResult.Refused] carries the server's own
     * sentence for a range whose end is before its start — a caller mistake the operator can fix by
     * picking different dates, not a generic failure.
     */
    public suspend fun fetchSlots(
        workerId: String,
        from: String,
        to: String,
    ): WorkerSlotsResult
}

/**
 * `Ago.Calendar.Contracts.WorkerSlotResponse.Status`'s six wire spellings, classified here rather than
 * left as a raw string for a UI `when` to switch on — the identical "the classification lives in
 * `:core:domain`" call [ago.chat.android.core.domain.readiness.BookingPrecondition]'s own doc comment
 * already makes. [Unknown] is not a bug: a backend that adds a seventh status before this client is
 * taught it must still render something, and [WorkerSlot.rawStatus] is what that something is.
 */
public enum class WorkerSlotStatus { Available, PendingConfirmation, Booked, Cancelled, NoShow, Blocked, Unknown }

/**
 * `Ago.Calendar.Contracts.WorkerSlotResponse`, field for field.
 *
 * [personId] is not personal data — an opaque person reference, never gated — so it tells [phone]'s two
 * null-reasons apart the identical way the server response's own remarks do: `null` here means nobody
 * holds the slot; non-null with [phone] `null` means somebody does and this operator may not see who.
 * [bookingId] is `null` exactly when [status] is [WorkerSlotStatus.Available] or
 * [WorkerSlotStatus.Blocked]; two rows sharing it are two slots of one run, deliberately not merged into
 * one row — `20-15`'s own scope, which this item inherits unchanged.
 */
public data class WorkerSlot(
    val eventId: String,
    /** Business-local, `YYYY-MM-DD`. */
    val localDate: String,
    /** `0 = Sunday`, matching `java.time.DayOfWeek`'s *opposite* convention — server-derived from
     * [localDate], the identical translation
     * [ago.chat.android.core.domain.schedule.WorkingHoursRule.dayOfWeek]'s own doc comment states. */
    val weekday: Int,
    val startsAt: String,
    val endsAt: String,
    val status: WorkerSlotStatus,
    /** The wire spelling verbatim, for the one caller that needs it — [WorkerSlotStatus]'s own doc
     * comment explains why. */
    val rawStatus: String,
    /** `null` on a [WorkerSlotStatus.Blocked] row — a closure is not a service. */
    val serviceId: String?,
    val serviceName: String?,
    val personId: String?,
    /** `null` either because [personId] is `null` too, or because this operator lacks `customer:read` —
     * [personId]'s own doc comment names the discriminator. */
    val phone: String?,
    val masked: Boolean,
    val bookingId: String?,
)

/**
 * What reading one worker's slots came back with — a four-arm shape rather than the usual three
 * ([ago.chat.android.core.domain.workers.WorkersResult] and its siblings): [Refused] is this port's own
 * class doc comment's reason for existing without a `type` check — the one caller-fixable refusal this
 * read produces already carries a genuine `detail`.
 */
public sealed interface WorkerSlotsResult {
    public data class Loaded(
        val slots: List<WorkerSlot>,
    ) : WorkerSlotsResult

    /** The identical "this deployment does not run AGO Calendar at all" fact
     * [ago.chat.android.core.domain.workers.WorkersResult.NotConfigured]'s own doc comment explains. */
    public data object NotConfigured : WorkerSlotsResult

    /** `worker_slots.invalid_range` — `to` before `from`. Shown verbatim; the range picker stays open
     * for another try, never silently clamped. */
    public data class Refused(
        val detail: String,
    ) : WorkerSlotsResult

    /** [BookingsQueueFailure] reused again — this read reduces to the same "is it me, or is it broken"
     * two-way question every other calendar read already answers with it. */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : WorkerSlotsResult
}
