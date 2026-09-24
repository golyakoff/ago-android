package ago.chat.android.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-18`: [SeenKeysWindow]'s own pure list arithmetic - no `DataStore`, no file, no coroutine, so every
 * case [DataStorePushMessageDedupeStoreTest]'s own doc comment says is deliberately *not* proven with real
 * file IO (several keys, capacity trimming) is proven here instead, at no risk of that suite's own
 * documented on-disk finding.
 */
class SeenKeysWindowTest {
    @Test
    fun `a key not already in the window is reported new`() {
        val result = SeenKeysWindow.apply(existing = emptyList(), key = "msg-1", capacity = 30)

        assertTrue(result.isNew)
        assertEquals(listOf("msg-1"), result.updated)
    }

    @Test
    fun `a key already in the window is reported as a duplicate, and the window is unchanged`() {
        val result = SeenKeysWindow.apply(existing = listOf("msg-1"), key = "msg-1", capacity = 30)

        assertFalse(result.isNew)
        assertEquals(listOf("msg-1"), result.updated)
    }

    @Test
    fun `two different keys are both new, appended in order`() {
        val first = SeenKeysWindow.apply(existing = emptyList(), key = "msg-1", capacity = 30)
        val second = SeenKeysWindow.apply(existing = first.updated, key = "msg-2", capacity = 30)

        assertTrue(second.isNew)
        assertEquals(listOf("msg-1", "msg-2"), second.updated)
    }

    @Test
    fun `the window is bounded - the oldest key is dropped once capacity is exceeded`() {
        var window = emptyList<String>()
        repeat(31) { index ->
            window = SeenKeysWindow.apply(existing = window, key = "msg-$index", capacity = 30).updated
        }

        assertEquals(30, window.size)
        assertFalse("the oldest key (msg-0) aged out", "msg-0" in window)
        assertTrue("the newest key is still there", "msg-30" in window)
    }

    @Test
    fun `a key that already aged out of the window is treated as new again`() {
        var window = emptyList<String>()
        repeat(31) { index -> window = SeenKeysWindow.apply(existing = window, key = "msg-$index", capacity = 30).updated }

        val result = SeenKeysWindow.apply(existing = window, key = "msg-0", capacity = 30)

        assertTrue("msg-0 is no longer in the bounded window, so it is indistinguishable from a fresh key", result.isNew)
    }
}
