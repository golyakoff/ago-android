package ago.chat.android.core.network.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This item's own Done-when: "After a reconnect, history is re-read from the last `sequence`: a
 * message sent while disconnected appears exactly once, neither missing nor doubled." Every test
 * below drives [MessageSubscription] directly - the exact dedup/ordering mechanism
 * `OperatorHubConnection` wires to the real hub - with no `HubConnection`, no coroutine and no
 * network involved at all.
 */
class MessageSubscriptionTest {
    @Test
    fun `a message that arrived while disconnected is delivered exactly once after resume`() {
        val subscription = MessageSubscription()
        subscription.join("conv-1")

        // The initial JoinConversationAsync page: two messages already on screen as this call's own
        // return value, so they must not also flow through as if they were live pushes.
        subscription.markAlreadyDelivered(
            listOf(
                MessageDto(id = "m1", sequence = 1, conversationId = "conv-1"),
                MessageDto(id = "m2", sequence = 2, conversationId = "conv-1"),
            ),
        )
        assertEquals(2L, subscription.lastKnownSequence)

        // Disconnected; a visitor message (sequence 3) arrives while the socket is down. Nothing in
        // this class ever saw it live - it is discovered only by the reconnect's own catch-up page.
        val resumeDelta =
            subscription.acceptResumeDelta(
                listOf(MessageDto(id = "m3", sequence = 3, conversationId = "conv-1")),
            )
        assertEquals("the missed message is delivered exactly once", listOf("m3"), resumeDelta.map { it.id })
        assertEquals(3L, subscription.lastKnownSequence)

        // The fan-out path's own local-echo/redelivery can hand this connection the identical message
        // again as an ordinary live push after the resume completes - it must not be delivered twice.
        val duplicate = subscription.accept(MessageDto(id = "m3", sequence = 3, conversationId = "conv-1"))
        assertNull("the same message arriving again is dropped, not delivered a second time", duplicate)
    }

    @Test
    fun `a live push for a different conversation this operator is also assigned to is not delivered`() {
        val subscription = MessageSubscription()
        subscription.join("conv-1")

        val accepted = subscription.accept(MessageDto(id = "m9", sequence = 1, conversationId = "conv-2"))

        assertNull("belongs to a conversation other than the one open here", accepted)
        assertNull("an ignored message never advances this subscription's own cursor", subscription.lastKnownSequence)
    }

    @Test
    fun `a message naming no conversation at all is accepted regardless`() {
        // The widget's own single-conversation shape never sets conversationId; this connection must
        // not require every message to carry one.
        val subscription = MessageSubscription()
        subscription.join("conv-1")

        val accepted = subscription.accept(MessageDto(id = "m1", sequence = 1, conversationId = null))

        assertEquals("m1", accepted?.id)
    }

    @Test
    fun `joining a different conversation discards the previous one's own record`() {
        val subscription = MessageSubscription()
        subscription.join("conv-1")
        subscription.markAlreadyDelivered(listOf(MessageDto(id = "m1", sequence = 5, conversationId = "conv-1")))

        subscription.join("conv-2")

        assertNull("a fresh join starts from nothing", subscription.lastKnownSequence)
        assertTrue(
            "an id already seen under the previous conversation is not remembered under the new one",
            subscription.accept(MessageDto(id = "m1", sequence = 1, conversationId = "conv-2")) != null,
        )
    }

    @Test
    fun `leaving a conversation stops it from being resumed`() {
        val subscription = MessageSubscription()
        subscription.join("conv-1")

        subscription.leave()

        assertNull(subscription.conversationId)
    }

    @Test
    fun `a resume delta is delivered in the order the server sent it`() {
        val subscription = MessageSubscription()
        subscription.join("conv-1")

        val delta =
            subscription.acceptResumeDelta(
                listOf(
                    MessageDto(id = "m1", sequence = 5, conversationId = "conv-1"),
                    MessageDto(id = "m2", sequence = 6, conversationId = "conv-1"),
                    MessageDto(id = "m3", sequence = 7, conversationId = "conv-1"),
                ),
            )

        assertEquals(listOf("m1", "m2", "m3"), delta.map { it.id })
        assertEquals(7L, subscription.lastKnownSequence)
    }
}
