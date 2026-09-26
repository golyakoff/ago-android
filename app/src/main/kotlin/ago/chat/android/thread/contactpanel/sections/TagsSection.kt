package ago.chat.android.thread.contactpanel.sections

import ago.chat.android.R
import ago.chat.android.core.domain.tags.ConversationTag
import ago.chat.android.core.domain.tags.Tag
import ago.chat.android.thread.contactpanel.TagActionError
import ago.chat.android.thread.contactpanel.TagsSectionState
import ago.chat.android.ui.components.ScrimmedDropdownMenu
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * `26-149`: the second section of the contact-detail panel — «МЕТКИ», the tags applied to this
 * conversation. One stateless composable, its own file under `thread/contactpanel/sections/`, called from
 * [ago.chat.android.thread.contactpanel.ContactDetailPanel]'s marked insertion point — the additive
 * convention that container's own doc comment prescribes for `26-148`…`26-153`, so nothing in the shell,
 * the header or the section above it is restructured to add it. It is the exact sibling of
 * [ContactDetailsSection] (S-F): a plain [TagsSectionState] and plain callbacks, no view model and no Hilt,
 * which is what lets [ago.chat.android.thread.contactpanel.sections.TagsSection]'s own test drive it with a
 * hand-built state and no component in play.
 *
 * ## Read is the panel's gate; write is its own
 *
 * Seeing the chips at all rides on the panel's own `conversation:read` gate (the affordance that opens the
 * sheet, `26-147`) — there is no second read gate here. The **write** affordances — each chip's «×» remove
 * and the «+ метка» add control — are drawn only when [canTag] (the operator holds `conversation:tag`):
 * hide-not-disable, design Q7. An operator without it still reads the tags; they simply have no control to
 * change them, exactly as the server would refuse the write anyway
 * ([ago.chat.android.core.domain.tags.ConversationTagsApi]'s own contract).
 *
 * ## The «+ метка» picker
 *
 * The add control offers the site's whole tag vocabulary **minus whatever is already applied** — that
 * subtraction is this UI's job, not the port's ([ConversationTagsApi.fetchSiteTags][ago.chat.android.core.domain.tags.ConversationTagsApi.fetchSiteTags]'s
 * own doc comment). When nothing is left to add (every vocabulary entry is on, or the vocabulary read
 * degraded to empty) the control is not drawn — there is nothing to offer, so hiding it is honest rather
 * than a disabled affordance the operator cannot act on.
 *
 * ## Absent honestly, failures non-destructively
 *
 * With no tags applied (and no add control to draw) the section renders «[R.string.tags_empty]» rather than
 * an empty gap, the same posture [ContactDetailsSection]'s own empty arm takes. A write that was refused or
 * failed shows one line beneath the chips ([TagsSectionState.Loaded.actionError]) with the applied set left
 * exactly as it was — this section never adds or drops a chip a write did not confirm.
 *
 * ## Why the private [SectionHeading] is duplicated from S-F
 *
 * The heading reuses [ago.chat.android.ui.components.SectionLabel]'s *typographic* treatment at the panel's
 * own gutter rather than the shared composable itself, for the reason [ContactDetailsSection]'s own doc
 * comment spells out (that composable bakes in a 16dp gutter that would double the panel's own 20dp one).
 * That helper is `private` to its file, so it is restated here rather than shared — the same
 * one-file-per-section convention the container prescribes; a shared `:app` heading composable would be a
 * refactor of the just-merged S-F, out of this slice's scope.
 */
@Composable
internal fun TagsSection(
    state: TagsSectionState,
    canTag: Boolean,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag(TAGS_SECTION_TEST_TAG)) {
        SectionHeading(text = stringResource(R.string.tags_section_title))

        when (state) {
            TagsSectionState.Loading ->
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = RowSpacing).size(SpinnerSize),
                    strokeWidth = SpinnerStroke,
                )

            is TagsSectionState.Failed ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.tags_load_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetry) {
                        Text(text = stringResource(R.string.action_retry))
                    }
                }

            is TagsSectionState.Loaded -> LoadedTags(state, canTag, onAddTag, onRemoveTag)
        }
    }
}

