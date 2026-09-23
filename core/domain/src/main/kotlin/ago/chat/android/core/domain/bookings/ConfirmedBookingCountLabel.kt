package ago.chat.android.core.domain.bookings

/**
 * `26-51`: "Ирина Соколова · 4 записи" — the mockup's own worker-group header needs Russian
 * grammatical agreement on "запись", which Android's `<plurals>` resource cannot safely give it:
 * `<plurals>` selects its bucket from the *device's* configured locale, and this app's `values/`
 * bucket is Russian text shown regardless of device locale (there is no `values-ru` overlay - `docs/architecture.md`
 * on this app being Russian-only) - a device set to English would apply English one/other rules to
 * Russian words and produce wrong agreement (2 and 5 both landing on "other" while the text still reads
 * "запись"). `ago-console`'s own `calendarBookingsCountLabel` sidesteps the whole problem with "Записей:
 * N", explicitly to need no agreement (`CalendarBookingsPage.tsx`'s own doc comment) - the item's own
 * mockup instead spells out "N записи" in full, so this function is the one place that spelling's own
 * agreement rule lives, computed in Kotlin rather than through a resource mechanism that would silently
 * get it wrong on a non-Russian device.
 */
public fun confirmedBookingsCountLabel(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word =
        when {
            mod100 in TEENS -> "записей"
            mod10 == 1 -> "запись"
            mod10 in FEW -> "записи"
            else -> "записей"
        }
    return "$count $word"
}

private val TEENS = 11..14
private val FEW = 2..4
