package ago.chat.android.core.domain.conversations

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationStateLabelTest {
    @Test
    fun `the wire's four real spellings map to their own arm`() {
        assertEquals(ConversationStateLabel.Pending, conversationStateLabel("Pending"))
        assertEquals(ConversationStateLabel.Waiting, conversationStateLabel("Waiting"))
        assertEquals(ConversationStateLabel.Assigned, conversationStateLabel("Assigned"))
        assertEquals(ConversationStateLabel.Closed, conversationStateLabel("Closed"))
    }

    @Test
    fun `a row that predates the field - the empty default - is Unknown, never a guess`() {
        assertEquals(ConversationStateLabel.Unknown, conversationStateLabel(""))
    }

    @Test
    fun `a spelling this client has not been taught is Unknown rather than a crash`() {
        assertEquals(ConversationStateLabel.Unknown, conversationStateLabel("SomeFutureState"))
    }
}
