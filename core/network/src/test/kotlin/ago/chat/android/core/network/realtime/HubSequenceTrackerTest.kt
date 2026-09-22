package ago.chat.android.core.network.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `CLAUDE.md` rule 6 / `docs/architecture/realtime.md`: ordering comes from the server-assigned
 * `sequence`, and the remembered cursor must never move backwards on a duplicate or a stray
 * out-of-order redelivery. */
class HubSequenceTrackerTest {
    @Test
    fun `starts with nothing known`() {
        assertNull(HubSequenceTracker().lastKnownSequence)
    }

    @Test
    fun `advances on a strictly newer sequence`() {
        val tracker = HubSequenceTracker()

        assertTrue(tracker.observe(5))
        assertEquals(5L, tracker.lastKnownSequence)

        assertTrue(tracker.observe(6))
        assertEquals(6L, tracker.lastKnownSequence)
    }

    @Test
    fun `never moves backwards on a duplicate or an out-of-order replay`() {
        val tracker = HubSequenceTracker()
        tracker.observe(10)

        assertFalse("an exact repeat does not advance", tracker.observe(10))
        assertEquals(10L, tracker.lastKnownSequence)

        assertFalse("an older sequence does not move the cursor backwards", tracker.observe(3))
        assertEquals(10L, tracker.lastKnownSequence)
    }

    @Test
    fun `reset forgets what was known`() {
        val tracker = HubSequenceTracker()
        tracker.observe(42)

        tracker.reset()

        assertNull(tracker.lastKnownSequence)
    }
}
