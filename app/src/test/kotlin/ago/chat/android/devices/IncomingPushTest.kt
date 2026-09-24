package ago.chat.android.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `26-18`: [parseIncomingPush] against the real wire shapes `NotifyOperatorDevicesHandler`
 * (`ago-chat`) actually sends - `RuStorePushSenderTests`' own pinned JSON, restated on this side of the
 * wire rather than guessed at independently.
 */
class IncomingPushTest {
    @Test
    fun `an assignment payload - conversationId alone - parses as ConversationAssigned`() {
        val push = parseIncomingPush(mapOf("conversationId" to "conv-1"))

        assertEquals(IncomingPush.ConversationAssigned("conv-1"), push)
    }

    @Test
    fun `a message payload - conversationId plus messageId - parses as VisitorMessage`() {
        val push = parseIncomingPush(mapOf("conversationId" to "conv-1", "messageId" to "msg-1"))

        assertEquals(IncomingPush.VisitorMessage("conv-1"), push)
    }

    @Test
    fun `the English title body groupKey BuildData folds in are ignored, never read into the result`() {
        val push =
            parseIncomingPush(
                mapOf(
                    "conversationId" to "conv-1",
                    "title" to "New conversation assigned",
                    "body" to "Visitor abc12345 is waiting for you.",
                    "groupKey" to "ago-conversation-conv-1",
                ),
            )

        assertEquals(IncomingPush.ConversationAssigned("conv-1"), push)
    }

    @Test
    fun `a blank messageId does not count as present - still an assignment`() {
        val push = parseIncomingPush(mapOf("conversationId" to "conv-1", "messageId" to ""))

        assertEquals(IncomingPush.ConversationAssigned("conv-1"), push)
    }

    @Test
    fun `no conversationId at all is unrecognised, not a guess`() {
        assertNull(parseIncomingPush(mapOf("messageId" to "msg-1")))
    }

    @Test
    fun `a blank conversationId is unrecognised too`() {
        assertNull(parseIncomingPush(mapOf("conversationId" to "")))
    }

    @Test
    fun `an empty map is unrecognised`() {
        assertNull(parseIncomingPush(emptyMap()))
    }
}
