package ago.chat.android.core.network.realtime

/**
 * `docs/architecture/realtime.md`'s Client protocol: "Server assigns `sequence`; clients order by it
 * and never by arrival time or client clock" — `CLAUDE.md`'s non-negotiable rule 6 ("message order is
 * guaranteed per conversation, never globally"), restated for this transport: one tracker exists per
 * subscribed conversation, never one shared across several.
 *
 * Monotonic on purpose — a duplicate or an out-of-order redelivery (the fan-out path's own local-echo
 * duplicate, or a stray replay from a reconnect's catch-up page) must never move the remembered "last
 * known sequence" backwards, or the next resume request would ask the server for messages already
 * rendered. Identical shape to `ago-console`'s `realtime/protocol/sequence.ts` `SequenceTracker`,
 * ported rather than re-derived.
 */
public class HubSequenceTracker {
    public var lastKnownSequence: Long? = null
        private set

    /**
     * Advances the tracked sequence if [sequence] is newer than what was already known. Returns
     * whether it advanced, so a caller can tell a genuine advance from a stale repeat.
     */
    public fun observe(sequence: Long): Boolean {
        val current = lastKnownSequence
        if (current == null || sequence > current) {
            lastKnownSequence = sequence
            return true
        }

        return false
    }

    /** Back to "nothing known yet" — used when subscribing to a *different* conversation, never
     * when resuming the same one after a reconnect (that path reads [lastKnownSequence], it does not
     * reset it). */
    public fun reset() {
        lastKnownSequence = null
    }
}
