package ago.chat.android.core.network.realtime

/**
 * `26-14`: `Ago.Chat.Contracts.ConversationAssignedDto`, pushed to a newly-assigned operator's own
 * connections as `"ConversationAssigned"` — `4-02`'s automatic engine and `23-04`'s manual claim alike
 * (`AssignConversationHandler` raises the identical `ConversationAssigned` domain event either way).
 * `ago-console`'s `operatorConnection.ts` is the same hand-written-DTO precedent [MessageDto] already
 * follows: `adr/0178` — "there is nothing to generate from".
 *
 * Carries no visitor and no conversation summary — `WorkspaceLayout.tsx`'s own doc comment on this
 * exact DTO explains why: "the queue row that knows the visitor has not been fetched yet". This screen
 * makes the identical choice the console does: a push here is read as "something changed, go ask the
 * queue again", never assembled into a row by hand from these three fields.
 */
public data class ConversationAssignedDto(
    val conversationId: String,
    val operatorId: String,
    val assignedAt: String,
)
