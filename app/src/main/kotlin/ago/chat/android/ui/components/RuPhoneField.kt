package ago.chat.android.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * `26-305`/`docs/backlog/26-303-phone-input-research.md`: one reusable masked Russian phone field —
 * fixed, non-deletable `+7`, live `+7 (XXX) XXX-XX-XX` mask, paste normalisation and a completeness gate
 * — replacing the plain, unmasked `OutlinedTextField` each of the app's two editable phone-entry sites
 * ([ago.chat.android.bookings.ManualBookingScreen]'s phone step,
 * [ago.chat.android.thread.contactpanel.sections.ContactDetailsSection]'s contact editor) used to carry
 * separately. `26-303`'s own point was that the author's complaint is about *the control*, so it is
 * fixed once here and reused, not patched per screen.
 *
 * **[value]/[onValueChange] carry the canonical form** — blank while nothing has been typed, otherwise
 * the fixed `+7` followed by however many of the 10 national digits have been entered so far. A caller's
 * own state (a `ViewModel` field, an edit draft) is therefore already what a search/submit/save call
 * sends the server: no separate "convert to E.164" step, and the auto-search/submit paths in
 * `ManualBookingViewModel` need no change at all to start sending a clean number — they already forward
 * `wizard.phone`/`editDraft` verbatim, which is now always canonical because this composable is.
 * [isRuPhoneComplete] is the gate a caller uses instead of `isNotBlank()`, since a fixed `+7` with one
 * digit typed is already non-blank but nowhere near dialable.
 *
 * **Hand-rolled, not a library.** A single fixed national mask is a normalise function plus one
 * `VisualTransformation` with correct offset mapping — `docs/backlog/26-303-phone-input-research.md` §5
 * sizes it at roughly 40 lines and judges a masking dependency not worth its transitive surface for one
 * format we would use under 5% of (project rule: no new package without saying what it replaces). The
 * fiddly part — the caret staying put as digits, then punctuation, are inserted ahead of it — is
 * [maskedOffsetForDigitCount]/[digitCountForMaskedOffset] below, exercised directly by this file's own
 * unit tests rather than only indirectly through the field.
 *
 * @param value the canonical value described above.
 * @param onValueChange receives the new canonical value on every edit (typed digit, deletion, or a
 * paste already run through [normalizeRuNationalDigits]).
 * @param autoFocus requests focus once, the first time this composable enters composition — the
 * reference control's own "focused with the numeric keyboard already up" trait
 * (`docs/backlog/26-303-phone-input-research.md` §1).
 */
@Composable
public fun RuPhoneField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    OutlinedTextField(
        value = ruNationalDigits(value),
        onValueChange = { raw -> onValueChange(canonicalRuPhone(normalizeRuNationalDigits(raw))) },
        modifier = modifier.focusRequester(focusRequester),
        enabled = enabled,
        label = label?.let { text -> { Text(text = text) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        visualTransformation = RuPhoneVisualTransformation,
    )
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}

/** True once [value] — the canonical form [RuPhoneField] hands its caller — carries all
 * [NATIONAL_DIGIT_COUNT] national digits, i.e. is `+7` followed by exactly 10 digits. Callers gate a
 * forward action (search, submit, save) on this, never on `isNotBlank()`. */
public fun isRuPhoneComplete(value: String): Boolean = ruNationalDigits(value).length == NATIONAL_DIGIT_COUNT

/** The national digits [RuPhoneField] is currently editing, recovered from whatever [value] holds — its
 * own canonical `+7…` output most of the time, but also a value seeded from elsewhere (a server-formatted
 * string, an edit draft) that may or may not already carry the `+7` this field owns. A literal `+7` lead
 * is stripped first, so the ambiguity [normalizeRuNationalDigits] otherwise resolves by digit-counting
 * alone never mis-fires on our own output (`+7` + 3 digits is 4 digits total after stripping the `+`,
 * nowhere near the 11 that heuristic needs to suspect a leading country digit). */
internal fun ruNationalDigits(value: String): String = normalizeRuNationalDigits(value.removePrefix(RU_PREFIX))

/**
 * Keeps digits only, drops one leading `8` or `7` country marker when the run is longer than
 * [NATIONAL_DIGIT_COUNT] digits, and caps at [NATIONAL_DIGIT_COUNT] — the paste-normalisation behaviour
 * `docs/backlog/26-303-phone-input-research.md` names as the reference control's single most
 * user-visible trait: `89211234567`, `+7 921 123-45-67`, `7 9211234567` and `9211234567` all collapse to
 * the same 10-digit `9211234567`. Also runs on every keystroke, not only a paste — a single typed digit
 * is just as much "raw text that might contain punctuation" as a pasted block, so there is no separate
 * paste-only code path to keep in sync.
 */
internal fun normalizeRuNationalDigits(raw: String): String {
    var digits = raw.filter(Char::isDigit)
    if (digits.length > NATIONAL_DIGIT_COUNT && (digits.startsWith("8") || digits.startsWith("7"))) {
        digits = digits.drop(1)
    }
    return digits.take(NATIONAL_DIGIT_COUNT)
}

/** [RuPhoneField]'s own public value shape: blank while [nationalDigits] is empty, otherwise the fixed
 * [RU_PREFIX] followed by [nationalDigits] verbatim (complete only once there are
 * [NATIONAL_DIGIT_COUNT] of them — partial values are valid canonical values too, just incomplete
 * ones). */
internal fun canonicalRuPhone(nationalDigits: String): String = if (nationalDigits.isEmpty()) "" else RU_PREFIX + nationalDigits

/**
 * Renders [digits] (0-10, already normalised) as `+7 (XXX) XXX-XX-XX`, growing one group at a time —
 * closing punctuation for a group appears the instant that group is full, not only once the next group's
 * first digit arrives, so typing the 3rd/6th/8th digit immediately shows the `)`/`-`/`-` that follows it,
 * matching the reference control's own progressive formatting
 * (`docs/backlog/26-303-phone-input-research.md` §1: "formats as you type").
 */
internal fun maskRuNational(digits: String): String =
    buildString {
        append(RU_PREFIX)
        append(' ')
        if (digits.isEmpty()) return@buildString
        append('(')
        append(digits.take(3))
        if (digits.length >= 3) {
            append(')')
            append(' ')
        }
        if (digits.length > 3) append(digits.substring(3, minOf(6, digits.length)))
        if (digits.length >= 6) append('-')
        if (digits.length > 6) append(digits.substring(6, minOf(8, digits.length)))
        if (digits.length >= 8) append('-')
        if (digits.length > 8) append(digits.substring(8, minOf(NATIONAL_DIGIT_COUNT, digits.length)))
    }

/** The masked-string offset immediately after the [count]-th national digit ( `0` = right after the
 * fixed `+7 ` lead, with no digits typed yet). Depends only on how many digits precede that point, not
 * their values, so a run of `'0'`s stands in for the real digits — [maskRuNational]'s own punctuation
 * placement is a pure function of digit *count*. [RuPhoneVisualTransformation] uses this for
 * `originalToTransformed`, and [digitCountForMaskedOffset] scans over it in the other direction. */
internal fun maskedOffsetForDigitCount(count: Int): Int = maskRuNational("0".repeat(count.coerceIn(0, NATIONAL_DIGIT_COUNT))).length

/** The inverse of [maskedOffsetForDigitCount]: how many national digits (0..[totalDigits]) precede a
 * given offset into the masked string. A tap that lands inside punctuation (between the digit whose
 * group just closed and the one after it) snaps forward to the next digit slot, the same direction a
 * masked field's own inserted characters "push" the caret when typing through them. */
internal fun digitCountForMaskedOffset(
    maskedOffset: Int,
    totalDigits: Int,
): Int {
    for (count in 0..totalDigits) {
        if (maskedOffsetForDigitCount(count) >= maskedOffset) return count
    }
    return totalDigits
}

/** [RuPhoneField]'s own `VisualTransformation` — a stateless singleton since [filter] is a pure function
 * of its input text, so there is nothing per-instance to recreate across recompositions. */
internal object RuPhoneVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val offsetMapping =
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = maskedOffsetForDigitCount(offset.coerceIn(0, digits.length))

                override fun transformedToOriginal(offset: Int): Int = digitCountForMaskedOffset(offset, digits.length)
            }
        return TransformedText(AnnotatedString(maskRuNational(digits)), offsetMapping)
    }
}

// A Russian mobile number's national part is exactly 10 digits, the mask's own `XXX XXX-XX-XX`.
private const val NATIONAL_DIGIT_COUNT = 10

// The fixed, non-deletable country prefix this field always shows — a notation, not language-dependent
// text, so (like `ConfirmedBookingsScreen`'s own em-dash placeholder) it is a literal, never a string
// resource.
private const val RU_PREFIX = "+7"
