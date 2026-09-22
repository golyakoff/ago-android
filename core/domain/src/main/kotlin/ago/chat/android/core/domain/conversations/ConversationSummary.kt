package ago.chat.android.core.domain.conversations

/**
 * `26-14`: one row of `GET /api/v1/conversations/queue` (`Ago.Chat.Contracts.ConversationSummaryDto`),
 * reduced to exactly the fields this item's own Scope names — a visitor to render through
 * `VisitorDisplayPrefix` (`26-10`), when the conversation started, and how many messages the operator
 * has not yet read. `state`/`operatorId`/`operatorName`/the attachment-grant fields are on the wire DTO
 * and are not carried here because nothing in this screen reads them; a later item that needs one grows
 * this type rather than this screen inventing a second copy of the wire shape, the same additive
 * discipline `MessageDto`'s own doc comment states for its module.
 *
 * `createdAt` is kept as the raw ISO-8601 string the wire sends, not parsed here: parsing needs a `now`
 * to measure against ([elapsedSince]), and a domain entity that carried its own "how long ago" would be
 * a value that changes without a new server answer arriving — exactly the "cache what a write decision
 * depends on" shape rule 8 warns about, read onto a display value this time rather than a write one.
 */
public data class ConversationSummary(
    public val conversationId: String,
    public val visitorId: String,
    public val emojiCreature: String?,
    public val emojiFood: String?,
    public val visitorName: String?,
    public val createdAt: String,
    public val operatorUnreadCount: Int,
)

/**
 * `GET /api/v1/conversations/queue`'s whole answer (`Ago.Chat.Contracts.OperatorQueueResponse`) — two
 * lists rather than one filterable list, for the identical reason that DTO's own doc comment gives:
 * "Waiting" and "AssignedToMe" answer genuinely different questions, and a screen showing both wants
 * them in one round trip.
 */
public data class ConversationQueue(
    public val waiting: List<ConversationSummary>,
    public val assignedToMe: List<ConversationSummary>,
)
