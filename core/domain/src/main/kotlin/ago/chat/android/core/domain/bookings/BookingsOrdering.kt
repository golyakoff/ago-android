package ago.chat.android.core.domain.bookings

import java.time.OffsetDateTime

/**
 * `26-48`'s own Done-when: "sees the tenant's real pending bookings, oldest deadline first" — the
 * identical `sortedWith(compareBy(nullsLast()) { ... })` shape
 * [ago.chat.android.core.domain.conversations.oldestFirst] already establishes for the conversation
 * queue, over [PendingBooking.confirmationDeadline] rather than a conversation's own `createdAt`. The
 * deadline, not the booking's own `startsAt`, is what an operator triages on here — the queue's whole
 * reason to exist is "which of these auto-confirms soonest", not "which appointment happens soonest".
 *
 * A row whose `confirmationDeadline` fails to parse sorts last rather than throwing or being dropped —
 * the same "never invented, rendered honestly" posture [oldestFirst] already takes for a malformed
 * `createdAt`.
 */
public fun oldestDeadlineFirst(bookings: List<PendingBooking>): List<PendingBooking> =
    bookings.sortedWith(compareBy(nullsLast()) { parseOrNull(it.confirmationDeadline) })

private fun parseOrNull(confirmationDeadline: String): OffsetDateTime? =
    runCatching { OffsetDateTime.parse(confirmationDeadline) }.getOrNull()
