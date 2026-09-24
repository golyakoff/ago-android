package ago.chat.android.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `26-18`: [DefaultOpenConversationTracker] - in particular the one case its own doc comment names as
 * the reason [conversationClosed] compares before clearing: a stale close from a conversation that is no
 * longer the current one must never clobber a newer open.
 */
class DefaultOpenConversationTrackerTest {
    @Test
    fun `nothing is open before the first conversationOpened call`() {
        assertNull(DefaultOpenConversationTracker().currentConversationId)
    }

    @Test
    fun `conversationOpened records the id`() {
        val tracker = DefaultOpenConversationTracker()

        tracker.conversationOpened("conv-1")

        assertEquals("conv-1", tracker.currentConversationId)
    }

    @Test
    fun `conversationClosed for the current id clears it`() {
        val tracker = DefaultOpenConversationTracker()
        tracker.conversationOpened("conv-1")

        tracker.conversationClosed("conv-1")

        assertNull(tracker.currentConversationId)
    }

    @Test
    fun `a stale close for a conversation that is no longer current does not clobber the newer open`() {
        val tracker = DefaultOpenConversationTracker()
        tracker.conversationOpened("conv-1")
        tracker.conversationOpened("conv-2")

        // `ThreadViewModel.open`'s own late backstop for the *first* conversation, arriving after a
        // second one has already opened - the race [DefaultOpenConversationTracker]'s own doc comment
        // names.
        tracker.conversationClosed("conv-1")

        assertEquals("conv-2 must still be reported open", "conv-2", tracker.currentConversationId)
    }
}
