package ago.chat.android.ui.components

import ago.chat.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-116`: [visitorEmojiPairName]'s own `@Composable` half needs a real `stringResource`/composition
 * host to drive, but its two plain halves - [visitorEmojiNameResource] (glyph to resource id) and
 * [visitorEmojiPairNameText] (how two already-resolved names join) - are plain Kotlin, so this suite
 * exercises both directly on a plain JVM, matching this project's own convention (no Robolectric
 * anywhere in `app/src/test`; see `ElapsedTextPluralsTest`'s own doc comment for the identical reasoning
 * applied to `russianPluralStringResource`).
 *
 * To prove the *rendered words*, not merely which resource id got picked, this test keeps a small local
 * mirror of the exact values `values/strings.xml`/`values-en/strings.xml` hold for the two glyphs it
 * exercises, then resolves through it the same way [androidx.compose.ui.res.stringResource] would. A
 * plain JVM `test` cannot read the real Android resource system without Robolectric, so the alternative
 * to this mirror is not testing the rendered text at all - if a future edit changes those two files'
 * wording, keep this mirror in sync deliberately (a mismatch here means this test stops proving what it
 * claims to, silently, not that the app is broken).
 */
class VisitorEmojiPairNameTest {
    // Mirrors `values/strings.xml`'s own `visitor_emoji_name_owl`/`visitor_emoji_name_strawberry`,
    // verbatim.
    private val russianNames =
        mapOf(
            R.string.visitor_emoji_name_owl to "Сова",
            R.string.visitor_emoji_name_strawberry to "Клубника",
        )

    // Mirrors `values-en/strings.xml`'s own pair, verbatim.
    private val englishNames =
        mapOf(
            R.string.visitor_emoji_name_owl to "Owl",
            R.string.visitor_emoji_name_strawberry to "Strawberry",
        )

    // Copied byte-for-byte from `VisitorEmojiDictionary.Creatures` (`ago-chat`) - the same second,
    // independent transcription `VisitorEmojiNamesTest` already keeps for the Russian-only table, kept
    // here too so this test does not silently pass if a glyph ever goes missing from
    // `VisitorEmojiPairName.kt`'s own map without this file's author noticing.
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

    // Copied byte-for-byte from `VisitorEmojiDictionary.Foods` (`ago-chat`).
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

    @Test
    fun `known pair renders its localized name in Russian`() {
        val text = visitorEmojiPairNameText(resolve("🦉", russianNames), resolve("🍓", russianNames))

        assertEquals("Сова · Клубника", text)
    }

    @Test
    fun `the same pair renders its localized name in English`() {
        val text = visitorEmojiPairNameText(resolve("🦉", englishNames), resolve("🍓", englishNames))

        assertEquals("Owl · Strawberry", text)
    }

    @Test
    fun `a glyph with no resource entry falls back to the bare glyph, never a blank string`() {
        assertNull(visitorEmojiNameResource("🛸"))

        val text = visitorEmojiPairNameText(resolve("🛸", russianNames), resolve("🍓", russianNames))

        assertEquals("🛸 · Клубника", text)
    }

    @Test
    fun `every expected glyph - 20 creatures, 20 foods - has its own resource id`() {
        for (glyph in expectedCreatures + expectedFoods) {
            assertTrue("missing resource id for $glyph (VisitorEmojiDictionary.cs)", visitorEmojiNameResource(glyph) != null)
        }
    }

    @Test
    fun `has exactly 40 expected glyphs in this test's own list - no accidental duplicate`() {
        assertEquals(20, expectedCreatures.size)
        assertEquals(20, expectedFoods.size)
        assertEquals(40, (expectedCreatures + expectedFoods).toSet().size)
    }

    private fun resolve(
        glyph: String,
        names: Map<Int, String>,
    ): String = visitorEmojiNameResource(glyph)?.let { names.getValue(it) } ?: glyph
}
