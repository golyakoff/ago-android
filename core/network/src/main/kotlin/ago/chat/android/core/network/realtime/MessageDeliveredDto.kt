package ago.chat.android.core.network.realtime

/**
 * `26-42`: `Ago.Chat.Contracts.MessageDeliveredDto(ConversationId, MessageId, DeliveredAt)`, pushed to
 * every one of an assigned operator's own connections the moment the visitor's own widget acknowledges
 * one of that operator's messages (`ResolveMessageDeliveredTargetsHandler`, `ago-chat`) — the live half
 * of the second delivery tick, arriving on a bubble already on screen with no navigation away and back.
 * Hand-written the same "there is nothing to generate from" precedent [MessageDto]/[ConversationAssignedDto]
 * already follow (`adr/0178`).
 *
 * Unscoped by construction, the same reason [ConversationAssignedDto] is: the server only ever sends
 * this to the operator who owns the delivered message's own conversation, so there is no "which
 * conversation is open" filter to apply before *receiving* it — [ThreadViewModel] is what filters by
 * the conversation actually open on screen before acting on one, since a push can in principle arrive
 * for a conversation this operator is assigned to but not currently looking at.
 */
public data class MessageDeliveredDto(
    val conversationId: String,
    val messageId: String,
    val deliveredAt: String,
)
