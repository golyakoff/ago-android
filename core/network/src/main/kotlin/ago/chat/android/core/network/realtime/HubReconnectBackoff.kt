package ago.chat.android.core.network.realtime

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Exponential backoff with full jitter — the identical formula `ago-console`'s
 * `realtime/protocol/backoff.ts` (`jitteredDelayMs`) and `ago-widget`'s own copy already use, ported
 * rather than re-derived (`26-13`'s own backlog item: "port the exact discipline"). `docs/adr/0010`
 * and this project's own edge-of-network reasoning are why jitter exists at all: every client of a
 * draining node reconnecting on the identical un-jittered schedule turns a routine rolling deploy
 * into a self-inflicted thundering herd.
 *
 * A plain, dependency-free class — no `com.microsoft.signalr` import anywhere in this file — so a
 * unit test can assert the exact delay *sequence* this produces (this item's own Done-when: "asserted
 * on the computed delay sequence, not on observed wall-clock timing") with no hub connection, no
 * coroutine and no real clock involved. `OperatorHubConnection` is the one caller that drives its own
 * reconnect loop from this computation — see that class's own doc comment for why that loop is
 * hand-rolled rather than handed to a library retry-policy hook: `com.microsoft.signalr` 9.0.5 has no
 * such hook at all.
 */
public class HubReconnectBackoff(
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 30_000,
    private val random: () -> Double = Math::random,
) {
    /**
     * The full-jitter delay for the given 1-based attempt number:
     * `cap = min(baseDelayMs * 2^(attemptNumber - 1), maxDelayMs)`, `delay = round(random() * cap)` —
     * the exact arithmetic both existing clients' `jitteredDelayMs` already use. Computed in `Double`
     * throughout (matching the original) rather than with integer shifts, so a very large
     * `attemptNumber` saturates at `maxDelayMs` instead of overflowing a `Long`.
     */
    public fun delayMillisFor(attemptNumber: Int): Long {
        require(attemptNumber >= 1) { "attemptNumber is the 1-based retry attempt; got $attemptNumber" }
        val cap = min(baseDelayMs.toDouble() * 2.0.pow(attemptNumber - 1), maxDelayMs.toDouble())
        return (random() * cap).roundToLong()
    }
}
