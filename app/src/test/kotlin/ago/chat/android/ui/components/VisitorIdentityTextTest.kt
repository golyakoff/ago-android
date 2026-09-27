package ago.chat.android.ui.components

import ago.chat.android.core.domain.VisitorEmojiPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `26-203`: [resolvedVisitorEmojiPair] is [VisitorIdentityText]'s own plain decision — pulled out so a
 * plain JVM `test` can drive it directly without a composition host, the identical split
 * [VisitorEmojiPairNameTest] already exercises for [visitorEmojiPairNameText].
 */
class VisitorIdentityTextTest {
    @Test
    fun `both fields present resolves to the pair`() {
        assertEquals(VisitorEmojiPair("🦉", "🍓"), resolvedVisitorEmojiPair("🦉", "🍓"))
    }

    @Test
    fun `a missing food falls back to no pair, never a half-formed one`() {
        assertNull(resolvedVisitorEmojiPair("🦉", null))
    }

    @Test
    fun `a missing creature falls back to no pair, never a half-formed one`() {
        assertNull(resolvedVisitorEmojiPair(null, "🍓"))
    }

    @Test
    fun `neither field present is no pair`() {
        assertNull(resolvedVisitorEmojiPair(null, null))
    }
}
