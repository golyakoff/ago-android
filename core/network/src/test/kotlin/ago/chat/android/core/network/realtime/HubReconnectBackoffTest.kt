package ago.chat.android.core.network.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This item's own Done-when: "the backoff is jittered — asserted on the computed delay sequence, not
 * on observed wall-clock timing." Every test below reads [HubReconnectBackoff.delayMillisFor] as a
 * pure function — no `Thread.sleep`, no hub connection, no coroutine.
 */
class HubReconnectBackoffTest {
    @Test
    fun `the exact delay sequence for a fixed random source`() {
        // random() pinned at the midpoint makes every cap's own delay exactly half of it - the
        // deterministic sequence this item's own Done-when asks for.
        val backoff = HubReconnectBackoff(baseDelayMs = 1_000, maxDelayMs = 30_000, random = { 0.5 })

        assertEquals("attempt 1: cap = 1_000 * 2^0 = 1_000", 500L, backoff.delayMillisFor(1))
        assertEquals("attempt 2: cap = 1_000 * 2^1 = 2_000", 1_000L, backoff.delayMillisFor(2))
        assertEquals("attempt 3: cap = 1_000 * 2^2 = 4_000", 2_000L, backoff.delayMillisFor(3))
        assertEquals("attempt 4: cap = 1_000 * 2^3 = 8_000", 4_000L, backoff.delayMillisFor(4))
        assertEquals("attempt 5: cap = 1_000 * 2^4 = 16_000", 8_000L, backoff.delayMillisFor(5))
        // attempt 6 would be 32_000 uncapped - this is the saturation point.
        assertEquals("attempt 6: cap saturates at maxDelayMs = 30_000", 15_000L, backoff.delayMillisFor(6))
        assertEquals("attempt 20: still saturated, never overflows", 15_000L, backoff.delayMillisFor(20))
    }

    @Test
    fun `random at the extremes gives the cap's own bounds exactly`() {
        val backoff = HubReconnectBackoff(baseDelayMs = 1_000, maxDelayMs = 30_000, random = { 0.0 })
        assertEquals(0L, backoff.delayMillisFor(1))

        val alwaysMax = HubReconnectBackoff(baseDelayMs = 1_000, maxDelayMs = 30_000, random = { 1.0 })
        assertEquals(1_000L, alwaysMax.delayMillisFor(1))
        assertEquals(30_000L, alwaysMax.delayMillisFor(10))
    }

    @Test
    fun `with a real random source, every delay stays within its own cap and never negative`() {
        val backoff = HubReconnectBackoff(baseDelayMs = 1_000, maxDelayMs = 30_000)

        for (attempt in 1..10) {
            val cap = minOf(1_000.0 * Math.pow(2.0, (attempt - 1).toDouble()), 30_000.0)
            repeat(200) {
                val delay = backoff.delayMillisFor(attempt)
                assertTrue("delay $delay for attempt $attempt must be >= 0", delay >= 0)
                assertTrue("delay $delay for attempt $attempt must be <= cap $cap", delay <= cap.toLong() + 1)
            }
        }
    }

    @Test
    fun `attemptNumber must be 1-based`() {
        val backoff = HubReconnectBackoff()
        assertThrows(IllegalArgumentException::class.java) { backoff.delayMillisFor(0) }
        assertThrows(IllegalArgumentException::class.java) { backoff.delayMillisFor(-1) }
    }
}
