package ago.chat.android.core.domain.analytics

/**
 * `26-57`: `ago-console`'s own `formatDurationSeconds` (`src/time/format.ts`), ported rather than
 * reinvented — the same `s`/`m`/`h` shape [ago.chat.android.core.domain.bookings.ConfirmationCountdown]
 * already shows this app is willing to restate a small pure function in Kotlin rather than share it
 * across the language boundary.
 *
 * **Locale-invariant on purpose, the same exemption the console source states for itself.** `s`/`m`/`h`
 * are bare unit letters, not Russian words any more than they are English ones — a single Latin letter
 * is not the kind of prose this app's "no untranslated string" discipline is aimed at, and every other
 * label this screen shows is a real `strings.xml` resource.
 *
 * Rounded to the nearest second, clamped at zero — a negative duration is not a real fact this endpoint
 * can produce, but clamping here (rather than trusting the wire) costs nothing and matches the
 * console's own `Math.max(0, ...)`.
 */
public fun formatDurationSeconds(totalSeconds: Double): String {
    val seconds = totalSeconds.let { if (it.isNaN()) 0L else Math.round(it) }.coerceAtLeast(0L)

    if (seconds < SECONDS_PER_MINUTE) {
        return "${seconds}s"
    }

    if (seconds < SECONDS_PER_HOUR) {
        val minutes = seconds / SECONDS_PER_MINUTE
        val remainingSeconds = seconds % SECONDS_PER_MINUTE
        return if (remainingSeconds == 0L) "${minutes}m" else "${minutes}m ${remainingSeconds}s"
    }

    val hours = seconds / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    return if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
