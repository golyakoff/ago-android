package ago.chat.android.core.domain.conversations

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationOrderingTest {
    @Test
    fun `oldest createdAt sorts first`() {
        val newer = summary(id = "newer", createdAt = "2026-09-22T10:00:00Z")
        val older = summary(id = "older", createdAt = "2026-09-22T09:00:00Z")

        assertEquals(listOf(older, newer), oldestFirst(listOf(newer, older)))
    }

    @Test
    fun `a row whose createdAt does not parse sorts last rather than throwing`() {
        val broken = summary(id = "broken", createdAt = "not-a-timestamp")
        val real = summary(id = "real", createdAt = "2026-09-22T09:00:00Z")

        assertEquals(listOf(real, broken), oldestFirst(listOf(broken, real)))
    }

    @Test
    fun `an empty list sorts to an empty list`() {
        assertEquals(emptyList<ConversationSummary>(), oldestFirst(emptyList()))
    }

    private fun summary(
        id: String,
        createdAt: String,
    ) = ConversationSummary(
        conversationId = id,
        visitorId = "visitor-$id",
        emojiCreature = null,
        emojiFood = null,
        visitorName = null,
        createdAt = createdAt,
        operatorUnreadCount = 0,
    )
}
