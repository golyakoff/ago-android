package ago.chat.android.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A plain JVM unit test — no Android test runner, no Robolectric, same reasoning as `ShortIdTest`
 * (`ago-android/docs/architecture.md`, "Testing"). Covers `26-10`'s own Done-when cases: both emoji
 * present with no name, a name present, no emoji pair at all (the pre-column visitor, short code
 * alone with no gap), and a name containing a space. `26-68`'s own section below covers the
 * newly-possible case of the *id* being the absent part instead.
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

    // ------------------------------------------------------------------------------------ `26-68`
    // The id itself can now be the absent part - a restored thread with no matching queue row yet
    // (`ago.chat.android.shell.ConversationsTabHost`) - joining the pair and the name, which were
    // already nullable. `visitorId` stays `null` on `parts`, never a substituted value, and the text
    // form drops the id with no stray trailing space where it would have gone - the identical rule
    // every other absent part already follows above.

    @Test
    public fun `visitorId on the parts is null, never substituted, when the id is not known`() {
        val parts = visitorDisplayPrefixParts("🦉", "🍓", "Иван Иванов", null)
        assertNull(parts.visitorId)
    }

    @Test
    public fun `renders the pair and the name with no trailing space when only the id is absent`() {
        val parts = visitorDisplayPrefixParts("🦉", "🍓", "Иван Иванов", null)
        assertEquals("🦉🍓 Иван Иванов", visitorDisplayPrefixText(parts))
    }

    @Test
    public fun `renders the pair alone with no trailing space when the name and the id are both absent`() {
        val parts = visitorDisplayPrefixParts("🦉", "🍓", null, null)
        assertEquals("🦉🍓", visitorDisplayPrefixText(parts))
    }

    @Test
    public fun `renders an empty string, never a blank placeholder, when nothing at all is known`() {
        val parts = visitorDisplayPrefixParts(null, null, null, null)
        assertEquals("", visitorDisplayPrefixText(parts))
    }

    @Test
    public fun `displayName is unaffected by an absent id`() {
        val withoutId = visitorDisplayPrefixParts("🦊", "🍊", null, null)
        val withId = visitorDisplayPrefixParts("🦊", "🍊", null, visitorId)
        assertEquals(withId.displayName, withoutId.displayName)
        assertEquals("Лиса · Апельсин", withoutId.displayName)
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

    // ------------------------------------------------------------------------------------ `26-30`

    @Test
    public fun `displayName is the emoji pair's localized fallback label when no name is known`() {
        val parts = visitorDisplayPrefixParts("🦊", "🍊", null, visitorId)
        assertEquals("Лиса · Апельсин", parts.displayName)
    }

    @Test
    public fun `displayName is the real name, not the fallback, when a name is known`() {
        val parts = visitorDisplayPrefixParts("🦊", "🍊", "Иван Иванов", visitorId)
        assertEquals("Иван Иванов", parts.displayName)
    }

    @Test
    public fun `displayName is null when there is neither a name nor a pair`() {
        val parts = visitorDisplayPrefixParts(null, null, null, visitorId)
        assertNull(parts.displayName)
    }

    @Test
    public fun `visitorName itself stays the real name only, provably unaffected by the fallback`() {
        val named = visitorDisplayPrefixParts("🦊", "🍊", "Иван Иванов", visitorId)
        assertEquals("Иван Иванов", named.visitorName)

        val nameless = visitorDisplayPrefixParts("🦊", "🍊", null, visitorId)
        assertNull("visitorName - unlike displayName - never carries the fallback label", nameless.visitorName)
    }

    @Test
    public fun `visitorFallbackLabel renders creature then food, localized and joined by a middle dot`() {
        assertEquals("Сова · Клубника", visitorFallbackLabel(VisitorEmojiPair("🦉", "🍓")))
    }

    @Test
    public fun `visitorFallbackLabel is null, never a lone dot, when there is no pair`() {
        assertNull(visitorFallbackLabel(null))
    }
}
