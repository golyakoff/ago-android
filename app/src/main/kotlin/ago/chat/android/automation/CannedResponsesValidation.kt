package ago.chat.android.automation

import ago.chat.android.core.domain.cannedresponses.CannedResponse
import ago.chat.android.core.domain.cannedresponses.CannedResponseBounds

/**
 * `26-220` (`docs/design/tenant-canned-tags-android.md` §1.5): the client-side courtesy check
 * [CannedResponsesViewModel.save] runs *before*
 * [ago.chat.android.core.domain.cannedresponses.CannedResponsesApi.save] is ever called — first problem
 * wins, mirroring `ago-console`'s own `validateDraft` for this same list. **Never the authority**: the
 * server (`UpdateCannedResponsesHandler`) is the real, authoritative gate, and a response this misses is
 * simply refused server-side, whose `detail` text the screen surfaces unchanged
 * ([ago.chat.android.core.domain.cannedresponses.CannedResponsesWriteResult.Refused]) - the identical
 * "courtesy, never authority" posture
 * [ago.chat.android.automation.validateOfflineAutoReplyDraft]'s own doc comment states for itself.
 *
 * **A response whose title *and* body are both blank is dropped, never reported.** In practice the
 * editor's own Save button is disabled while either field is blank
 * (`docs/design/tenant-canned-tags-android.md` §1.5), so this never actually fires from this app's own
 * UI - it exists so a whole-list save is defensive about its own request the identical way
 * [ago.chat.android.automation.meaningfulAutoReplyRules] is for the auto-reply screen's editable rows.
 *
 * @return the first problem found, or `null` when the draft is sendable.
 */
internal fun validateCannedResponsesDraft(responses: List<CannedResponse>): CannedResponseValidationProblem? {
    val meaningful = meaningfulCannedResponses(responses)
    if (meaningful.size > CannedResponseBounds.MAX_COUNT) {
        return CannedResponseValidationProblem.TooMany
    }

    for (response in meaningful) {
        val trimmedTitle = response.title.trim()
        if (trimmedTitle.isEmpty()) {
            return CannedResponseValidationProblem.TitleRequired
        }
        if (trimmedTitle.length > CannedResponseBounds.MAX_TITLE_LENGTH) {
            return CannedResponseValidationProblem.TitleTooLong
        }
        // The body is measured as typed for its length bound, matching `cannedResponsesValidation.ts`'s
        // own asymmetry: only the title is trimmed before being sent (`toRequestResponses`), since the
        // body becomes a message body verbatim and its own whitespace is meaningful.
        if (response.body.trim().isEmpty()) {
            return CannedResponseValidationProblem.BodyRequired(trimmedTitle)
        }
        if (response.body.length > CannedResponseBounds.MAX_BODY_LENGTH) {
            return CannedResponseValidationProblem.BodyTooLong(trimmedTitle)
        }
    }

    return null
}

/** Everything but the wholly-blank entries - the identical `meaningfulRules`-shaped filter
 * [ago.chat.android.automation.meaningfulAutoReplyRules] applies before both validating and building a
 * save's own request list. */
internal fun meaningfulCannedResponses(responses: List<CannedResponse>): List<CannedResponse> =
    responses.filter { it.title.trim().isNotEmpty() || it.body.trim().isNotEmpty() }

/** `validateDraft`'s own five checks for this list, restated as one arm each rather than a formatted
 * string - each arm becomes exactly one sentence at its own single call site
 * (`ago.chat.android.automation.cannedResponsesActionErrorText` in `CannedResponsesScreen.kt`), the
 * identical "one problem, one resource" split
 * [ago.chat.android.automation.OfflineAutoReplyValidationProblem] already establishes. */
internal sealed interface CannedResponseValidationProblem {
    /** More than [CannedResponseBounds.MAX_COUNT] meaningful responses. */
    data object TooMany : CannedResponseValidationProblem

    /** A meaningful response with a blank title. */
    data object TitleRequired : CannedResponseValidationProblem

    /** A response's own title exceeds [CannedResponseBounds.MAX_TITLE_LENGTH]. */
    data object TitleTooLong : CannedResponseValidationProblem

    /** A meaningful response with a title but a blank body. [title] is that response's own trimmed
     * title, naming which response needs a body. */
    data class BodyRequired(
        val title: String,
    ) : CannedResponseValidationProblem

    /** A response's own body exceeds [CannedResponseBounds.MAX_BODY_LENGTH]. [title] names which one. */
    data class BodyTooLong(
        val title: String,
    ) : CannedResponseValidationProblem
}
