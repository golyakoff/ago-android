package ago.chat.android.thread.contactpanel.sections

import ago.chat.android.R
import ago.chat.android.core.domain.notes.ConversationNote
import ago.chat.android.thread.contactpanel.AddNoteError
import ago.chat.android.thread.contactpanel.NotesSectionState
import ago.chat.android.ui.components.russianPluralStringResource
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-150`: the third section of the contact-detail panel — «Заметки команды», one row that opens a
 * notes **sub-screen** rather than an inline list of rows (unlike [ContactDetailsSection]/[TagsSection]):
 * `docs/design/26-111-thread-contact-detail-panel.md` §4 ("tapping the «Заметки команды» row opens a
 * notes sub-screen (list + composer)"). Its own file under `thread/contactpanel/sections/`, called from
 * [ago.chat.android.thread.contactpanel.ContactDetailPanel]'s marked insertion point — the additive
 * convention that container's own doc comment prescribes for `26-148`…`26-153`.
 *
 * ## Read is the panel's gate; there is no second one
 *
 * Unlike [TagsSection] (whose write half needs its own `conversation:tag` gate beside the panel's own
 * `conversation:read`), reading a note is not a separate capability at all — the Appendix table
 * (`docs/design/26-111-*.md`) lists "read notes" under the identical `conversation:read` that already
 * gates the whole sheet. So this row carries no read-permission parameter of its own: it is drawn
 * whenever the panel is open, hidden exactly when the panel itself is (design Q7) — the same "the row
 * rides the panel's own gate" posture [TagsSection]'s own doc comment states for its chips. Only
 * [canWriteNote] (`conversation:note_write`) gates anything here, and it gates the composer alone, never
 * the row or the read-only list — hide-not-disable.
 *
 * ## Why the sub-screen's own open/close flag lives here, not in [ago.chat.android.thread.contactpanel.ContactDetailPanel]
 *
 * The sub-screen is local presentation state — "is the notes screen open right now" — no different in
 * kind from [TagsSection]'s own `AddTagControl` `expanded` flag; only the *data* (the notes list, the
 * draft, the write's in-flight/error state) needs to survive this composable being torn down and rebuilt,
 * and that already lives in [NotesSectionState] on [ago.chat.android.thread.contactpanel.ContactPanelViewModel].
 * Keeping the flag here is what lets this file stay the one place touched to add the row (the container's
 * own insertion-point convention) rather than growing `ContactDetailPanel`'s own state for a screen that
 * section alone opens and closes. [rememberSaveable] (not a plain `remember`) survives a configuration
 * change while the sub-screen is open, the same reason [ago.chat.android.thread.ThreadScreen]'s own
 * `showContactPanel` uses it one level up.
 *
 * ## Count = list length, fetched once, at panel-open time
 *
 * The row's own count is [NotesSectionState.Loaded.notes]'s size — never a separate read
 * (design Q8: "fetch the notes list on open … no separate count field"). The fetch itself already
 * happened when the panel opened ([ContactPanelViewModel.open]'s own doc comment lists notes beside
 * contact-details and tags), so by the time an operator taps this row the count is very often already on
 * screen; while it is still in flight the row shows a small spinner instead, and is not yet tappable —
 * there is nothing loaded to show a sub-screen over.
 */
@Composable
internal fun NotesSection(
    state: NotesSectionState,
    canWriteNote: Boolean,
    onNoteDraftChanged: (String) -> Unit,
    onAddNote: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSubScreen by rememberSaveable { mutableStateOf(false) }

    NotesRow(
        state = state,
        onOpen = { showSubScreen = true },
        onRetry = onRetry,
        modifier = modifier,
    )

    // Guarding on the state still being `Loaded` (rather than trusting `showSubScreen` alone) is
    // defensive rather than load-bearing today - nothing in this section currently re-enters `Loading`
    // once opened - but it is what keeps the sub-screen from ever being asked to render a `null` list.
    val loaded = state as? NotesSectionState.Loaded
    if (showSubScreen && loaded != null) {
        NotesSubScreen(
            state = loaded,
            canWriteNote = canWriteNote,
            onDraftChanged = onNoteDraftChanged,
            onAddNote = onAddNote,
            onDismiss = { showSubScreen = false },
        )
    }
}

/** The «Заметки команды» row itself: title, a trailing count/spinner/retry depending on [state], and a
 * chevron once [state] is [NotesSectionState.Loaded] - the only arm the row is actually clickable in. */
@Composable
private fun NotesRow(
    state: NotesSectionState,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        NotesSectionState.Loading ->
            Row(
                modifier = modifier.fillMaxWidth().padding(vertical = RowVerticalPadding).testTag(NOTES_ROW_TEST_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.notes_row_title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                CircularProgressIndicator(modifier = Modifier.size(SpinnerSize), strokeWidth = SpinnerStroke)
            }

        is NotesSectionState.Failed ->
            Row(
                modifier = modifier.fillMaxWidth().padding(vertical = RowVerticalPadding).testTag(NOTES_ROW_TEST_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.notes_row_title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.notes_row_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) {
                    Text(text = stringResource(R.string.action_retry))
                }
            }

        is NotesSectionState.Loaded ->
            Row(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpen)
                        .padding(vertical = RowVerticalPadding)
                        .testTag(NOTES_ROW_TEST_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.notes_row_title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text =
                        russianPluralStringResource(
                            count = state.notes.size.toLong(),
                            one = R.string.notes_row_count_one,
                            few = R.string.notes_row_count_few,
                            many = R.string.notes_row_count_many,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = AgoIcons.ChevronRight,
                    // Decorative: the row's own visible text already states what tapping it does, the
                    // same "the row's text carries the meaning" posture `TagsSection`'s remove chip
                    // takes the opposite way only because it has no visible label of its own to lean on.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = RowSpacing).size(ChevronSize),
                )
            }
    }
}

/**
 * The notes sub-screen — a second, independent [ModalBottomSheet] stacked over the panel's own, opened
 * only from [NotesRow] and never nested inside the panel's own `Column` (§4's own "sub-screen" language:
 * a distinct surface, not one more inline section). Its own back control returns to the panel rather than
 * dismissing the whole sheet, the same "leaving a sub-screen is not the same as leaving the panel"
 * distinction [ago.chat.android.thread.ThreadScreen]'s own back handling draws one level up for the
 * thread itself.
 *
 * Content is a single [Column] wrapped in [androidx.compose.foundation.verticalScroll] rather than a
 * fixed-height list plus a pinned composer - design's own N1 remark ("N is tiny") is what makes one
 * scrollable column the honest shape here, the identical reasoning that let `26-115`'s own port skip
 * paging for this endpoint in the first place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesSubScreen(
    state: NotesSectionState.Loaded,
    canWriteNote: Boolean,
    onDraftChanged: (String) -> Unit,
    onAddNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SubScreenHorizontalPadding)
                    .testTag(NOTES_SUB_SCREEN_TEST_TAG),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = SubScreenHorizontalPadding)) {
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                }
                Text(
                    text = stringResource(R.string.notes_row_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = SectionSpacing),
            )

            if (state.notes.isEmpty()) {
                Text(
                    text = stringResource(R.string.notes_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.notes.forEach { note -> NoteRow(note) }
            }

            // `26-150`: `conversation:note_write` gates the composer alone - the list above is always
            // readable under the panel's own `conversation:read` gate (this file's own doc comment).
            // Hide-not-disable (design Q7): an operator without it never sees a composer they could not
            // use anyway.
            if (canWriteNote) {
                NotesComposer(
                    draft = state.draft,
                    addingNote = state.addingNote,
                    onDraftChanged = onDraftChanged,
                    onAddNote = onAddNote,
                )
                state.addNoteError?.let { error ->
                    Text(
                        text =
                            when (error) {
                                is AddNoteError.Refused -> error.detail
                                is AddNoteError.Failed -> stringResource(R.string.notes_add_failed)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = agoStatusColors().dangerText,
                        modifier = Modifier.padding(top = RowSpacing),
                    )
                }
            }

            Spacer(modifier = Modifier.height(SectionSpacing))
        }
    }
}

/** One team note: its server-assigned timestamp (absent honestly, never guessed, if it fails to parse)
 * over its body — the identical two-line shape `ago-console`'s own `ConversationNotesPanel` row draws,
 * with no author name beside it (this port's own doc comment: rendering [ConversationNote.authorId] as a
 * name needs a team-roster lookup this section does not have, and is explicitly out of its scope). */
@Composable
private fun NoteRow(note: ConversationNote) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = RowSpacing)) {
        noteTimestamp(note.createdAt)?.let { formatted ->
            Text(
                text = formatted,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = note.body, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The composer: a plain multi-line field plus its own submit control, the identical
 * field-then-button shape `ago.chat.android.team.InviteColleagueSheet`'s own form body already
 * establishes for a different write in a different sheet. */
@Composable
private fun NotesComposer(
    draft: String,
    addingNote: Boolean,
    onDraftChanged: (String) -> Unit,
    onAddNote: () -> Unit,
) {
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChanged,
        enabled = !addingNote,
        placeholder = { Text(text = stringResource(R.string.notes_composer_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onAddNote,
        enabled = !addingNote && draft.isNotBlank(),
        modifier = Modifier.fillMaxWidth().padding(top = RowSpacing, bottom = RowSpacing),
    ) {
        Text(text = stringResource(if (addingNote) R.string.notes_adding_button else R.string.notes_add_button))
    }
}

/** [ConversationNote.createdAt] rendered in the operator's own zone (CLAUDE.md rule 11), `null` - never a
 * guessed date - when it fails to parse, the identical posture `ago.chat.android.thread.ThreadScreen`'s
 * own `clockTimeOrNull` takes for a malformed message timestamp. */
@Composable
private fun noteTimestamp(createdAt: String): String? {
    val locale = LocalConfiguration.current.locales[0]
    return runCatching {
        OffsetDateTime
            .parse(createdAt)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(NOTE_TIMESTAMP_PATTERN, locale))
    }.getOrNull()
}

/** `26-150`: the row's own test tag - a stable hook for `NotesSectionTest` independent of the (Russian,
 * wording-sensitive) title, the same reasoning [CONTACT_DETAILS_SECTION_TEST_TAG]'s own doc comment
 * gives. */
internal const val NOTES_ROW_TEST_TAG: String = "notesRow"

/** `26-150`: the sub-screen's own root test tag - proves the sub-screen is actually up, independent of
 * its own wording. */
internal const val NOTES_SUB_SCREEN_TEST_TAG: String = "notesSubScreen"

// `d MMMM, HH:mm` -> "14 марта, 09:00" (ru) / "14 March, 09:00" (en) - an absolute date-and-time, never a
// relative "5 minutes ago" (CLAUDE.md rule 11), the same pattern shape `ContactDetailPanel`'s own
// `FIRST_VISIT_DATE_PATTERN` uses for H4, extended with a clock time since a note (unlike a first-visit
// date) is often read the same day it was written.
private const val NOTE_TIMESTAMP_PATTERN = "d MMMM, HH:mm"

private val RowVerticalPadding = 12.dp
private val RowSpacing = 8.dp
private val SubScreenHorizontalPadding = 20.dp
private val SectionSpacing = 16.dp
private val ChevronSize = 20.dp
private val SpinnerSize = 16.dp
private val SpinnerStroke = 2.dp
