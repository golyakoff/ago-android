package ago.chat.android.core.domain.analytics

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-57`: the identical `<input type="date">` -> local-day-boundary conversion `ago-console`'s own
 * `MyNumbersPage.tsx` (`startOfDayIso`/`endOfDayIso`) already establishes, restated here in Kotlin
 * rather than shared across the language boundary — that file's own doc comment gives the reason:
 * "four lines, not worth the coupling."
 *
 * [zone] is the device's own zone — the operator holding the phone, picking a calendar day on it — the
 * same role the browser's own local zone plays in the console's version of this conversion.
 * `date-and-time.md`'s rule is still obeyed on the wire: the result carries an explicit offset
 * ([DateTimeFormatter.ISO_OFFSET_DATE_TIME]), never a bare local timestamp.
 */
public fun startOfDayIso(
    date: LocalDate,
    zone: ZoneId,
): String = date.atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

/** The same calendar day's last instant, to the millisecond — [startOfDayIso]'s own doc comment
 * explains the shape; this is `ago-console`'s own `endOfDayIso`, restated for the same reason. */
public fun endOfDayIso(
    date: LocalDate,
    zone: ZoneId,
): String = date.atTime(23, 59, 59, 999_000_000).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
