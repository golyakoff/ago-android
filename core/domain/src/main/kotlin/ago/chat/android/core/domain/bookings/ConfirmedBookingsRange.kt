package ago.chat.android.core.domain.bookings

import java.time.DayOfWeek
import java.time.LocalDate

/** `26-51`: the query range, and every date it spans — [from]/[to] are what
 * [ago.chat.android.core.domain.bookings.BookingsApi.fetchConfirmedBookings] takes, and [dates] is what
 * the date strip iterates to draw one chip per day, including a day with no bookings at all (which
 * [groupByDayThenWorker] never produces a [DayGroup] for). */
public data class ConfirmedBookingsRange(
    val from: String,
    val to: String,
    val dates: List<String>,
)

/** A week beyond today, both bounds inclusive — the identical span `ago-console`'s own `defaultRange`
 * carries (`CalendarBookingsPage.tsx:29-34`), and for the identical reason that function's own doc
 * comment gives: this screen answers an operational, near-term question ("what is on for Thursday"),
 * not "what does next month look like". */
private const val RANGE_HORIZON_DAYS = 6L

/**
 * `26-51`: [today] is handed in rather than read here — the identical "logic is pure, the one clock
 * read happens at the call site" split [oldestDeadlineFirst] already draws for this package, and the
 * one this function's own caller,
 * [ago.chat.android.bookings.ConfirmedBookingsViewModel], is written to honour.
 *
 * `ago-console`'s own `defaultRange` builds [today] from `new Date().toISOString().slice(0, 10)` — the
 * *UTC* calendar date a bare `Date` renders as, not the browser's own local date — so [today] is meant
 * to be `LocalDate.now(ZoneOffset.UTC)` here too, ported faithfully rather than "corrected" to a local
 * date this function has no way to know is more right.
 */
public fun defaultConfirmedBookingsRange(today: LocalDate): ConfirmedBookingsRange {
    val horizon = today.plusDays(RANGE_HORIZON_DAYS)
    val dates =
        generateSequence(today) { it.plusDays(1) }
            .takeWhile { !it.isAfter(horizon) }
            .map { it.toString() }
            .toList()
    return ConfirmedBookingsRange(from = today.toString(), to = horizon.toString(), dates = dates)
}

/** One date strip chip's worth of information — [hasBookings] is the dot, computed against
 * [DayGroup.localDate] rather than re-fetched, since [range]'s own [ConfirmedBookingsRange.dates] and
 * [days] already come from the one read that answers both questions. */
public data class ConfirmedBookingsStripDay(
    val date: String,
    val weekday: Int,
    val hasBookings: Boolean,
)

/** 0 = Sunday, the identical convention [ConfirmedBooking.weekday] and `ConfirmedBookingResponse.Weekday`
 * both carry — `java.time.DayOfWeek.MONDAY.value == 1 .. SUNDAY.value == 7`, so `% 7` maps Sunday to 0
 * and leaves every other day one lower than its ISO value. */
private fun DayOfWeek.sundayZeroIndex(): Int = value % 7

/**
 * `26-51`: one strip entry per date in [range], in order, `hasBookings` `true` exactly when
 * [groupByDayThenWorker]'s own [days] produced a [DayGroup] for that date — a day the tenant has
 * nothing booked on is still drawn, dot-less, never omitted (the whole point of a strip that "picks the
 * day" is that every day is pickable).
 */
public fun confirmedBookingsStrip(
    range: ConfirmedBookingsRange,
    days: List<DayGroup>,
): List<ConfirmedBookingsStripDay> {
    val datesWithBookings = days.mapTo(HashSet()) { it.localDate }
    return range.dates.map { date ->
        ConfirmedBookingsStripDay(
            date = date,
            weekday = LocalDate.parse(date).dayOfWeek.sundayZeroIndex(),
            hasBookings = date in datesWithBookings,
        )
    }
}

/**
 * `26-117`: one month-label's own span across the day strip — [dayCount] chips wide, in strip order.
 * `docs/backlog/26-117-*.md`'s own hard requirement 4: one label per month, each carrying its own
 * [year], no divider between two labels, and each sitting under exactly its own [dayCount] day chips —
 * this type is what lets the UI size and position each label without re-deriving month boundaries
 * itself. [monthValue] is `1..12`, `java.time.LocalDate.getMonthValue`'s own convention, so a caller
 * indexes a 12-entry resource array with `monthValue - 1` the same way [ConfirmedBooking.weekday] is
 * already indexed directly with no remapping.
 */
public data class ConfirmedBookingsMonthLabel(
    val monthValue: Int,
    val year: Int,
    val dayCount: Int,
)

/**
 * `26-117`: [strip] collapsed into runs of consecutive days sharing one calendar month — the default
 * range never spans more than a handful of days (`RANGE_HORIZON_DAYS` above), so a strip crosses at most
 * one month boundary in practice, but this makes no assumption of that: a longer range would still
 * collapse correctly into one label per distinct `(year, month)` run, in order, each with its own
 * [ConfirmedBookingsMonthLabel.year] — the hard requirement that rules out a shared, divider-joined
 * "Сентябрь | Октябрь 2026" label spanning two different years' worth of the same month name.
 */
public fun confirmedBookingsMonthLabels(strip: List<ConfirmedBookingsStripDay>): List<ConfirmedBookingsMonthLabel> {
    val labels = mutableListOf<ConfirmedBookingsMonthLabel>()
    for (day in strip) {
        val date = LocalDate.parse(day.date)
        val last = labels.lastOrNull()
        if (last != null && last.monthValue == date.monthValue && last.year == date.year) {
            labels[labels.lastIndex] = last.copy(dayCount = last.dayCount + 1)
        } else {
            labels.add(ConfirmedBookingsMonthLabel(monthValue = date.monthValue, year = date.year, dayCount = 1))
        }
    }
    return labels
}
