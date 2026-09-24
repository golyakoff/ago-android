package ago.chat.android.ui.components

import ago.chat.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * `26-105`: `russianPluralStringResource`'s own `@Composable` wrapper needs a real `Configuration`/
 * `LocalConfiguration` host to drive, but its `localePluralResourceId` half — the whole fix — is plain
 * Kotlin, so this suite exercises that directly on a plain JVM, matching this project's own convention (no
 * Robolectric anywhere in `app/src/test`, see e.g. `SignInViewModelTest`'s doc comment).
 *
 * To prove the *rendered words*, not merely which resource id got picked, this test keeps a small local
 * mirror of the exact templates `values/strings.xml`/`values-en/strings.xml` hold for the elapsed-minutes
 * trio, then formats through it the same way [androidx.compose.ui.res.stringResource] would. A plain JVM
 * `test` cannot read the real Android resource system without Robolectric, so the alternative to this
 * mirror is not testing the rendered text at all — if a future edit changes those two files' wording, keep
 * this mirror in sync deliberately (a mismatch here means this test stops proving what it claims to,
 * silently, not that the app is broken).
 */
class ElapsedTextPluralsTest {
    private val russian = Locale.forLanguageTag("ru")
    private val english = Locale.forLanguageTag("en")

    // Mirrors `values/strings.xml`'s `conversation_row_elapsed_minutes_one/_few/_many`, verbatim.
    private val russianTemplates =
        mapOf(
            R.string.conversation_row_elapsed_minutes_one to "%1\$d минута",
            R.string.conversation_row_elapsed_minutes_few to "%1\$d минуты",
            R.string.conversation_row_elapsed_minutes_many to "%1\$d минут",
        )

    // Mirrors `values-en/strings.xml`'s own trio, verbatim - `_few` and `_many` are identical because
    // English has no third form to distinguish.
    private val englishTemplates =
        mapOf(
            R.string.conversation_row_elapsed_minutes_one to "%1\$d minute",
            R.string.conversation_row_elapsed_minutes_few to "%1\$d minutes",
            R.string.conversation_row_elapsed_minutes_many to "%1\$d minutes",
        )

    @Test
    fun `Russian keeps its own mod-10-mod-100 grammar, unchanged by this item`() {
        assertEquals("1 минута", render(1, russian, russianTemplates))
        assertEquals("2 минуты", render(2, russian, russianTemplates))
        assertEquals("5 минут", render(5, russian, russianTemplates))
        // Russian's own "one" bucket also fires for 21 (ends in 1, not 11) - correct for Russian, and the
        // exact case that used to leak into English before this item (see the test below).
        assertEquals("21 минута", render(21, russian, russianTemplates))
        assertEquals("11 минут", render(11, russian, russianTemplates))
        assertEquals("14 минут", render(14, russian, russianTemplates))
    }

    @Test
    fun `English now uses its own one-other grammar instead of always applying Russian's`() {
        assertEquals("1 minute", render(1, english, englishTemplates))
        assertEquals("2 minutes", render(2, english, englishTemplates))
        assertEquals("5 minutes", render(5, english, englishTemplates))
        // The bug `26-105` fixes: the old, locale-blind selection reused Russian's own "one" bucket for
        // 21 and rendered "21 minute" in English. CLDR's English rule has only one/other, so 21 must fall
        // to "other" ("21 minutes") the same as 11.
        assertEquals("21 minutes", render(21, english, englishTemplates))
        assertEquals("11 minutes", render(11, english, englishTemplates))
    }

    private fun render(
        count: Long,
        locale: Locale,
        templates: Map<Int, String>,
    ): String {
        val resId =
            localePluralResourceId(
                count = count,
                locale = locale,
                one = R.string.conversation_row_elapsed_minutes_one,
                few = R.string.conversation_row_elapsed_minutes_few,
                many = R.string.conversation_row_elapsed_minutes_many,
            )
        return String.format(Locale.ROOT, templates.getValue(resId), count)
    }
}
