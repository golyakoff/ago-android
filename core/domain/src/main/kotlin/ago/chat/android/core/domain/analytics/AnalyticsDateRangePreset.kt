package ago.chat.android.core.domain.analytics

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * `26-71`: the three date-range presets `ago-console`'s own `rangePresets.ts` establishes for this
 * report (`docs/backlog/26-71-*.md`'s own Scope item 7) — calendar month, previous calendar month,
 * last 30 days — ported once here so a later report needing the identical three
 * (`docs/backlog/26-71-*.md`'s own Found section names `18-11` as a sibling with the same scope) can
 * reuse rather than re-derive them.
 *
 * **Client-side only, on purpose — there is no server-side "preset" concept**, the identical reasoning
 * `rangePresets.ts`'s own doc comment gives: every preset here resolves to a concrete `from`/`to` pair
 * in the caller's own local time zone, sent through the exact same `from`/`to` query parameters the
 * free-form date fields already use. The server always echoes back the range it actually used, so a
 * preset button is UX sugar over the existing contract, never a new one.
 *
 * **Local time, not UTC** — a calendar month boundary is a wall-clock concept ("the whole of March"),
 * and computing it in UTC would shift the boundary by the caller's own UTC offset, the same reasoning
 * [startOfDayIso]/[endOfDayIso] already apply to a single day. [now] therefore carries its own zone
 * (the device's own, the same role [startOfDayIso]'s own `zone` parameter plays), rather than this file
 * calling [ZonedDateTime.now] itself — a pure function of its argument is what makes
 * `AnalyticsDateRangePresetTest` possible without a device clock to fake.
 */
public data class AnalyticsDateRangePreset(
    public val from: String,
    public val to: String,
)

/** The current calendar month so far — [AnalyticsDateRangePreset.from] is this month's first instant,
 * [AnalyticsDateRangePreset.to] is [now] itself (not the theoretical end of the month, which has not
 * happened yet and would report on a range that includes no real data past the current moment
 * anyway). */
public fun currentCalendarMonth(now: ZonedDateTime): AnalyticsDateRangePreset {
    val from = now.withDayOfMonth(1).toLocalDate().atStartOfDay(now.zone)
    return AnalyticsDateRangePreset(from.format(ISO_FORMAT), now.format(ISO_FORMAT))
}

/** The complete previous calendar month — [AnalyticsDateRangePreset.from] its first instant,
 * [AnalyticsDateRangePreset.to] (exclusive) this month's first instant, so the range covers every day
 * of that month and nothing of this one. */
public fun previousCalendarMonth(now: ZonedDateTime): AnalyticsDateRangePreset {
    val thisMonthStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay(now.zone)
    val from = thisMonthStart.minusMonths(1)
    return AnalyticsDateRangePreset(from.format(ISO_FORMAT), thisMonthStart.format(ISO_FORMAT))
}

/** The trailing thirty days up to [now] — the same width `GetConversionReportForSiteHandler`'s own
 * default window already uses when no range is named at all, offered here as an explicit, nameable
 * choice rather than only an implicit one. */
public fun last30Days(now: ZonedDateTime): AnalyticsDateRangePreset {
    val from = now.minusDays(30)
    return AnalyticsDateRangePreset(from.format(ISO_FORMAT), now.format(ISO_FORMAT))
}

private val ISO_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
