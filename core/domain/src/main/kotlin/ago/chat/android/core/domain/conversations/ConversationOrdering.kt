package ago.chat.android.core.domain.conversations

import java.time.OffsetDateTime

/**
 * `26-14`: both halves of the queue in the order an operator triages them - oldest first, exactly
 * `ago-console/src/workspace/attention.ts`'s own `oldestFirst` (`ConversationList.tsx`'s own doc
 * comment: "'waiting 14m' is the number an operator triages on"). Ordered by `createdAt` - the one
 * per-conversation timestamp the queue endpoint returns - and never by a `sequence`: `sequence` is
 * scoped to *one* conversation's own message stream (`concurrency.md`, non-negotiable rule 6 - "message
 * order is guaranteed per conversation, never globally"), so comparing conversation *A*'s sequence 5
 * against conversation *B*'s sequence 3 would not mean what it looks like it means. This screen's own
 * "ordered by sequence, never a timestamp" property lives at the transport layer instead - see
 * [ago.chat.android.core.domain.conversations] `MessageSubscription`'s own doc comment (`:core:network`)
 * - and is what stops a duplicate or reordered push from corrupting one conversation's own unread count;
 * it was never a promise that whole rows sort against each other by anything but this server-assigned,
 * per-conversation-agnostic `createdAt`.
 *
 * See `:core:network`'s `MessageSubscription`/`HubSequenceTracker` for where the `sequence` guarantee
 * actually lives - one conversation's own dedup/ordering, not this list's row order.
 *
 * A row whose `createdAt` fails to parse sorts last rather than throwing or being dropped - the same
 * "never invented, rendered honestly" posture [elapsedSince] takes for the identical failure.
 */
public fun oldestFirst(conversations: List<ConversationSummary>): List<ConversationSummary> =
    conversations.sortedWith(compareBy(nullsLast()) { parseOrNull(it.createdAt) })

private fun parseOrNull(createdAt: String): OffsetDateTime? = runCatching { OffsetDateTime.parse(createdAt) }.getOrNull()
