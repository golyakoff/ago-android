package ago.chat.android.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A plain JVM unit test — no Android test runner, no Robolectric, same reasoning as `ShortIdTest`
 * (`ago-android/docs/architecture.md`, "Testing"). Covers `26-10`'s own Done-when cases: both emoji
 * present with no name, a name present, no emoji pair at all (the pre-column visitor, short code
 * alone with no gap), and a name containing a space.
 */
public class VisitorDisplayPrefixTest {
    private val visitorId = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    private val shortCode = "3fa85f64"

    @Test
    public fun `renders the pair and the short code, trailing space included, when no name is known`() {
        val parts = visitorDisplayPrefixParts("🦉", "🍓", null, visitorId)
        assertEquals("🦉🍓 $shortCode", visitorDisplayPrefixText(parts))
    }

    @Test
    public fun `renders the pair, the name, and the short code when a name is known`() {
        val parts = visitorDisplayPrefixParts("🦉", "🍓", "Иван Иванов", visitorId)
        assertEquals("🦉🍓 Иван Иванов $shortCode", visitorDisplayPrefixText(parts))
    }

    @Test
    public fun `renders the short code alone, no gap, when there is no emoji pair at all`() {
        val parts = visitorDisplayPrefixParts(null, null, null, visitorId)
        assertEquals(shortCode, visitorDisplayPrefixText(parts))
    }

    @Test
    public fun `renders the name then the short code, no leading space, when the pair is absent but a name is known`() {
        val parts = visitorDisplayPrefixParts(null, null, "Иван Иванов", visitorId)
        assertEquals("Иван Иванов $shortCode", visitorDisplayPrefixText(parts))
    }

    @Test
    public fun `treats a name containing a space as one name, not two parts`() {
        val parts = visitorDisplayPrefixParts("🦉", "🍓", "Anna Maria", visitorId)
        assertEquals("🦉🍓 Anna Maria $shortCode", visitorDisplayPrefixText(parts))
    }

    @Test
    public fun `treats a blank name the same as an absent one`() {
        val parts = visitorDisplayPrefixParts("🦉", "🍓", "   ", visitorId)
        assertEquals("🦉🍓 $shortCode", visitorDisplayPrefixText(parts))
    }

    @Test
    public fun `is not a pair when only one half is present`() {
        assertNull(visitorEmojiPair("🦉", null))
        assertNull(visitorEmojiPair(null, "🍓"))
    }

    @Test
    public fun `is a pair only when both halves are present and non-blank`() {
        assertEquals(VisitorEmojiPair("🦉", "🍓"), visitorEmojiPair("🦉", "🍓"))
    }

    @Test
    public fun `is not a pair when a half is present but blank`() {
        assertNull(visitorEmojiPair("", "🍓"))
        assertNull(visitorEmojiPair("🦉", ""))
    }
}
