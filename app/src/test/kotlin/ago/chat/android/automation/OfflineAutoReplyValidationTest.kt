package ago.chat.android.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `26-192`/`C5`: [validateOfflineAutoReplyDraft]'s own seven checks, in the exact order
 * `offlineAutoReplyValidation.ts#validateDraft` runs them - first problem wins. The load-bearing tests
 * here are the two console-parity oddities that are easy to get wrong from the spec alone: a wholly-blank
 * rule is dropped rather than flagged, and a rule's reply is measured *untrimmed* while its keyword and
 * the fallback are measured trimmed.
 */
class OfflineAutoReplyValidationTest {
    @Test
    fun `a disabled auto-reply with a blank fallback and no rules is sendable`() {
        assertNull(validateOfflineAutoReplyDraft(enabled = false, fallbackReply = "", rules = emptyList()))
    }

    @Test
    fun `an enabled auto-reply needs a non-blank fallback`() {
        val problem = validateOfflineAutoReplyDraft(enabled = true, fallbackReply = "   ", rules = emptyList())

        assertEquals(OfflineAutoReplyValidationProblem.FallbackRequired, problem)
    }

    @Test
    fun `an enabled auto-reply with a real fallback and no rules is sendable`() {
        assertNull(validateOfflineAutoReplyDraft(enabled = true, fallbackReply = "Мы вернёмся утром.", rules = emptyList()))
    }

    @Test
    fun `a fallback over the bound is too long`() {
        val problem = validateOfflineAutoReplyDraft(enabled = false, fallbackReply = "x".repeat(1001), rules = emptyList())

        assertEquals(OfflineAutoReplyValidationProblem.FallbackTooLong, problem)
    }

    @Test
    fun `a fallback at exactly the bound is fine`() {
        assertNull(validateOfflineAutoReplyDraft(enabled = false, fallbackReply = "x".repeat(1000), rules = emptyList()))
    }

    @Test
    fun `a wholly-blank rule is dropped, never flagged, even with an otherwise-invalid draft`() {
        val problem =
            validateOfflineAutoReplyDraft(
                enabled = false,
                fallbackReply = "",
                rules = listOf(AutoReplyRuleDraft(id = 1, keyword = "  ", reply = "  ")),
            )

        assertNull("a rule with both fields blank must never be reported as incomplete", problem)
    }

    @Test
    fun `more than the max meaningful rules is too many, blank rows not counted`() {
        val meaningful = (1..20).map { AutoReplyRuleDraft(id = it.toLong(), keyword = "k$it", reply = "r$it") }
        val withOneTooMany = meaningful + AutoReplyRuleDraft(id = 21, keyword = "k21", reply = "r21")
        val withBlankPadding = withOneTooMany + AutoReplyRuleDraft(id = 22, keyword = "", reply = "")

        assertNull("exactly 20 meaningful rules is fine", validateOfflineAutoReplyDraft(false, "", meaningful))
        assertEquals(
            "the 21st meaningful rule tips it over",
            OfflineAutoReplyValidationProblem.TooManyRules,
            validateOfflineAutoReplyDraft(false, "", withOneTooMany),
        )
        assertEquals(
            "a trailing blank row must not rescue an already-too-long list",
            OfflineAutoReplyValidationProblem.TooManyRules,
            validateOfflineAutoReplyDraft(false, "", withBlankPadding),
        )
    }

    @Test
    fun `a rule with a reply but a blank keyword needs a keyword`() {
        val problem =
            validateOfflineAutoReplyDraft(false, "", listOf(AutoReplyRuleDraft(id = 1, keyword = "  ", reply = "Возвраты...")))

        assertEquals(OfflineAutoReplyValidationProblem.RuleKeywordRequired, problem)
    }

    @Test
    fun `a rule with a keyword but a blank reply needs a reply, naming that rule's own keyword`() {
        val problem =
            validateOfflineAutoReplyDraft(false, "", listOf(AutoReplyRuleDraft(id = 1, keyword = " возврат ", reply = "  ")))

        assertEquals(OfflineAutoReplyValidationProblem.RuleReplyRequired("возврат"), problem)
    }

    @Test
    fun `a rule keyword over the bound is too long, measured trimmed`() {
        val problem =
            validateOfflineAutoReplyDraft(
                false,
                "",
                listOf(AutoReplyRuleDraft(id = 1, keyword = "  " + "k".repeat(65) + "  ", reply = "r")),
            )

        assertEquals(OfflineAutoReplyValidationProblem.RuleKeywordTooLong, problem)
    }

    @Test
    fun `a rule keyword at exactly the bound, once trimmed, is fine`() {
        assertNull(
            validateOfflineAutoReplyDraft(
                false,
                "",
                listOf(AutoReplyRuleDraft(id = 1, keyword = "  " + "k".repeat(64) + "  ", reply = "r")),
            ),
        )
    }

    @Test
    fun `a rule reply over the bound is too long, measured untrimmed - the console's own asymmetry`() {
        // Padded with leading/trailing spaces so the untrimmed length crosses the bound while the
        // trimmed length would not - `offlineAutoReplyValidation.ts` measures `rule.reply.length`
        // directly, never `rule.reply.trim().length`, unlike the keyword and the fallback.
        val paddedReply = " " + "r".repeat(999) + " "
        val problem = validateOfflineAutoReplyDraft(false, "", listOf(AutoReplyRuleDraft(id = 1, keyword = "k", reply = paddedReply)))

        assertEquals(OfflineAutoReplyValidationProblem.RuleReplyTooLong, problem)
    }

    @Test
    fun `a rule reply at exactly the bound is fine`() {
        assertNull(validateOfflineAutoReplyDraft(false, "", listOf(AutoReplyRuleDraft(id = 1, keyword = "k", reply = "r".repeat(1000)))))
    }

    @Test
    fun `the first problem among several wins, fallback checked before rules`() {
        val problem =
            validateOfflineAutoReplyDraft(
                enabled = true,
                fallbackReply = "",
                rules = listOf(AutoReplyRuleDraft(id = 1, keyword = "", reply = "")),
            )

        assertEquals(
            "the blank enabled fallback must be reported before the (wholly-blank, otherwise ignorable) rule is even looked at",
            OfflineAutoReplyValidationProblem.FallbackRequired,
            problem,
        )
    }

    @Test
    fun `rules are checked in list order, the first offending rule wins`() {
        val problem =
            validateOfflineAutoReplyDraft(
                enabled = false,
                fallbackReply = "",
                rules =
                    listOf(
                        AutoReplyRuleDraft(id = 1, keyword = "хорошее", reply = "ответ"),
                        AutoReplyRuleDraft(id = 2, keyword = "", reply = "нужен ответ"),
                        AutoReplyRuleDraft(id = 3, keyword = "", reply = "тоже нужен ответ"),
                    ),
            )

        assertEquals(OfflineAutoReplyValidationProblem.RuleKeywordRequired, problem)
    }

    @Test
    fun `meaningfulAutoReplyRules drops only wholly-blank rows and preserves order`() {
        val rules =
            listOf(
                AutoReplyRuleDraft(id = 1, keyword = "a", reply = "1"),
                AutoReplyRuleDraft(id = 2, keyword = "  ", reply = "  "),
                AutoReplyRuleDraft(id = 3, keyword = "", reply = "2"),
                AutoReplyRuleDraft(id = 4, keyword = "b", reply = ""),
            )

        val meaningful = meaningfulAutoReplyRules(rules)

        assertEquals(listOf(1L, 3L, 4L), meaningful.map { it.id })
    }
}
