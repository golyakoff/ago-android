package ago.chat.android.thread.contactpanel.sections

import ago.chat.android.R
import ago.chat.android.thread.contactpanel.AttachmentUploadActionError
import ago.chat.android.thread.contactpanel.AttachmentUploadSectionState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-152`: the fifth and final section of the contact-detail panel — «Приём файлов от посетителя», a
 * single reversible toggle (`docs/design/26-111-contact-panel-slices.md`'s own S-J line: "reversible;
 * granted-by caption; `conversation:attachment_upload_grant`"). One stateless composable, its own file
 * under `thread/contactpanel/sections/`, called from
 * [ago.chat.android.thread.contactpanel.ContactDetailPanel]'s marked insertion point — the additive
 * convention that container's own doc comment prescribes, so nothing in the shell, the header or the
 * sections above it is restructured to add this one. It takes a plain [AttachmentUploadSectionState] and
 * plain callbacks, no view model and no Hilt — the same shape [TagsSection]/[NotesSection] already
 * establish, which is what lets this file's own test drive it with a hand-built state.
 *
 * ## Whole section hidden without the permission — not merely its control
 *
 * Unlike [TagsSection]/[NotesSection] (whose *read* half rides the panel's own `conversation:read` gate
 * and only the write is separately gated), this feature has no read gate of its own to fall back to: an
 * operator without [canGrant] (`conversation:attachment_upload_grant`) sees nothing here at all, the
 * identical "hidden, not disabled" posture `ago-console`'s own `AttachmentUploadGrantToggle` states for
 * the same permission ("an operator who will never use it" gains nothing from being shown a control it
 * cannot act on). [AttachmentUploadSectionState]'s own doc comment states why this is the one section
 * whose *existence*, not only its write, is gated.
 *
 * ## One tap, no dialog — reversible
 *
 * `docs/design/26-111-thread-contact-detail-panel.md` §"Per-action confirmation shape": "single tap, no
 * dialog — reversible, the console's `SeatToggleButton` shape." The control is a single [Button] whose
 * label states the *next* state it will move to (mirroring `ago-console`'s own
 * `attachmentUploadGrantButton`/`attachmentUploadRevokeButton` wording) rather than a `Switch` drawn
 * already-on/-off — this app has no other binary toggle to be consistent with, and a labelled button
 * names the action a tap takes, the same reasoning [NotesSection]'s own composer submit button already
 * follows for its own two-word states.
 *
 * ## The granted-by caption
 *
 * Shown only while [AttachmentUploadSectionState.Loaded.granted] is `true`, mirroring
 * `ago-console`'s own `AttachmentUploadGrantToggle`: "an operator granted it" when
 * [AttachmentUploadSectionState.Loaded.grantedByOperatorId] is present, "granted by this site's own
 * default" when it is `null` (a grant this app never itself performs, but the server can still report
 * one, `Ago.Chat.Domain`'s own remarks on the field) — **never a resolved operator name**, the identical
 * "who/when, not a name" gap that component's own doc comment names rather than works around. The
 * timestamp, when present, is rendered absolute in the operator's own zone (CLAUDE.md rule 11), the same
 * `d MMMM, HH:mm` shape [NotesSection]'s own `noteTimestamp` already establishes for a server timestamp
 * this app does not otherwise share a formatter for (this codebase's own per-file convention — no shared
 * date helper, restated once more here).
 */
@Composable
internal fun AttachmentUploadSection(
    state: AttachmentUploadSectionState,
    canGrant: Boolean,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!canGrant) return

    Column(modifier = modifier.fillMaxWidth().testTag(ATTACHMENT_UPLOAD_SECTION_TEST_TAG)) {
        SectionHeading(text = stringResource(R.string.attachment_upload_section_title))

        when (state) {
            AttachmentUploadSectionState.Loading ->
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = RowSpacing).size(SpinnerSize),
                    strokeWidth = SpinnerStroke,
                )

            is AttachmentUploadSectionState.Failed ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = RowSpacing)) {
                    Text(
                        text = stringResource(R.string.attachment_upload_load_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetry) {
                        Text(text = stringResource(R.string.action_retry))
                    }
                }

            is AttachmentUploadSectionState.Loaded -> LoadedAttachmentUpload(state, onToggle)
        }
    }
}

@Composable
private fun LoadedAttachmentUpload(
    state: AttachmentUploadSectionState.Loaded,
    onToggle: () -> Unit,
) {
    Button(
        onClick = onToggle,
        enabled = !state.toggling,
        modifier = Modifier.padding(top = RowSpacing),
    ) {
        Text(
            text =
                stringResource(
                    if (state.granted) R.string.attachment_upload_revoke_button else R.string.attachment_upload_grant_button,
                ),
        )
    }

    if (state.granted) {
        Text(
            text = grantedByCaption(state.grantedAt, state.grantedByOperatorId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RowSpacing),
        )
    }

    state.actionError?.let { error ->
        Text(
            text =
                when (error) {
                    is AttachmentUploadActionError.Refused -> error.detail
                    is AttachmentUploadActionError.Failed -> stringResource(R.string.attachment_upload_toggle_failed)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = RowSpacing),
        )
    }
}

/** "Разрешено оператором"/"Разрешено по умолчанию для этого сайта", plus « · {time}» when [grantedAt]
 * parses — the identical join [ContactDetailPanel]'s own `summaryLineText` uses for H4/H5, restated here
 * for this section's own pair (that file's own doc comment on why a shared helper is not worth it for
 * this codebase's one-file-per-section convention). */
@Composable
private fun grantedByCaption(
    grantedAt: String?,
    grantedByOperatorId: String?,
): String {
    val who =
        stringResource(
            if (grantedByOperatorId != null) {
                R.string.attachment_upload_granted_by_operator
            } else {
                R.string.attachment_upload_granted_by_default
            },
        )
    val time = grantedAt?.let { grantedAtTimestamp(it) }
    return if (time != null) "$who · $time" else who
}

/** [grantedAt] rendered in the operator's own zone (CLAUDE.md rule 11), `null` — never a guessed time —
 * when it fails to parse, the identical posture [NotesSection]'s own `noteTimestamp` takes for a
 * malformed note timestamp. */
@Composable
private fun grantedAtTimestamp(grantedAt: String): String? {
    val locale = LocalConfiguration.current.locales[0]
    return runCatching {
        OffsetDateTime
            .parse(grantedAt)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(GRANTED_AT_TIMESTAMP_PATTERN, locale))
    }.getOrNull()
}

/** The section heading, reusing [ago.chat.android.ui.components.SectionLabel]'s typographic treatment at
 * the panel's own gutter — restated from [ContactDetailsSection]/[TagsSection] for the reason those
 * files' own doc comments give (that helper is `private` to its own file, and one-file-per-section is the
 * container's convention). */
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

/** `26-152`: the section's root test tag - a stable hook for `AttachmentUploadSectionTest` independent of
 * the (Russian, wording-sensitive) heading, the same reasoning [TAGS_SECTION_TEST_TAG]'s own doc comment
 * gives. */
internal const val ATTACHMENT_UPLOAD_SECTION_TEST_TAG: String = "attachmentUploadSection"

// `d MMMM, HH:mm` -> "14 марта, 09:00" (ru) / "14 March, 09:00" (en) - an absolute date-and-time, never a
// relative "5 minutes ago" (CLAUDE.md rule 11), the identical pattern [NotesSection]'s own
// `NOTE_TIMESTAMP_PATTERN` uses.
private const val GRANTED_AT_TIMESTAMP_PATTERN = "d MMMM, HH:mm"

private val RowSpacing = 8.dp
private val SpinnerSize = 16.dp
private val SpinnerStroke = 2.dp
