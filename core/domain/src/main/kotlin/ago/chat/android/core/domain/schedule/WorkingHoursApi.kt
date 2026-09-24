package ago.chat.android.core.domain.schedule

import ago.chat.android.core.domain.bookings.BookingsQueueFailure

/**
 * `26-97`: the port the working-hours list reads and writes through — declared here and implemented in
 * `:core:network` (`KtorWorkingHoursApi`), the identical split
 * [ago.chat.android.core.domain.bookings.BookingsApi] already establishes.
 *
 * **Why this item exists at all.** Until `26-97`, `Ago.Calendar.Api` mapped exactly one working-hours
 * verb — `POST /api/v1/console/working-hours`. There was no `PUT` and no `DELETE`, anywhere, so a
 * mistyped 09:00-for-19:00 was permanent and the only remedy in the whole product was deleting the
 * worker, which discards everything else about them. A working-hours rule is precondition 4 of the six
 * `booking-readiness` checks and decides what a schedule template materialises into real slots, so the
 * cost of the typo is every day of the horizon silently selling the wrong hours.
 *
 * **Its own port, not a fourth method on `BookingsApi`.** The three reads that interface carries are
 * all "what is on the calendar"; these are configuration writes on a different noun, gated
 * server-side on `calendar:configure` alone. [BookingsQueueFailure] *is* reused across the boundary
 * rather than copied, and deliberately: despite its name it answers only "is it me, or is it broken",
 * a classification this port needs in exactly the same two flavours, and a second identical enum would
 * be one more thing for `26-59`'s own message rules to have to find.
 */
public interface WorkingHoursApi {
    /**
     * `GET /api/v1/console/configuration` — the same read `ago-console`'s own `CalendarSetupPage`
     * makes, flattened by the adapter into one list across every calendar. There is no id-addressed
     * working-hours read on the server (`26-97` added the two writes, not a third read), so the
     * configuration document is the list: a rule lives inside its calendar there, alongside the worker
     * roster this port needs anyway to say *whose* hours a row is.
     */
    public suspend fun fetchWorkingHours(): WorkingHoursResult

    /**
     * `PUT /api/v1/console/working-hours/{ruleId}` — the weekday and the two wall-clock times, and
     * deliberately nothing else. A rule is corrected where it is, never moved to another worker or
     * calendar: the server refuses that outright (`WorkingHoursRule.ChangeTo`), so sending an id here
     * would offer a choice that cannot happen.
     *
     * [startsAt]/[endsAt] are wall clock in the calendar's own zone — `"09:00"`, never an instant.
     * An offset chosen at configuration time is wrong for half the year in any zone with DST.
     */
    public suspend fun updateWorkingHoursRule(
        ruleId: String,
        dayOfWeek: Int,
        startsAt: String,
        endsAt: String,
    ): WorkingHoursChangeResult

    /** `DELETE /api/v1/console/working-hours/{ruleId}` — answers `200` with a body rather than `204`,
     * because the body is the point: see [WorkingHoursReconciliation]. */
    public suspend fun deleteWorkingHoursRule(ruleId: String): WorkingHoursChangeResult
}

/**
 * One recurring weekly window, with the two names only the whole configuration document can resolve.
 *
 * [dayOfWeek] is `0 = Sunday`, matching `java.time.DayOfWeek`'s *opposite* convention deliberately: the
 * wire uses `System.DayOfWeek`'s numbering (`Ago.Calendar.Contracts.WorkingHoursRuleResponse`), and
 * translating it here rather than at the edge would leave two numberings in the app with nothing to
 * say which is which. The UI maps it to a label; nothing in this app does weekday arithmetic on it.
 */
public data class WorkingHoursRule(
    val ruleId: String,
    val workerId: String,
    val workerName: String,
    val calendarName: String,
    val dayOfWeek: Int,
    val startsAt: String,
    val endsAt: String,
)

/**
 * `26-97`: what a correction did **not** reach — the days this worker's schedule had already
 * materialised from the old hours.
 *
 * **The item's own design decision, carried on the wire.** A rule edit is always allowed and can never
 * damage a booking: the materialiser only ever inserts into business-local days that have no event row
 * at all, and only ever forward of the schedule's own cursor. What it *can* do is nothing visible —
 * the operator fixes 09:00 to 19:00, the screen accepts it, and the next three weeks keep selling the
 * old hours. So the server answers every edit and delete with this, and the screen states it. Nothing
 * here is optional or conditional: a field that only appeared when something was wrong could not be
 * told apart from a build that does not send it.
 */
public data class WorkingHoursReconciliation(
    /** `YYYY-MM-DD`, business-local — the date to re-cut the schedule from. `null` exactly when
     * [alreadyCutDays] is empty, which is the ordinary, happy case. */
    val recutFrom: String?,
    /** Business-local dates, oldest first. */
    val alreadyCutDays: List<String>,
    /** Pending, confirmed and no-show bookings sitting on those days. Zero means re-cutting them would
     * cancel nothing. */
    val liveBookingCount: Int,
)

/** What reading the working-hours list came back with — the identical three-arm shape
 * [ago.chat.android.core.domain.bookings.PendingBookingsResult] already establishes, including its
 * [NotConfigured] arm for a deployment that does not run AGO Calendar at all. */
public sealed interface WorkingHoursResult {
    public data class Loaded(
        val rules: List<WorkingHoursRule>,
    ) : WorkingHoursResult

    public data object NotConfigured : WorkingHoursResult

    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : WorkingHoursResult
}

/**
 * What correcting or removing one rule came back with — the identical
 * succeeded/refused/failed three-way split
 * [ago.chat.android.core.domain.bookings.BookingActionResult] establishes, with the one difference
 * that success here carries something: the [WorkingHoursReconciliation] this item exists to stop
 * anyone dropping on the floor.
 */
public sealed interface WorkingHoursChangeResult {
    public data class Changed(
        val reconciliation: WorkingHoursReconciliation,
    ) : WorkingHoursChangeResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail` — a rule that ends before it starts,
     * or one belonging to another tenant, worded by the server itself and shown verbatim. */
    public data class Refused(
        val detail: String,
    ) : WorkingHoursChangeResult

    /** Everything that is not a genuine server refusal, classified the one way this app classifies
     * anything ([BookingsQueueFailure]) — never a fabricated `detail` string. */
    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : WorkingHoursChangeResult
}
