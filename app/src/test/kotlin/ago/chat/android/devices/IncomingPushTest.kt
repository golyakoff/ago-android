package ago.chat.android.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `26-18`/`26-86`: [parseIncomingPush] against the real wire shapes `NotifyOperatorDevicesHandler`
 * (`ago-chat`) actually sends - `RuStorePushSenderTests`' own pinned JSON, restated on this side of the
 * wire rather than guessed at independently.
 *
 * `26-81`'s own close: every case here now supplies an explicit `reason` key, because that is the
 * primary (and, since this item, only) discriminator - the earlier messageId-presence inference this
 * class used to fall back on is gone; a payload with no recognised `reason` is unrecognised, full stop,
 * regardless of which other keys it happens to carry.
 */
class IncomingPushTest {
    @Test
    fun `an assignment payload - conversationId plus reason assigned - parses as ConversationAssigned`() {
        val push = parseIncomingPush(mapOf("conversationId" to "conv-1", "reason" to "assigned"))

        assertEquals(IncomingPush.ConversationAssigned("conv-1"), push)
    }

    @Test
    fun `a message payload - conversationId, messageId, reason message - parses as VisitorMessage`() {
        val push = parseIncomingPush(mapOf("conversationId" to "conv-1", "messageId" to "msg-1", "reason" to "message"))

        assertEquals(IncomingPush.VisitorMessage("conv-1"), push)
    }

    /** `26-86`: the third kind - no `messageId`, no assignee, only `conversationId` and its own
     * `reason` - the identical no-extra-key shape the assignment kind already has. */
    @Test
    fun `a waiting payload - conversationId plus reason waiting - parses as ConversationWaiting`() {
        val push = parseIncomingPush(mapOf("conversationId" to "conv-1", "reason" to "waiting"))

        assertEquals(IncomingPush.ConversationWaiting("conv-1"), push)
    }

    @Test
    fun `the English title body groupKey BuildData folds in are ignored, never read into the result`() {
        val push =
            parseIncomingPush(
                mapOf(
                    "conversationId" to "conv-1",
                    "reason" to "assigned",
                    "title" to "New conversation assigned",
                    "body" to "Visitor abc12345 is waiting for you.",
                    "groupKey" to "ago-conversation-conv-1",
                ),
            )

        assertEquals(IncomingPush.ConversationAssigned("conv-1"), push)
    }

    /** `26-81`'s own fixed gap, made concrete: before this item, a `messageId`-less payload with an
     * unrecognised (or missing) `reason` would have silently misparsed as an assignment via the old
     * key-presence inference. Now it is simply unrecognised - nothing left to infer from. */
    @Test
    fun `a payload with no reason at all is unrecognised, not inferred from messageId presence`() {
        assertNull(parseIncomingPush(mapOf("conversationId" to "conv-1")))
    }

    @Test
    fun `an unrecognised reason value is unrecognised, not a guess`() {
        assertNull(parseIncomingPush(mapOf("conversationId" to "conv-1", "reason" to "something-a-future-version-added")))
    }

    @Test
    fun `no conversationId at all is unrecognised, not a guess`() {
        assertNull(parseIncomingPush(mapOf("reason" to "assigned")))
    }

    @Test
    fun `a blank conversationId is unrecognised too`() {
        assertNull(parseIncomingPush(mapOf("conversationId" to "", "reason" to "assigned")))
    }

    @Test
    fun `an empty map is unrecognised`() {
        assertNull(parseIncomingPush(emptyMap()))
    }
}
