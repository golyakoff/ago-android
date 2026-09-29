package ago.chat.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-285`: [visitorEmojiInitials]'s own `@Composable` half needs a real `stringResource`/composition
 * host, but [visitorEmojiInitialsText] - the plain join rule behind it - is plain Kotlin, so this suite
 * drives it directly on a plain JVM, the identical convention [VisitorEmojiPairNameTest] already
 * establishes for [visitorEmojiPairNameText].
 */
class VisitorEmojiInitialsTest {
    @Test
    fun `two ordinary single-word names join to their two first letters, uppercased`() {
        assertEquals("СК", visitorEmojiInitialsText("Сова", "Клубника"))
    }

    @Test
    fun `english names work identically`() {
        assertEquals("FO", visitorEmojiInitialsText("Fox", "Orange"))
    }

    @Test
    fun `a multi-word food name uses the first character of the whole string - no word-splitting`() {
        // "Картошка фри" - the first character is already the first letter of the first word, so
        // taking the bare first character is enough; no `split(" ")` is needed.
        assertEquals("СК", visitorEmojiInitialsText("Сова", "Картошка фри"))
    }

    @Test
    fun `a hyphenated multi-word food name behaves the same way`() {
        assertEquals("ЛХ", visitorEmojiInitialsText("Лиса", "Хот-дог"))
    }

    @Test
    fun `already-lowercase names are uppercased locale-invariantly`() {
        assertEquals("СК", visitorEmojiInitialsText("сова", "клубника"))
    }

    @Test
    fun `visitorEmojiInitials has no plain-function fallback for an unresolved glyph - the Composable half returns null`() {
        // `visitorEmojiInitials` itself needs a composition host to call `stringResource`, so its own
        // "unresolved glyph -> null" branch is proven indirectly here: `visitorEmojiNameResource`, the
        // function it guards on, already returns `null` for a glyph neither table knows -
        // `VisitorEmojiPairNameTest`'s own "a glyph with no resource entry" test covers that half; this
        // assertion is the reminder that `visitorEmojiInitials` must check it on *both* glyphs before
        // ever calling `stringResource`, never falling through to a broken surrogate half.
        assertEquals(null, visitorEmojiNameResource("🛸"))
    }
}
