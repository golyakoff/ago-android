package ago.chat.android.core.domain.recut

import ago.chat.android.core.domain.bookings.BookingsQueueFailure

/**
 * `26-168` (part 1 of `26-155`): the port the Пересчёт drill-down (a follow-up slice) reads and writes
 * through — declared here and implemented in `:core:network` (`KtorRecutApi`), the identical split
 * [ago.chat.android.core.domain.workers.WorkersApi] and
 * [ago.chat.android.core.domain.workerschedule.WorkerScheduleApi] already establish. This is the one
 * shipped Android touchpoint that already points at this port before it existed: the Часы screen's own
 * `RecutNotice` (`26-97`) tells the operator to «пересчитайте расписание с {date}» with nowhere to do it.
 *
 * **Its own port, not a fourth method on [ago.chat.android.core.domain.workers.WorkersApi].** A re-cut is
 * a distinct, destructive action — preview, then a fingerprinted confirm — with its own actor-facing
 * vocabulary (a staleness refusal, a missing per-booking decision) the read-only ports never needed, the
 * identical reasoning `Ago.Calendar.Application.RecutErrors`'s own doc comment gives for not folding this
 * into `AvailabilityErrors` server-side. [BookingsQueueFailure] is reused across the boundary rather than
 * copied, for the identical "is it me, or is it broken" reason every sibling port already gives.
 *
 * **The second adapter-side gap `docs/design/26-155-*.md` flagged.** [confirm]'s central refusal,
 * `recut.stale` (a booking landed since the preview this call's fingerprint came from), must be told apart
 * from every other refusal so the follow-up screen can clear its stale preview and ask for a fresh one
 * rather than just showing a sentence — [RecutConfirmResult.Refused.code] is the server's own stable RFC
 * 7807 `type`, read for exactly that reason, never parsed out of the prose `detail`.
 */
public interface RecutApi {
    /**
     * `POST /api/v1/console/workers/{workerId}/schedule/recut/preview` — every day in
     * `[from, today+horizon]`, including empty ones, and the opaque [RecutPreview.fingerprint] [confirm]
     * must hand back unchanged. A bounds refusal the operator can fix by picking a different `from` (it
     * already passed, or nothing already cut sits at or after it) comes back as [RecutPreviewResult.Refused]
     * with the server's own `code` — `recut.from_before_today`, `recut.not_a_regression`,
     * `recut.horizon_before_from`, `recut.worker_has_no_schedule` — shown verbatim.
     */
    public suspend fun preview(
        workerId: String,
        from: String,
    ): RecutPreviewResult

    /**
     * `POST /api/v1/console/workers/{workerId}/schedule/recut` — applies [decisions] and clears and
     * re-cuts every day the preview showed. [decisions] carries one entry per
     * [RecutBooking.canDecide] booking the preview showed; an entry for one it did not, or a missing
     * entry for one it did, is `recut.invalid`/`recut.missing_decision` respectively — a caller mistake,
     * not the world moving.
     *
     * **`recut.stale`, this call's own central refusal**, is the reason [RecutConfirmResult.Refused]
     * carries a `code` at all: the bookings in range changed since the preview's own fingerprint was
     * computed — most likely a customer claimed a slot in between — and the honest remedy is a fresh
     * preview, never a silent retry of the same decisions against a range that moved. `recut.day_changed_concurrently`
     * is the narrower sibling — a claim landing in the gap between this call's own staleness check and one
     * day's own write — and carries the identical remedy, with the days already re-cut in this same
     * request standing rather than rolled back (the server's own remarks on why).
     */
    public suspend fun confirm(
        workerId: String,
        from: String,
        fingerprint: String,
        decisions: List<RecutBookingDecision>,
    ): RecutConfirmResult
}

/** `Ago.Calendar.Contracts.RecutDecisionRequest.Decision`'s two wire spellings — client-constructed, so
 * unlike [ago.chat.android.core.domain.workerslots.WorkerSlotStatus] there is no defensive `Unknown` arm:
 * this app is the one choosing the value, never reading an open-ended one back off the wire. */
public enum class RecutDecision { Cancel, Keep }

/** `Ago.Calendar.Contracts.RecutDecisionRequest`, field for field — one entry per decidable booking the
 * preview showed. */
public data class RecutBookingDecision(
    val bookingId: String,
    val decision: RecutDecision,
)

/**
 * `Ago.Calendar.Contracts.RecutBookingPreviewResponse.Status`'s three wire spellings — a booking inside
 * the re-cut range is always pending, confirmed, or a recorded no-show, never anything a slot itself can
 * be (`Available`/`Cancelled`/`Blocked` describe a slot with no booking on it, or one already gone). The
 * identical defensive-`Unknown` reasoning
 * [ago.chat.android.core.domain.workerslots.WorkerSlotStatus]'s own doc comment gives, restated for this
 * narrower, three-value list rather than shared with it — the two enums carry no case in common worth
 * generalising over.
 */
