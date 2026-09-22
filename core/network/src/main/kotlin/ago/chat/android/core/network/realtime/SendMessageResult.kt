package ago.chat.android.core.network.realtime

/**
 * `26-15`: what `OperatorHubConnection.sendMessage`/`OperatorHubEvents.sendMessage` came back with —
 * a sealed result rather than a thrown exception, `ago-console`'s own `NotConnectedError`/
 * `SendOutcomeUnknownError`/plain-throw split (`operatorConnection.ts`), restated as data instead of
 * control flow so a `ThreadViewModel` can pattern-match it exhaustively without a `try`/`catch` around
 * every call site.
 *
 * The retry-safety rule these four arms exist to carry, restated once here rather than at every call
 * site: [NotConnected] is safe to retry with a **fresh** [ago.chat.android.core.network.realtime.newClientMessageId] —
 * nothing was sent at all. [OutcomeUnknown] is safe to retry only with the **same** `clientMessageId`
 * the failed attempt used — an invoke was genuinely in flight and may have landed, and
 * `Conversation.AddMessage`'s own dedup (`newClientMessageId`'s own doc comment) is what makes reusing
 * it safe rather than merely convenient.
 */
public sealed interface SendMessageResult {
    /** The server accepted it. [sequence] is `SendMessageAsync`'s own return value — not itself used
     * to render the message (the local echo pushed back over [OperatorHubEvents.messages] is), only
     * to confirm the pending send resolved. */
    public data class Sent(val sequence: Long) : SendMessageResult

    /** Nothing was sent — the connection was not `Connected` at the moment of the call. Safe to retry
     * once [OperatorHubEvents.state] reports `Connected` again, with a fresh `clientMessageId`. */
    public data object NotConnected : SendMessageResult

    /** An invoke was genuinely in flight when the connection dropped, so whether the server received
     * it is unknown — it may have, and simply lost the response. Safe to retry, but **only** with the
     * exact same `clientMessageId` the failed attempt used ([SendMessageResult]'s own doc comment). */
    public data class OutcomeUnknown(val cause: Throwable) : SendMessageResult

    /** The connection was healthy and the server definitively refused the send (a `HubException` —
     * a real business-level rejection, not a transport ambiguity). Retrying automatically would be
     * wrong: nothing about the connection changed, so a fresh attempt would fail the identical way. */
    public data class Refused(val message: String) : SendMessageResult
}
