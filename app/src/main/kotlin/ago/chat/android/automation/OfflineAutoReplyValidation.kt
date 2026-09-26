package ago.chat.android.automation

import ago.chat.android.core.domain.autoreply.OfflineAutoReplyBounds

/**
 * `26-192`/`C5` (`docs/design/tenant-channels-android.md` §4.3): the client-side courtesy check
 * [OfflineAutoReplyViewModel.save] runs *before* [ago.chat.android.core.domain.autoreply.OfflineAutoReplyApi.update]
 * is ever called — a field-for-field, order-for-order mirror of `ago-console`'s own
 * `offlineAutoReplyValidation.ts#validateDraft`, first problem wins. **Never the authority**: the server
 * (`UpdateOfflineAutoReplyHandler`) is the real, authoritative gate, and a rule this misses is simply
 * refused server-side, whose `detail` text the screen surfaces unchanged
 * ([ago.chat.android.core.domain.autoreply.OfflineAutoReplyWriteResult.Refused]) - the identical
 * "courtesy, never authority" posture `docs/design/tenant-channels-android.md` §3.4's own branding-logo
 * check states for itself.
 *
 * **A rule whose keyword *and* reply are both blank is dropped, never reported** - the editor's own
 * "Add" button leaves a blank row behind for the operator to type into or abandon, so treating an
 * untouched row as an error would make the form permanently invalid the moment a second rule is added.
 * [meaningfulAutoReplyRules] is the one place that filter lives; both this function and
 * [OfflineAutoReplyViewModel.save]'s own request-building read it from here rather than each keeping a
 * separate copy.
 *
 * @return the first problem found, or `null` when the draft is sendable.
 */
internal fun validateOfflineAutoReplyDraft(
    enabled: Boolean,
    fallbackReply: String,
    rules: List<AutoReplyRuleDraft>,
): OfflineAutoReplyValidationProblem? {
    val trimmedFallback = fallbackReply.trim()

    if (enabled && trimmedFallback.isEmpty()) {
        return OfflineAutoReplyValidationProblem.FallbackRequired
    }

    if (trimmedFallback.length > OfflineAutoReplyBounds.MAX_REPLY_LENGTH) {
        return OfflineAutoReplyValidationProblem.FallbackTooLong
    }

    val meaningful = meaningfulAutoReplyRules(rules)
    if (meaningful.size > OfflineAutoReplyBounds.MAX_RULES) {
        return OfflineAutoReplyValidationProblem.TooManyRules
    }

    for (rule in meaningful) {
        val trimmedKeyword = rule.keyword.trim()
        if (trimmedKeyword.isEmpty()) {
            return OfflineAutoReplyValidationProblem.RuleKeywordRequired
        }
        if (rule.reply.trim().isEmpty()) {
            return OfflineAutoReplyValidationProblem.RuleReplyRequired(trimmedKeyword)
        }
        if (trimmedKeyword.length > OfflineAutoReplyBounds.MAX_KEYWORD_LENGTH) {
            return OfflineAutoReplyValidationProblem.RuleKeywordTooLong
        }
        // `offlineAutoReplyValidation.ts`'s own asymmetry, mirrored deliberately: the keyword is measured
        // trimmed (whitespace either side is never sent, `toRequestRules`), while the reply is measured
        // as typed - only the fallback and the keyword are trimmed before this bound is checked.
        if (rule.reply.length > OfflineAutoReplyBounds.MAX_REPLY_LENGTH) {
            return OfflineAutoReplyValidationProblem.RuleReplyTooLong
        }
    }

    return null
}

/** Everything but the wholly-blank rows the "Add" button leaves around for typing into - the identical
 * `meaningfulRules` filter `offlineAutoReplyValidation.ts` applies before both validating and building a
 * save's own request rules. */
internal fun meaningfulAutoReplyRules(rules: List<AutoReplyRuleDraft>): List<AutoReplyRuleDraft> =
    rules.filter { it.keyword.trim().isNotEmpty() || it.reply.trim().isNotEmpty() }

/** `validateDraft`'s own seven checks, restated as one arm each rather than a formatted string - each
 * arm becomes exactly one sentence at its own single call site
 * (`ago.chat.android.automation.offlineAutoReplyActionErrorText` in `OfflineAutoReplyScreen.kt`), the
 * identical "one problem, one resource" split [ago.chat.android.channels.LogoValidationProblem] already
 * establishes for the branding screen's own courtesy check. */
internal sealed interface OfflineAutoReplyValidationProblem {
    /** `enabled && fallbackReply.trim().isEmpty()`. */
    data object FallbackRequired : OfflineAutoReplyValidationProblem

    /** `fallbackReply.trim().length > OfflineAutoReplyBounds.MAX_REPLY_LENGTH`. */
    data object FallbackTooLong : OfflineAutoReplyValidationProblem

    /** More than [OfflineAutoReplyBounds.MAX_RULES] meaningful rules. */
    data object TooManyRules : OfflineAutoReplyValidationProblem

    /** A meaningful rule (has a reply, or is being reported for its keyword first) with a blank keyword. */
    data object RuleKeywordRequired : OfflineAutoReplyValidationProblem

    /** A meaningful rule with a keyword but a blank reply. [keyword] is that rule's own trimmed keyword,
     * named in the message the same way `offlineAutoReplyValidation.ts`'s own
     * `autoReplyValidationReplyRequiredPrefix/Suffix` names it. */
    data class RuleReplyRequired(
        val keyword: String,
    ) : OfflineAutoReplyValidationProblem

    /** A rule's own keyword exceeds [OfflineAutoReplyBounds.MAX_KEYWORD_LENGTH]. */
    data object RuleKeywordTooLong : OfflineAutoReplyValidationProblem

    /** A rule's own reply exceeds [OfflineAutoReplyBounds.MAX_REPLY_LENGTH]. */
    data object RuleReplyTooLong : OfflineAutoReplyValidationProblem
}
