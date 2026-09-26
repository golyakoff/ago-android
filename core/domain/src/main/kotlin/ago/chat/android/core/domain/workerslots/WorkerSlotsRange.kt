package ago.chat.android.core.domain.workerslots

import java.time.LocalDate

/**
 * `26-171` (`26-155` part 3): the query range [WorkerSlotsApi.fetchSlots] takes — [from]/[to], both
 * bounds inclusive ([WorkerSlotsApi.fetchSlots]'s own doc comment).
 */
public data class WorkerSlotsRange(
    val from: String,
    val to: String,
)

/** Q5's accepted default (`docs/design/26-155-*.md`): today through fourteen days ahead, matching
 * `ago-console`'s own `CalendarWorkerSlotsPage.tsx` default range. */
private const val RANGE_HORIZON_DAYS = 14L

/**
 * [today] is handed in rather than read here — the identical "logic is pure, the one clock read happens
 * at the call site" split
 * [ago.chat.android.core.domain.bookings.defaultConfirmedBookingsRange]'s own doc comment draws for its
 * sibling range, restated here rather than reused because that type also carries a per-day `dates` strip
 * this port's own flat range has no strip to need.
 */
public fun defaultWorkerSlotsRange(today: LocalDate): WorkerSlotsRange =
    WorkerSlotsRange(from = today.toString(), to = today.plusDays(RANGE_HORIZON_DAYS).toString())
