package ago.chat.android.core.network.realtime

/**
 * At-least-once delivery (`docs/architecture/realtime.md`'s Fan-out path, `CLAUDE.md` rule 5) means
 * this connection's own principal can see one message twice — once as an immediate local echo, once
 * again once the real fan-out delivery completes — and a reconnect's own catch-up page can re-deliver
 * a message already rendered before the drop. This is that dedupe, on the client that receives it.
 *
 * Identical shape to `ago-console`'s `realtime/protocol/dedup.ts` `SeenMessageIds`, ported rather than
 * re-derived. Bounded by a fixed capacity, not because a session is expected to survive that long
 * in memory, but because an operator can leave the app in the foreground for a full shift and nothing
 * here needs to remember a message id from hours ago.
 *
 * `@Synchronized`: [markSeen] runs both from a live push arriving on the SignalR client's own thread
 * and from a resume/join call running on a caller's coroutine — two real callers, not a
 * defensive habit.
 */
public class SeenHubIds(
    private val capacity: Int = 500,
) {
    private val seen = LinkedHashSet<String>()

    /** Returns true the first time an id is seen, false on every repeat. */
    @Synchronized
    public fun markSeen(id: String): Boolean {
        if (!seen.add(id)) {
            return false
        }

        if (seen.size > capacity) {
            val oldest = seen.iterator()
            oldest.next()
            oldest.remove()
        }

        return true
    }

    /** Forgets everything seen — used when subscribing to a *different* conversation. */
    @Synchronized
    public fun reset() {
        seen.clear()
    }
}
