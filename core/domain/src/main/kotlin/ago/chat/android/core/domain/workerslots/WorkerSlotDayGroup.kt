package ago.chat.android.core.domain.workerslots

/** One business-local day and every slot [WorkerSlotsApi.fetchSlots] returned for it, in the server's
 * own row order. */
public data class WorkerSlotDayGroup(
    val localDate: String,
    val weekday: Int,
    val slots: List<WorkerSlot>,
)

/**
 * `26-171` (`26-155` part 3): groups a flat [WorkerSlotsApi.fetchSlots] read by
 * [WorkerSlot.localDate] — the identical
 * [ago.chat.android.core.domain.bookings.groupByDayThenWorker]'s own doc comment states for its sibling
 * grouping: a day's own position in the result is exactly the order the first slot naming it arrived
 * in, never a client-side sort, so the server's own `order by starts_at` ordering survives into the
 * grouped shape unchanged. Rows sharing a [WorkerSlot.bookingId] are **not** merged into one — `20-15`'s
 * own scope, which this item inherits unchanged (`docs/design/26-155-*.md`).
 */
public fun groupSlotsByDay(slots: List<WorkerSlot>): List<WorkerSlotDayGroup> {
    val dayByDate = LinkedHashMap<String, MutableWorkerSlotDayGroup>()

    for (slot in slots) {
        val day = dayByDate.getOrPut(slot.localDate) { MutableWorkerSlotDayGroup(localDate = slot.localDate, weekday = slot.weekday) }
        day.slots += slot
    }

    return dayByDate.values.map { day -> WorkerSlotDayGroup(localDate = day.localDate, weekday = day.weekday, slots = day.slots) }
}

private class MutableWorkerSlotDayGroup(
    val localDate: String,
    val weekday: Int,
) {
    val slots: MutableList<WorkerSlot> = mutableListOf()
}
