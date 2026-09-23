package ago.chat.android.core.domain.conversations

/**
 * `26-40`: `Ago.Chat.Contracts.ConversationSummaryDto.State`'s wire spelling
 * (`Ago.Chat.Domain.ConversationState`'s own member names), classified into the handful of cases this
 * app ever has cause to render — the identical "the classification is a pure, testable function; the
 * prose belongs to `:app`" split [ElapsedLabel]/[elapsedSince] already establish for elapsed time.
 * [ConversationSummary.state] carries the raw wire string; a screen reads it through
 * [conversationStateLabel] rather than comparing string literals at the call site.
 *
 * `Pending` is excluded from every operator-facing read server-side (`Ago.Chat.Domain.ConversationState`'s
 * own remarks: a conversation the visitor has never written into), so no row this app ever holds should
 * carry it — kept as a real arm regardless, since the wire vocabulary genuinely has that name and a
 * client that silently folded it into [Unknown] would hide a server-side change that started sending it.
 */
public sealed interface ConversationStateLabel {
    public data object Pending : ConversationStateLabel

    public data object Waiting : ConversationStateLabel

    public data object Assigned : ConversationStateLabel

    public data object Closed : ConversationStateLabel

    /** An empty or unrecognised spelling — a row that predates this field ([ConversationSummary.state]'s
     * own `""` default), or a wire value this client has not been taught yet. A screen renders no word
     * at all for this arm, never a guessed one — the same "unknown, not zero" posture
     * [ElapsedLabel.Unknown] already takes for a `createdAt` that fails to parse. */
    public data object Unknown : ConversationStateLabel
}

public fun conversationStateLabel(state: String): ConversationStateLabel =
    when (state) {
        "Pending" -> ConversationStateLabel.Pending
        "Waiting" -> ConversationStateLabel.Waiting
        "Assigned" -> ConversationStateLabel.Assigned
        "Closed" -> ConversationStateLabel.Closed
        else -> ConversationStateLabel.Unknown
    }
