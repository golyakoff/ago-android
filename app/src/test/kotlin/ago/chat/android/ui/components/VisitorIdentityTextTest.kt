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

/**
 * `26-279` (A9): [resolveVisitorIdentityFallback] - [VisitorIdentityText]'s own three-arm decision, pulled
 * out for the identical "assert the fallback order without a composition host" reason
 * [VisitorIdentityTextTest] above already exercises for [resolvedVisitorEmojiPair]. The bug this item
 * fixes ("a client with no chat name currently renders the raw person-id as its title") is exactly the
 * case these tests pin down: the emoji pair still wins when present, a phone is consulted only once the
 * pair is absent, and a caller that passes no phone at all still falls through to the bare id, never a
 * new, silent behaviour change for the call sites this item did not touch.
 */
class ResolveVisitorIdentityFallbackTest {
    @Test
    fun `emoji pair present wins over a phone, unchanged from before this item`() {
        val fallback = resolveVisitorIdentityFallback("🦉", "🍓", phone = "+7 921 000-00-00")

        assertEquals(VisitorIdentityFallback.EmojiPair(VisitorEmojiPair("🦉", "🍓")), fallback)
    }

    @Test
    fun `no emoji pair but a phone falls back to the phone, never the bare id`() {
        val fallback = resolveVisitorIdentityFallback(null, null, phone = "+7 921 000-00-00")

        assertEquals(VisitorIdentityFallback.NoChatIdentity("+7 921 000-00-00"), fallback)
    }

    @Test
    fun `a half-formed pair is treated as absent, still falling back to the phone`() {
        val fallback = resolveVisitorIdentityFallback("🦉", null, phone = "+7 921 000-00-00")

        assertEquals(VisitorIdentityFallback.NoChatIdentity("+7 921 000-00-00"), fallback)
    }

    @Test
    fun `neither a pair nor a phone falls back to the bare id`() {
        val fallback = resolveVisitorIdentityFallback(null, null, phone = null)

        assertEquals(VisitorIdentityFallback.BareId, fallback)
    }

    @Test
    fun `a call site that passes no phone keeps its own pre-existing bare-id fallback`() {
        // `WorkerSlotsScreen`/`WorkerRecutScreen`/`PhoneRevealsReportScreen`/`RestrictedVisitorsScreen` -
        // out of `26-279`'s own scope, so a missing emoji pair must still resolve to the bare id exactly
        // as it did before this item, regardless of whether a phone happens to exist elsewhere on their
        // own row types.
        val fallback = resolveVisitorIdentityFallback(emojiCreature = null, emojiFood = null, phone = null)

        assertEquals(VisitorIdentityFallback.BareId, fallback)
    }
}