public enum class RecutBookingStatus { PendingConfirmation, Booked, NoShow, Unknown }

/**
 * `Ago.Calendar.Contracts.RecutBookingPreviewResponse`, field for field. [canDecide] is `false` only for
 * a [RecutBookingStatus.NoShow] row — the follow-up screen shows it with no Отменить/Оставить control at
 * all, since it always forces its own day to be skipped rather than re-cut.
 */
public data class RecutBooking(
    val bookingId: String,
    val startsAt: String,
    val endsAt: String,
    val status: RecutBookingStatus,
    val rawStatus: String,
    val serviceId: String?,
    val serviceName: String?,
    val personId: String?,
    val phone: String?,
    val masked: Boolean,
    val canDecide: Boolean,
)

/** `Ago.Calendar.Contracts.RecutDayPreviewResponse`, field for field — one entry per day in
 * `[from, today+horizon]`, including a day with no bookings at all. */
public data class RecutDay(
    val localDate: String,
    val availableSlotsToDelete: Int,
    val bookings: List<RecutBooking>,
)

/** `Ago.Calendar.Contracts.RecutPreviewResponse`, field for field. [fingerprint] is opaque to this app —
 * carried forward into [RecutApi.confirm] unchanged, never inspected or recomputed on this side. */
public data class RecutPreview(
    val days: List<RecutDay>,
    val fingerprint: String,
)

/** `Ago.Calendar.Contracts.RecutConfirmResponse`, field for field — the four numbers and two day lists
 * the follow-up screen's own confirm dialog and result card render verbatim. */
public data class RecutConfirmation(
    val recutDays: List<String>,
    val skippedDays: List<String>,
    val slotsDeleted: Int,
    val slotsInserted: Int,
    val bookingsCancelled: Int,
)

/**
 * What asking for a re-cut preview came back with — the identical three-arm shape
 * [ago.chat.android.core.domain.workers.WorkersResult] establishes, with [Refused] carrying the server's
 * own `code` alongside its `detail` so a caller can tell apart the bounds refusals this port's own doc
 * comment on [RecutApi.preview] names, rather than showing every one identically.
 */
public sealed interface RecutPreviewResult {
    public data class Loaded(
        val preview: RecutPreview,
    ) : RecutPreviewResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail`. [code] is the server's own stable
     * `type` (`recut.from_before_today`, `recut.not_a_regression`, `recut.horizon_before_from`,
     * `recut.worker_has_no_schedule`, `recut.worker_not_on_a_calendar`, `recut.worker_not_found`,
     * `recut.forbidden`) — empty when the body carried a `detail` but no `type` at all, which never
     * happens against this server (`ErrorExtensions.ToProblem` always sets both) but is not treated as a
     * parse failure either. */
    public data class Refused(
        val detail: String,
        val code: String,
    ) : RecutPreviewResult

    /** The identical "this deployment does not run AGO Calendar at all" fact
     * [ago.chat.android.core.domain.workers.WorkersResult.NotConfigured]'s own doc comment explains. */
    public data object NotConfigured : RecutPreviewResult

    /** [BookingsQueueFailure] reused again — this read reduces to the same "is it me, or is it broken"
     * two-way question every other calendar call already answers with it. */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : RecutPreviewResult
}

/**
 * What confirming a re-cut came back with — the identical shape [RecutPreviewResult] establishes,
 * restated for [confirm]'s own success payload. [Refused.code] is this port's whole reason for existing
 * with a `code` field at all: `recut.stale` is the one refusal the follow-up screen must react to
 * specially (clear the stale preview, ask for a fresh one) rather than merely display.
 */
public sealed interface RecutConfirmResult {
    public data class Confirmed(
        val confirmation: RecutConfirmation,
    ) : RecutConfirmResult

    /** [code] is `recut.stale` (see [RecutApi.confirm]'s own doc comment), `recut.day_changed_concurrently`,
     * `recut.missing_decision`, `recut.invalid`, or one of [RecutPreviewResult.Refused]'s own bounds
     * codes reproduced on this second call — empty when a `detail` arrived with no `type`, the identical
     * defensive default [RecutPreviewResult.Refused.code]'s own doc comment states. */
    public data class Refused(
        val detail: String,
        val code: String,
    ) : RecutConfirmResult

    public data object NotConfigured : RecutConfirmResult

    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : RecutConfirmResult
}
