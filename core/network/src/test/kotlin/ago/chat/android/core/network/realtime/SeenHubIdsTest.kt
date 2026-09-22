package ago.chat.android.core.network.realtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenHubIdsTest {
    @Test
    fun `true the first time an id is seen, false on every repeat`() {
        val seen = SeenHubIds()

        assertTrue(seen.markSeen("a"))
        assertFalse(seen.markSeen("a"))
        assertFalse(seen.markSeen("a"))
    }

    @Test
    fun `distinct ids are each seen once`() {
        val seen = SeenHubIds()

        assertTrue(seen.markSeen("a"))
        assertTrue(seen.markSeen("b"))
        assertTrue(seen.markSeen("c"))
    }

    @Test
    fun `the oldest id is evicted once capacity is exceeded`() {
        val seen = SeenHubIds(capacity = 2)

        assertTrue(seen.markSeen("a"))
        assertTrue(seen.markSeen("b"))
        assertFalse("still within capacity, b is remembered", seen.markSeen("b"))

        assertTrue(seen.markSeen("c")) // over capacity: evicts the oldest, "a" -> remembers {b, c}
        assertTrue("a was evicted, so it reads as new again", seen.markSeen("a")) // evicts "b" -> remembers {c, a}

        assertFalse("c was never evicted", seen.markSeen("c"))
        assertFalse("a was just re-added", seen.markSeen("a"))
    }

    @Test
    fun `reset forgets everything`() {
        val seen = SeenHubIds()
        seen.markSeen("a")

        seen.reset()

        assertTrue(seen.markSeen("a"))
    }
}
