package ago.chat.android.devices

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-18`: [decideAlert] proven against `alerts.ts`'s own `decideAlert.test.ts` shape - both conditions
 * required together for silence, and each alone is not enough (`docs/backlog/26-18-*.md`'s own Done-when:
 * "suppressed when open, not when backgrounded — both proven").
 */
class PushAlertPolicyTest {
    @Test
    fun `silent exactly when this conversation is open AND the app is in front`() {
        assertFalse(decideAlert(conversationId = "conv-1", openConversationId = "conv-1", appInForeground = true))
    }

    @Test
    fun `this conversation open but the app backgrounded still alerts`() {
        assertTrue(decideAlert(conversationId = "conv-1", openConversationId = "conv-1", appInForeground = false))
    }

    @Test
    fun `the app foregrounded but a DIFFERENT conversation open still alerts`() {
        assertTrue(decideAlert(conversationId = "conv-1", openConversationId = "conv-2", appInForeground = true))
    }

    @Test
    fun `nothing open at all, app foregrounded, still alerts`() {
        assertTrue(decideAlert(conversationId = "conv-1", openConversationId = null, appInForeground = true))
    }

    @Test
    fun `nothing open, app backgrounded, alerts`() {
        assertTrue(decideAlert(conversationId = "conv-1", openConversationId = null, appInForeground = false))
    }
}
