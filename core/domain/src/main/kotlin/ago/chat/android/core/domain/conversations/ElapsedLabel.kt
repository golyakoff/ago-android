package ago.chat.android.core.domain.conversations

import java.time.Duration
import java.time.OffsetDateTime

/**
 * `26-14`: how long ago a conversation's own `createdAt` was, in the coarse buckets a row actually
 * renders ("Waiting time rather than arrival time" - `ago-console/src/workspace/ConversationList.tsx`'s
 * own doc comment, ported). A sealed type rather than a formatted `String` so `:app` decides the exact
 * Russian wording (plural forms included) - this function's whole job is the arithmetic, not the prose.
 *
 * `now` is a parameter, never [OffsetDateTime.now] called in here: the one clock read this screen makes
 * lives in `:app`'s own ticker (mirroring `ago-console`'s `useNow` hook), so this function stays a pure,
 * table-testable `(String, OffsetDateTime) -> ElapsedLabel` with no hidden dependency on wall-clock time
 * - the same reason the backend's non-negotiable rule 11 keeps `DateTime.UtcNow` out of everything but
 * `IClock`, read onto a client that has no such rule stated for it but no reason to be worse either.
 */
public sealed interface ElapsedLabel {
    public data class Minutes(
        val value: Long,
    ) : ElapsedLabel

    public data class Hours(
        val value: Long,
    ) : ElapsedLabel

    public data class Days(
        val value: Long,
    ) : ElapsedLabel

    /** `createdAt` did not parse. Rendered as an honest "unknown" - `ConversationList.tsx`'s own
     * `queueStartUnknown`/`queueWaitingSinceUnknown` precedent - never as "0 minutes ago". */
    public data object Unknown : ElapsedLabel
}

public fun elapsedSince(
    createdAt: String,
    now: OffsetDateTime,
): ElapsedLabel {
    val started = runCatching { OffsetDateTime.parse(createdAt) }.getOrNull() ?: return ElapsedLabel.Unknown
    val minutes = Duration.between(started, now).toMinutes().coerceAtLeast(0)

    return when {
        minutes < MINUTES_PER_HOUR -> ElapsedLabel.Minutes(minutes)
        minutes < MINUTES_PER_HOUR * HOURS_PER_DAY -> ElapsedLabel.Hours(minutes / MINUTES_PER_HOUR)
        else -> ElapsedLabel.Days(minutes / (MINUTES_PER_HOUR * HOURS_PER_DAY))
    }
}

private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
