package ago.chat.android.core.network.realtime

/**
 * `OperatorHubConnection`'s own record of what it is subscribed to server-side, and the single place
 * that decides — for one message at a time — whether it is new, belongs to the open conversation, and
 * where the "resume from `lastKnownSequence`" cursor currently sits. Pure and dependency-free on
 * purpose (no `com.microsoft.signalr` import here) so the property this item's own Done-when asks for
 * — "a message that arrived while disconnected shows up exactly once, neither missing nor doubled" —
 * is provable with no hub connection, no coroutine and no network at all
 * ([MessageSubscription]'s own test suite does exactly that).
 *
 * Mirrors `ago-console`'s `OperatorConnection` — `subscribedConversationId` plus its own
 * `sequenceTracker`/`seenMessageIds` pair — pulled out as its own class here rather than left as three
 * private fields on the connection holder, precisely so it can be tested without one.
 *
 * A hub connection that comes back up after a drop has *no* server-side group membership from its
 * previous life (`ago-console`'s own `5-16` finding, restated in `docs/architecture/realtime.md`), so
 * `OperatorHubConnection` is the one caller that replays this record — via [markAlreadyDelivered] on a
 * fresh `join`, or [acceptResumeDelta] on every reconnect.
 */
public class MessageSubscription {
    private val lock = Any()
    private val sequenceTracker = HubSequenceTracker()
    private val seenIds = SeenHubIds()

    /** The conversation this connection is currently subscribed to, or `null` when none is. */
    public var conversationId: String? = null
        private set

    /** What the next resume request should ask for — `null` means "nothing received yet", the exact
     * value `OperatorHub.JoinConversationAsync`'s own optional `lastKnownSequence` parameter expects
     * for that case. */
    public val lastKnownSequence: Long?
        get() = synchronized(lock) { sequenceTracker.lastKnownSequence }

    /** Subscribes to a new conversation, discarding whatever record the previous one left — a fresh
     * `JoinConversationAsync` call (no `lastKnownSequence`) starts from nothing, the same way
     * `ago-console`'s own `joinConversation` resets both trackers before calling it. */
    public fun join(conversationId: String) {
        synchronized(lock) {
            this.conversationId = conversationId
            sequenceTracker.reset()
            seenIds.reset()
        }
    }

    /** Stops routing pushes anywhere — called when the operator navigates away from a conversation.
     * Also what stops a later reconnect from re-joining a conversation nobody is looking at any more. */
    public fun leave() {
        synchronized(lock) { conversationId = null }
    }

    /**
     * Records `messages` — the initial page a fresh [join]'s own `JoinConversationAsync` call returned
     * — as already delivered, without emitting them a second time: the caller already has them as that
     * call's own return value. Advances the sequence tracker exactly as [accept] would.
     */
    public fun markAlreadyDelivered(messages: List<MessageDto>) {
        synchronized(lock) {
            for (message in messages) {
                sequenceTracker.observe(message.sequence)
                seenIds.markSeen(message.id)
            }
        }
    }

    /**
     * A reconnect's own catch-up page — every message strictly after the `lastKnownSequence` this
     * class handed the resume call — filtered through the identical dedup [accept] applies. Unlike
     * [markAlreadyDelivered], the result **is** meant to reach a live listener: the entire point of
     * resuming is that these arrived while the socket was down and nothing has shown them yet. Order
     * is preserved — the server already answers oldest-first (`realtime.md`) and this never reorders.
     */
    public fun acceptResumeDelta(messages: List<MessageDto>): List<MessageDto> = messages.mapNotNull(::accept)

    /**
     * One live push. Returns it if it should be delivered — belongs to the open conversation (or
     * names none at all) and has not been seen before — or `null` if it is for a different
     * conversation this operator is also assigned to, or a duplicate the fan-out path/a resume
     * already delivered once.
     */
    public fun accept(message: MessageDto): MessageDto? =
        synchronized(lock) {
            val messageConversationId = message.conversationId
            if (messageConversationId != null && messageConversationId != conversationId) {
                return@synchronized null
            }

            sequenceTracker.observe(message.sequence)
            if (seenIds.markSeen(message.id)) message else null
        }
}
