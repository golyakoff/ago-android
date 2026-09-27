package ago.chat.android.automation

import ago.chat.android.core.domain.tags.TagBounds

/**
 * `26-225` (`docs/design/tenant-canned-tags-android.md` §2.4): the client-side courtesy check
 * [TagsViewModel.create]/[TagsViewModel.rename] run *before* [ago.chat.android.core.domain.tags.SiteTagsApi]
 * is ever called — the identical "courtesy, never authority" posture
 * [ago.chat.android.automation.validateCannedResponsesDraft]'s own doc comment states for itself. The
 * server (`CreateTagHandler`/`RenameTagHandler`) remains the real, authoritative gate — `Tag.Invalid` for
 * a name this check misses, `Tag.AlreadyExists` for a case-insensitive duplicate this check cannot know
 * about at all, since it never reads the existing vocabulary to compare against.
 *
 * @return the first problem found, or `null` when [name] is sendable.
 */
internal fun validateTagNameDraft(name: String): TagValidationProblem? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
        return TagValidationProblem.NameRequired
    }
    if (trimmed.length > TagBounds.MAX_NAME_LENGTH) {
        return TagValidationProblem.NameTooLong
    }
    return null
}

/** [validateTagNameDraft]'s own two reasons, each its own string at the single call site
 * ([ago.chat.android.automation.tagsActionErrorText] in `TagsScreen.kt`) — the identical "one problem, one
 * resource" split [ago.chat.android.automation.CannedResponseValidationProblem] already establishes. */
internal sealed interface TagValidationProblem {
    /** A blank name (after trimming). */
    data object NameRequired : TagValidationProblem

    /** A name exceeding [TagBounds.MAX_NAME_LENGTH]. */
    data object NameTooLong : TagValidationProblem
}
