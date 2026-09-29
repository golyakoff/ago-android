package ago.chat.android.ui.components

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-305`: [RuPhoneField]'s own plain-Kotlin half — normalisation, the canonical value shape, the mask,
 * and the offset mapping the mockup research (`docs/backlog/26-303-phone-input-research.md` §5) names as
 * "the one fiddly part". None of this needs a composition host or Robolectric (no Robolectric anywhere in
 * `app/src/test`, `VisitorEmojiPairNameTest`'s own doc comment states why), including
 * [androidx.compose.ui.text.input.VisualTransformation] itself: `AnnotatedString`/`TransformedText`/
 * `OffsetMapping` are plain Kotlin types with no Android framework dependency, so [RuPhoneVisualTransformation]
 * is exercised directly, end to end, the same as every pure function below it.
 */
class RuPhoneFieldTest {
    // ─── normalizeRuNationalDigits: paste + keystroke normalisation ───────────────────────────────────

    @Test
    fun `a leading 8 is dropped once the run is longer than 10 digits`() {
        assertEquals("9211234567", normalizeRuNationalDigits("89211234567"))
    }

    @Test
    fun `a leading 7 is dropped once the run is longer than 10 digits`() {
        assertEquals("9211234567", normalizeRuNationalDigits("79211234567"))
    }

    @Test
    fun `a pasted plus-7 with spaces and dashes normalises to the bare 10 digits`() {
        assertEquals("9211234567", normalizeRuNationalDigits("+7 921 123-45-67"))
    }

    @Test
    fun `a pasted number with a leading 7 and spaces, no plus, still normalises`() {
        assertEquals("9211234567", normalizeRuNationalDigits("7 9211234567"))
    }

    @Test
    fun `bare 10 digits pass through unchanged`() {
        assertEquals("9211234567", normalizeRuNationalDigits("9211234567"))
    }

    @Test
    fun `a short, incomplete run is never mistaken for a leading country digit`() {
        // Exactly the case `ruNationalDigits`'s own doc comment calls out: "921" is 3 digits, nowhere near
        // the length that would make the leading `9` look like a stripped-off country marker.
        assertEquals("921", normalizeRuNationalDigits("921"))
        assertEquals("7", normalizeRuNationalDigits("7"))
        assertEquals("78", normalizeRuNationalDigits("78"))
    }

    @Test
    fun `extra digits past 10 are rejected, not appended`() {
        assertEquals("9211234567", normalizeRuNationalDigits("921123456789"))
        // With a leading 8 too: strip the 8, then cap at 10.
        assertEquals("9211234567", normalizeRuNationalDigits("8921123456789"))
    }

    @Test
    fun `non-digit characters are stripped even with no leading country marker at all`() {
        assertEquals("9211234567", normalizeRuNationalDigits("(921) 123-45-67"))
    }

    // ─── canonical value shape + completeness ──────────────────────────────────────────────────────────

    @Test
    fun `canonicalRuPhone is blank for no digits and prefixed for any digits typed`() {
        assertEquals("", canonicalRuPhone(""))
        assertEquals("+79", canonicalRuPhone("9"))
        assertEquals("+7921123456", canonicalRuPhone("921123456"))
        assertEquals("+79211234567", canonicalRuPhone("9211234567"))
    }

    @Test
    fun `isRuPhoneComplete is false for blank, partial, and true only at exactly 10 national digits`() {
        assertFalse(isRuPhoneComplete(""))
        assertFalse(isRuPhoneComplete("+7"))
        assertFalse(isRuPhoneComplete("+792"))
        assertFalse(isRuPhoneComplete("+7921123456"))
        assertTrue(isRuPhoneComplete("+79211234567"))
    }

    @Test
    fun `ruNationalDigits round-trips canonicalRuPhone's own output at every length`() {
        for (length in 0..10) {
            val digits = "9211234567".take(length)
            assertEquals("round-trip broken at length $length", digits, ruNationalDigits(canonicalRuPhone(digits)))
        }
    }

    @Test
    fun `ruNationalDigits also recovers digits from a value that never went through this field`() {
        // A server-formatted seed value, or a draft from before this control existed - `ContactDetailEditor`'s
        // own `editDraft = detail.value` seeding is exactly this case.
        assertEquals("9001112233", ruNationalDigits("+7 900 111 22 33"))
        assertEquals("9211234567", ruNationalDigits("89211234567"))
    }

    // ─── maskRuNational: the live `+7 (XXX) XXX-XX-XX` formatting ──────────────────────────────────────

    @Test
    fun `maskRuNational grows one group at a time as digits arrive`() {
        assertEquals("+7 ", maskRuNational(""))
        assertEquals("+7 (9", maskRuNational("9"))
        assertEquals("+7 (92", maskRuNational("92"))
        assertEquals("+7 (921) ", maskRuNational("921"))
        assertEquals("+7 (921) 1", maskRuNational("9211"))
        assertEquals("+7 (921) 123-", maskRuNational("921123"))
        assertEquals("+7 (921) 123-4", maskRuNational("9211234"))
        assertEquals("+7 (921) 123-45-", maskRuNational("92112345"))
        assertEquals("+7 (921) 123-45-6", maskRuNational("921123456"))
        assertEquals("+7 (921) 123-45-67", maskRuNational("9211234567"))
    }

    // ─── offset mapping: the caret staying correct through the mask ────────────────────────────────────

    @Test
    fun `maskedOffsetForDigitCount lands right after the digit just typed, punctuation included`() {
        // 0 digits: caret sits right after the fixed "+7 " lead.
        assertEquals("+7 ".length, maskedOffsetForDigitCount(0))
        // 1 digit: "+7 (9" - caret after the "9".
        assertEquals("+7 (9".length, maskedOffsetForDigitCount(1))
        // 3 digits: "+7 (921) " - the closing paren+space already appeared.
        assertEquals("+7 (921) ".length, maskedOffsetForDigitCount(3))
        // 6 digits: "+7 (921) 123-" - the dash already appeared.
        assertEquals("+7 (921) 123-".length, maskedOffsetForDigitCount(6))
        // 8 digits: "+7 (921) 123-45-" - the second dash already appeared.
        assertEquals("+7 (921) 123-45-".length, maskedOffsetForDigitCount(8))
        // 10 digits: the whole thing.
        assertEquals("+7 (921) 123-45-67".length, maskedOffsetForDigitCount(10))
    }

    @Test
    fun `digitCountForMaskedOffset is the exact inverse of maskedOffsetForDigitCount`() {
        for (count in 0..10) {
            val offset = maskedOffsetForDigitCount(count)
            assertEquals("offset $offset (from count $count) did not map back", count, digitCountForMaskedOffset(offset, 10))
        }
    }

    @Test
    fun `a tap landing inside punctuation snaps forward to the next digit slot`() {
        // "+7 (921) 123-45-67": index 7 is the "1" inside "(921", i.e. between the 2nd and 3rd digit -
        // that one already lands on a digit. Index 8 is the ")" right after the 3rd digit closes its
        // group - a click there has nowhere "forward" to go other than count 3 itself (it is already at
        // the boundary), so this asserts the boundary itself, then a genuinely mid-punctuation offset.
        val masked = maskRuNational("9211234567")
        assertEquals("+7 (921) 123-45-67", masked)
        val closingParenIndex = masked.indexOf(')')
        // The offset immediately before ")" is still "3 digits done" (nothing after the 3rd digit yet);
        // landing exactly on/after the space that follows it is also still 3, since no 4th digit exists
        // until the next character.
        assertEquals(3, digitCountForMaskedOffset(closingParenIndex, 10))
        assertEquals(3, digitCountForMaskedOffset(closingParenIndex + 1, 10))
    }

    @Test
    fun `digitCountForMaskedOffset clamps to totalDigits for an offset past the end`() {
        assertEquals(5, digitCountForMaskedOffset(999, 5))
    }

    // ─── RuPhoneVisualTransformation: the composable's own filter(), end to end ────────────────────────

    @Test
    fun `filter renders the masked text and an offset mapping consistent with the pure functions`() {
        val transformed = RuPhoneVisualTransformation.filter(AnnotatedString("9211234567"))

        assertEquals("+7 (921) 123-45-67", transformed.text.text)
        for (original in 0..10) {
            assertEquals(maskedOffsetForDigitCount(original), transformed.offsetMapping.originalToTransformed(original))
        }
        val maskedLength = transformed.text.text.length
        for (offset in 0..maskedLength) {
            assertEquals(digitCountForMaskedOffset(offset, 10), transformed.offsetMapping.transformedToOriginal(offset))
        }
    }

    @Test
    fun `filter on an empty field still shows the fixed plus-7 lead`() {
        val transformed = RuPhoneVisualTransformation.filter(AnnotatedString(""))

        assertEquals("+7 ", transformed.text.text)
        // With no digits at all, offset 0 (original) is the only original offset there is - it maps to
        // right after the fixed lead, i.e. the end of "+7 ".
        assertEquals("+7 ".length, transformed.offsetMapping.originalToTransformed(0))
        assertEquals(0, transformed.offsetMapping.transformedToOriginal(0))
    }

    // ─── formatRuPhoneForDisplay: 26-307's own read-only counterpart to the mask above ─────────────────

    @Test
    fun `a canonical plus-7 number formats into the grouped display shape`() {
        assertEquals("+7 (916) 222-22-22", formatRuPhoneForDisplay("+79162222222"))
    }

    @Test
    fun `a bare 10 national digits with no plus also formats`() {
        assertEquals("+7 (921) 123-45-67", formatRuPhoneForDisplay("9211234567"))
    }

    @Test
    fun `domestic dialing spellings without a plus normalise the same as the mask's own input path`() {
        assertEquals("+7 (921) 123-45-67", formatRuPhoneForDisplay("89211234567"))
        assertEquals("+7 (921) 123-45-67", formatRuPhoneForDisplay("79211234567"))
    }

    @Test
    fun `a plus-7 number with existing punctuation or spaces still formats`() {
        assertEquals("+7 (921) 123-45-67", formatRuPhoneForDisplay("+7 921 123-45-67"))
    }

    @Test
    fun `a foreign number is never mangled into a fake plus-7, even at the same digit count`() {
        // The author's own real sample: Norway's +47 country code plus an 8-digit subscriber number runs
        // exactly 10 digits once the "+" is stripped - the identical length a bare Russian national number
        // would have. Only the explicit, different "+47" prefix (not "+7") tells the two apart; formatting
        // must never fall back to digit-counting once a real, differing country code is present.
        assertEquals("+4758655828", formatRuPhoneForDisplay("+4758655828"))
    }

    @Test
    fun `other foreign numbers pass through unchanged regardless of shape`() {
        assertEquals("+12025550123", formatRuPhoneForDisplay("+12025550123"))
        assertEquals("+442071234567", formatRuPhoneForDisplay("+442071234567"))
    }

    @Test
    fun `formatting an already-formatted number is idempotent`() {
        val formatted = formatRuPhoneForDisplay("+79162222222")
        assertEquals(formatted, formatRuPhoneForDisplay(formatted))
    }

    @Test
    fun `an incomplete or malformed value passes through unchanged`() {
        assertEquals("+7", formatRuPhoneForDisplay("+7"))
        assertEquals("+79211234", formatRuPhoneForDisplay("+79211234"))
        assertEquals("921", formatRuPhoneForDisplay("921"))
    }

    @Test
    fun `a masked server preview is never reformatted into a fake full number`() {
        // The dotted partial preview a reveal control still gates ("+7 ··· 08") - far fewer than 10 real
        // digits once the bullets are discarded, so it is exactly as unrecognisable as any other malformed
        // value, never coaxed into looking like a complete number.
        assertEquals("+7 ··· 08", formatRuPhoneForDisplay("+7 ··· 08"))
    }

    @Test
    fun `blank and empty values pass through unchanged`() {
        assertEquals("", formatRuPhoneForDisplay(""))
        assertEquals("   ", formatRuPhoneForDisplay("   "))
    }
}
