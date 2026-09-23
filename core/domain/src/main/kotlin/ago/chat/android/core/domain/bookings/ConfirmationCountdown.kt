package ago.chat.android.core.domain.bookings

import java.time.Duration
import java.time.OffsetDateTime

/**
 * `26-48`: how long until a booking's own [PendingBooking.confirmationDeadline] auto-confirms it —
 * the mockup's own «Подтвердится через N ч». The identical
 * [ago.chat.android.core.domain.conversations.ElapsedLabel] shape: a sealed type holding the
 * arithmetic's answer, not a formatted `String`, so `:app` decides the exact Russian wording (and, per
 * that type's own doc comment, `now` is a parameter here for the same reason — a pure,
 * table-testable `(String, OffsetDateTime) -> ConfirmationCountdown` with no hidden
 * [OffsetDateTime.now] read inside it).
 *
 * **Rounded up, never down.** A deadline 20 minutes away reads as "confirms in 1 hour", not "in 0
 * hours" — the reverse of [ago.chat.android.core.domain.conversations.elapsedSince]'s own floor,
 * because that function answers "how long has this been true" (a floor is honest there: 59 minutes
 * elapsed is not yet an hour) while this one answers "how long do you still have" (a floor would claim
 * more time remains than actually does, the wrong direction to round a deadline in).
 *
 * **Clamped at zero, never negative.** A deadline already passed — the sweep has not run yet, or
 * simply has not caught up — still reads as "confirms in 0 hours" rather than a negative number or a
 * second, distinct "overdue" state: [PendingBooking] deliberately does not carry the wire's own
 * `isOverdue` flag (that field's own doc comment), so this stays the one honest thing this screen says
 * about a deadline that has already passed, matching this item's own literal Scope (`docs/backlog/
 * 26-48-*.md`: "the deadline as «Подтвердится через N ч»") rather than inventing a second wording this
 * wave was never asked for.
 */
public sealed interface ConfirmationCountdown {
    public data class HoursRemaining(
        val value: Long,
    ) : ConfirmationCountdown

    /** `confirmationDeadline` did not parse. Rendered as an honest "unknown" — never as "0 hours". */
    public data object Unknown : ConfirmationCountdown
}

public fun confirmationCountdown(
    confirmationDeadline: String,
    now: OffsetDateTime,
): ConfirmationCountdown {
    val deadline = runCatching { OffsetDateTime.parse(confirmationDeadline) }.getOrNull() ?: return ConfirmationCountdown.Unknown
    val minutesRemaining = Duration.between(now, deadline).toMinutes().coerceAtLeast(0)
    val hoursRemaining = (minutesRemaining + MINUTES_PER_HOUR - 1) / MINUTES_PER_HOUR
    return ConfirmationCountdown.HoursRemaining(hoursRemaining)
}

private const val MINUTES_PER_HOUR = 60L