@Composable
private fun LoadedTags(
    state: TagsSectionState.Loaded,
    canTag: Boolean,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    // The picker offers the site vocabulary minus whatever is already on — computed here, not by the port.
    val addable = state.vocabulary.filterNot { candidate -> state.applied.any { it.id == candidate.id } }
    val canAdd = canTag && addable.isNotEmpty()

    if (state.applied.isEmpty() && !canAdd) {
        Text(
            text = stringResource(R.string.tags_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RowSpacing),
        )
    } else {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = RowSpacing),
            horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
            verticalArrangement = Arrangement.spacedBy(ChipSpacing),
        ) {
            state.applied.forEach { tag ->
                TagChip(
                    tag = tag,
                    canRemove = canTag,
                    pending = tag.id in state.pendingTagIds,
                    onRemove = { onRemoveTag(tag.id) },
                )
            }
            if (canAdd) {
                AddTagControl(addable = addable, pendingTagIds = state.pendingTagIds, onAddTag = onAddTag)
            }
        }
    }

    state.actionError?.let { error ->
        Text(
            text =
                when (error) {
                    is TagActionError.Refused -> error.detail
                    is TagActionError.Failed -> stringResource(R.string.tags_action_failed)
                },
            style = MaterialTheme.typography.bodySmall,
            color = agoStatusColors().dangerText,
            modifier = Modifier.padding(top = RowSpacing),
        )
    }
}

/** One applied tag. When the operator may write ([canRemove]) it is a removable [InputChip] whose tap (and
 * its trailing «×») removes it, disabled while that tag's own write is in flight; otherwise a plain,
 * read-only [SuggestionChip]. */
@Composable
private fun TagChip(
    tag: ConversationTag,
    canRemove: Boolean,
    pending: Boolean,
    onRemove: () -> Unit,
) {
    if (canRemove) {
        val removeLabel = stringResource(R.string.tags_remove, tag.name)
        InputChip(
            selected = false,
            enabled = !pending,
            onClick = onRemove,
            label = { Text(text = tag.name) },
            trailingIcon = {
                // A bare «×» glyph rather than a Material icon - the design's own affordance (T2: "× →
                // remove"), and it adds no icon-library dependency this app does not otherwise carry. Its
                // remove semantics come from the chip's own accessible label below, not the glyph.
                Text(text = REMOVE_GLYPH, style = MaterialTheme.typography.bodyMedium)
            },
            // The whole chip is the remove target (InputChip's single onClick), so name what a tap does for
            // TalkBack rather than letting it read only the tag name.
            modifier =
                Modifier.semantics {
                    contentDescription = removeLabel
                },
        )
    } else {
        SuggestionChip(
            onClick = {},
            label = { Text(text = tag.name) },
        )
    }
}

/** The «+ метка» control: an [AssistChip] that opens a [DropdownMenu] of the addable tags. Its expanded
 * flag is local presentation state — the section stays stateless as to *data* (the picked id flows out
 * through [onAddTag]); whether the menu is open is not something a caller or a test needs to inject. */
@Composable
private fun AddTagControl(
    addable: List<Tag>,
    pendingTagIds: Set<String>,
    onAddTag: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(text = stringResource(R.string.tags_add)) },
        )
        // `26-177`: [ScrimmedDropdownMenu] in place of a plain `DropdownMenu` - see its own doc comment for
        // why this menu, like every other in the app, now dims the screen behind it while open.
        ScrimmedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            addable.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(text = tag.name) },
                    enabled = tag.id !in pendingTagIds,
                    onClick = {
                        expanded = false
                        onAddTag(tag.id)
                    },
                )
            }
        }
    }
}

/** The section heading, reusing [ago.chat.android.ui.components.SectionLabel]'s typographic treatment at
 * the panel's own gutter — restated from [ContactDetailsSection] for the reason this file's doc comment
 * gives (that helper is `private` to its own file, and one-file-per-section is the container's convention). */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text.uppercase(),
        style =
            MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.1.em,
            ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** `26-149`: the section's root test tag - a stable hook for `TagsSectionTest` independent of the (Russian,
 * wording-sensitive) heading, the same reasoning [CONTACT_DETAILS_SECTION_TEST_TAG]'s own doc comment gives. */
internal const val TAGS_SECTION_TEST_TAG: String = "tagsSection"

// The remove affordance's glyph — a bare multiplication sign, not a translatable string (it reads
// identically in every locale, the same way `ContactDetailsSection`'s own «—» placeholder is a bare literal).
private const val REMOVE_GLYPH = "×"

private val RowSpacing = 8.dp
private val ChipSpacing = 8.dp
private val SpinnerSize = 16.dp
private val SpinnerStroke = 2.dp
