package ago.chat.android.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-30`: the build-time check this item's own backlog asks for, in place of "a one-time manual
 * copy" — a second, independent copy-paste of the same 40 glyphs, hardcoded here as its own string
 * literal, asserting every one of them resolves to a real name in [VisitorEmojiNames.ru]. Mirrors
 * `ago-console/src/i18n/visitorEmojiNames.test.ts` exactly, including its own reasoning: this is
 * deliberately a **second** transcription, not a shared import from `VisitorEmojiNames.kt` itself — a
 * test that imports the same array it is checking would pass even if that array silently dropped a
 * member (or carried the wrong, variation-selector-mismatched glyph).
 *
 * Both this list and `VisitorEmojiNames.kt`'s own two source maps were copy-pasted independently, at
 * different times, from the same `ago-chat/src/Ago.Chat.Domain/VisitorEmojiDictionary.cs` — never
 * retyped from an OS emoji picker, and never from each other, and never from
 * `ago-console/src/i18n/visitorEmojiNames.ts` either.
 */
public class VisitorEmojiNamesTest {
    // Copied byte-for-byte from `VisitorEmojiDictionary.Creatures` (`ago-chat`) - do not retype.
    private val expectedCreatures =
        listOf(
            "🐔",
            "🐠",
            "🐳",
            "🐶",
            "🐱",
            "🐭",
            "🐹",
            "🐰",
            "🦊",
            "🐻",
            "🐼",
            "🐨",
            "🐯",
            "🦁",
            "🐮",
            "🐷",
            "🐸",
            "🐵",
            "🐦",
            "🦉",
        )

    // Copied byte-for-byte from `VisitorEmojiDictionary.Foods` (`ago-chat`) - do not retype.
    private val expectedFoods =
        listOf(
            "🍊",
            "🥝",
            "🌭",
            "🍕",
            "🍔",
            "🍟",
            "🌮",
            "🍣",
            "🍩",
            "🍪",
            "🍦",
            "🍎",
            "🍌",
            "🍇",
            "🍉",
            "🍓",
            "🍒",
            "🍑",
            "🥑",
            "🍍",
        )

    private val expectedGlyphs = expectedCreatures + expectedFoods

    @Test
    public fun `has exactly 40 expected glyphs in this test's own list - 20 creatures, 20 foods, no accidental duplicate`() {
        assertEquals(20, expectedCreatures.size)
        assertEquals(20, expectedFoods.size)
        assertEquals(40, expectedGlyphs.toSet().size)
    }

    @Test
    public fun `every expected glyph has a Russian name`() {
        for (glyph in expectedGlyphs) {
            assertTrue("missing Russian name for $glyph (VisitorEmojiDictionary.cs)", VisitorEmojiNames.ru[glyph]?.isNotBlank() == true)
        }
    }

    @Test
    public fun `carries no extra glyph beyond these 40 - a stale entry would go silently unused`() {
        assertEquals(expectedGlyphs.toSet(), VisitorEmojiNames.ru.keys)
    }

    @Test
    public fun `localizedEmojiName resolves a known glyph from the given table`() {
        assertEquals("Сова", localizedEmojiName("🦉"))
        assertEquals("Сова", localizedEmojiName("🦉", VisitorEmojiNames.ru))
    }

    @Test
    public fun `localizedEmojiName falls back to the raw glyph, never throwing, for a glyph missing from the table`() {
        assertEquals("🛸", localizedEmojiName("🛸"))
    }
}
