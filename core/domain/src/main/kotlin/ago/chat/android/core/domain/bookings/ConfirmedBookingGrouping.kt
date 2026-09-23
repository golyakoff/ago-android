package ago.chat.android.core.domain.bookings

/** One master's own rows on one day — [workerDisplayName] rather than [workerId] alone, since the
 * whole reason this grouping exists is the group header naming who the rows belong to. */
public data class WorkerGroup(
    val workerId: String,
    val workerDisplayName: String,
    val rows: List<ConfirmedBooking>,
)

/** One business-local day, and every master with at least one confirmed booking on it. [count] is the
 * day's own total across every master — the "how loaded is this day" fact the date strip's own dot
 * answers only as present/absent; this is where the number itself lives. */
public data class DayGroup(
    val localDate: String,
    val weekday: Int,
    val count: Int,
    val workers: List<WorkerGroup>,
)

/**
 * `26-51`: ports `ago-console`'s own `groupByDayThenWorker` (`calendarApi.ts:55`) verbatim rather than
 * re-deriving the grouping independently — that function's own doc comment states why: the server's
 * own row order (`ConfirmedBookingReadStore`'s SQL, `order by e.local_date, w.display_name, min(e.starts_at)`)
 * *is* the grouping, so a day or a worker's own position in the result is exactly the order the first
 * row naming it arrived in, never a client-side sort. A day absent from [rows] entirely is absent from
 * this result too — [ago.chat.android.bookings.ConfirmedBookingsViewModel]'s own caller is what turns
 * "no [DayGroup] for the selected date" into the screen's stated empty state, not this function.
 */
public fun groupByDayThenWorker(rows: List<ConfirmedBooking>): List<DayGroup> {
    val dayByDate = LinkedHashMap<String, MutableDayGroup>()

    for (row in rows) {
        val day = dayByDate.getOrPut(row.localDate) { MutableDayGroup(localDate = row.localDate, weekday = row.weekday) }
        day.count += 1

        val worker =
            day.workers.getOrPut(row.workerId) {
                MutableWorkerGroup(workerId = row.workerId, workerDisplayName = row.workerDisplayName)
            }
        worker.rows += row
    }

    return dayByDate.values.map { day ->
        DayGroup(
            localDate = day.localDate,
            weekday = day.weekday,
            count = day.count,
            workers = day.workers.values.map { WorkerGroup(it.workerId, it.workerDisplayName, it.rows) },
        )
    }
}

private class MutableDayGroup(
    val localDate: String,
    val weekday: Int,
) {
    var count: Int = 0
    val workers: LinkedHashMap<String, MutableWorkerGroup> = LinkedHashMap()
}

private class MutableWorkerGroup(
    val workerId: String,
    val workerDisplayName: String,
) {
    val rows: MutableList<ConfirmedBooking> = mutableListOf()
}
