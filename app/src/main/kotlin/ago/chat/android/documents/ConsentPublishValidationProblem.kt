package ago.chat.android.documents

import ago.chat.android.core.domain.consent.ConsentDocumentBounds

/**
 * `26-226` (`docs/design/tenant-consent-android.md` §1.3/§3.4): the client-side courtesy check
 * [ConsentDocumentsViewModel.publish] runs *before*
 * [ago.chat.android.core.domain.consent.SiteConsentDocumentsApi.publish] is ever called - first problem
 * wins, mirroring `ago-console`'s own `handleSubmit` for this same form. **Never the authority**: the
 * server (`PublishedDocumentVersion`) is the real, authoritative gate, and a draft this misses is simply
 * refused server-side as `Document.Invalid`, whose `detail` text the screen surfaces unchanged
 * ([ago.chat.android.core.domain.consent.ConsentPublishResult.Refused]) - the identical "courtesy, never
 * authority" posture
 * [ago.chat.android.automation.validateCannedResponsesDraft]'s own doc comment states for itself.
 *
 * **The body is measured as typed for its length bound, only trimmed for the blank check** - the
 * identical asymmetry [ago.chat.android.automation.validateCannedResponsesDraft]'s own doc comment
 * states for a canned response's own body: it becomes the document's own text verbatim, so its
 * whitespace is meaningful and only the title is trimmed before being sent.
 *
 * @return the first problem found, or `null` when the draft is publishable.
 */
internal fun validateConsentPublishDraft(
    title: String,
    body: String,
): ConsentPublishValidationProblem? {
    val trimmedTitle = title.trim()
    if (trimmedTitle.isEmpty()) {
        return ConsentPublishValidationProblem.TitleRequired
    }
    if (trimmedTitle.length > ConsentDocumentBounds.MAX_TITLE_LENGTH) {
        return ConsentPublishValidationProblem.TitleTooLong
    }
    if (body.trim().isEmpty()) {
        return ConsentPublishValidationProblem.BodyRequired
    }
    if (body.length > ConsentDocumentBounds.MAX_BODY_LENGTH) {
        return ConsentPublishValidationProblem.BodyTooLong
    }
    return null
}

/** `PublishedDocumentVersion`'s own four checks, restated as one arm each rather than a formatted
 * string - each arm becomes exactly one sentence at its own single call site
 * (`ago.chat.android.documents.consentPublishValidationProblemText` in `ConsentDocumentsScreen.kt`), the
 * identical "one problem, one resource" split
 * [ago.chat.android.automation.CannedResponseValidationProblem] already establishes. */
internal sealed interface ConsentPublishValidationProblem {
    data object TitleRequired : ConsentPublishValidationProblem

    data object TitleTooLong : ConsentPublishValidationProblem

    data object BodyRequired : ConsentPublishValidationProblem

    data object BodyTooLong : ConsentPublishValidationProblem
}
