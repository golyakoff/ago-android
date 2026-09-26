package ago.chat.android.thread.contactpanel.sections

import ago.chat.android.R
import ago.chat.android.core.domain.contactdetails.ContactDetail
import ago.chat.android.thread.contactpanel.ContactDetailsSectionState
import ago.chat.android.thread.contactpanel.RowActionError
import ago.chat.android.ui.components.ScrimmedDropdownMenu
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * `26-148`: the first section of the contact-detail panel — «КОНТАКТНЫЕ ДАННЫЕ», the visitor's recorded
 * contact fields (Имя / Телефон / Почта). One stateless composable, its own file under
 * `thread/contactpanel/sections/`, called from [ago.chat.android.thread.contactpanel.ContactDetailPanel]'s
 * marked insertion point — the additive convention that container's own doc comment prescribes for
 * `26-148`…`26-153`, so nothing in the shell or the header is restructured to add it.
 *
 * ## Stateless by construction
 *
 * It reads a plain [ContactDetailsSectionState] and takes its callbacks as parameters — no view model, no
 * Hilt — which is exactly what lets `ContactDetailsSectionTest` drive it with a hand-built state and no
 * component in play, the identical split
 * [ago.chat.android.thread.contactpanel.ContactDetailPanel] already draws for the shell. The one piece of
 * local, presentation-only state this file keeps is each row's own `⋮` `expanded` flag
 * ([ContactDetailRowMenu]) — the identical "a menu's open/closed flag is not data a caller or a test needs
 * to inject" precedent [ago.chat.android.thread.contactpanel.sections.TagsSection]'s own `AddTagControl`
 * already establishes. Everything else — which row is being edited, its draft, which writes are in
 * flight — lives on [ContactDetailsSectionState.Loaded] itself, the same "state that must survive a
 * recomposition of *this* row surviving a sibling row's own edit" reasoning that type's own doc comment
 * gives.
 *
 * ## Reveal, reusing `26-115`'s idiom
 *
 * The «Показать» control is drawn **exactly when [ContactDetail.masked] is `true`** — never inferred from
 * the value's own shape, and never for a `Name` row (the client guarantees a name is never masked,
 * `ContactDetailsApi`'s own contract; design decision #1: the name is plain display text, no «не
 * подтверждено» caption, no validity pill). A masked row whose id is in
 * [ContactDetailsSectionState.Loaded.pendingIds] shows «Показ…» disabled while its one reveal is in
 * flight; the value is replaced in place by the server's unmasked response, and a refusal or transport
 * failure shows under that one row with the masked value left exactly as it was — the panel never unmasks
 * a value itself.
 *
 * ## `26-169` (`docs/design/26-156-*.md`): edit + assessment, behind a row `⋮`
 *
 * Each Phone/Email/Name row can carry a trailing `⋮` ([ContactDetailRowMenu]) — never inline buttons (the
 * design's own Q1: three inline `TextButton`s beside a phone number do not fit a phone row the way the
 * console's desktop layout can afford). It is drawn only when [canSendConversation] holds (hide, not
 * disable — the same posture [TagsSection]'s own `canTag` already takes for its write affordances) **and**
 * at least one of its entries applies to this row: «Изменить» only when the row is not [ContactDetail.masked]
 * (Q2 — "cannot correct a value you cannot read", the console's own rule) and only for a `Phone`/`Email`
 * row's «Подтвердить»/«Отметить недействительным» only when that row's own [ContactDetail.assessment] is
 * not already that value — the identical "an assist chip offering nothing is not drawn" posture
 * [TagsSection]'s own `AddTagControl` already takes for its «+ метка» control. A `Name` row is never
 * assessable (console parity, `assessable` below) — its menu, when drawn, only ever offers «Изменить».
 *
 * Tapping «Изменить» replaces the value line with [ContactDetailEditor] — a plain [OutlinedTextField] (the
 * phone keyboard for a `Phone` row, the email keyboard for `Email` — design Q3: **not** the console's own
 * `+7 PhoneInput`, a separate item if ever wanted) plus «Сохранить»/«Отмена», driven entirely by
 * [ContactDetailsSectionState.Loaded.editingId]/[ContactDetailsSectionState.Loaded.editDraft] so only one
 * row can ever be mid-edit. The assessment word — «Подтверждено» (with [AgoIcons.Check], never a colour
 * alone) / «Недействительно» (`error`-coloured word, the identical "a word, never a colour alone" rule
 * `MastersBody`'s own «Неактивен» already follows, design Q7) — renders beside the value whenever
 * [ContactDetail.assessment] is set, for either state, independent of whether the row is currently
 * showing its `⋮`.
 *
 * ## Absent honestly
 *
 * A row whose value is blank renders «[VALUE_ABSENT]» — the same em-dash placeholder
 * [ago.chat.android.bookings.ConfirmedBookingsScreen] uses for an absent cell — rather than an empty gap.
 *
 * ## Why not the shared [ago.chat.android.ui.components.SectionLabel]
 *
 * That composable bakes in a 16dp horizontal gutter for the full-width list screens it was written for
 * (`26-44`); this section renders inside the panel's own [Column], which already applies a 20dp horizontal
 * gutter, so `SectionLabel` would stack to 36dp and push the heading out of line with both the header
 * above it and its own rows below. The heading here therefore reuses `SectionLabel`'s *typographic*
 * treatment (uppercase, extra-bold, letter-spaced, faint) at the panel's own gutter rather than its
 * padding — named per teaching mode rather than silently duplicated.
 */
@Composable
internal fun ContactDetailsSection(
    state: ContactDetailsSectionState,
    // `26-169`: `conversation:send`, threaded down from `AppShellScreen` the identical way `canTag`/
    // `canWriteNote` already are — gates every row `⋮` action hide-not-disable (design Q7). Reading rows and
    // revealing them stay ungated here (they ride the panel's own `conversation:read` gate, `26-148`'s own
    // doc comment).
    canSendConversation: Boolean,
    onReveal: (String) -> Unit,
    onRetry: () -> Unit,
    onStartEdit: (String) -> Unit,
    onEditDraftChanged: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    // Raw wire spelling (`"Confirmed"`/`"Invalid"`) forwarded verbatim - the identical "the classification
    // lives in :core:domain, the raw string travels" discipline `ContactDetail.kind` already follows, so
    // this section never introduces an enum the port itself deliberately does not have.
    onSetAssessment: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag(CONTACT_DETAILS_SECTION_TEST_TAG)) {
        SectionHeading(text = stringResource(R.string.contact_details_section_title))

        when (state) {
            ContactDetailsSectionState.Loading ->
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = RowSpacing).size(SpinnerSize),
                    strokeWidth = SpinnerStroke,
                )

            is ContactDetailsSectionState.Failed ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.contact_details_load_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetry) {
                        Text(text = stringResource(R.string.action_retry))
                    }
                }

            is ContactDetailsSectionState.Loaded ->
                if (state.details.isEmpty()) {
                    Text(
                        text = stringResource(R.string.contact_details_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RowSpacing),
                    )
                } else {
                    state.details.forEach { detail ->
                        ContactDetailRow(
                            detail = detail,
                            canSendConversation = canSendConversation,
                            pending = detail.id in state.pendingIds,
                            rowError = state.rowErrors[detail.id],
                            editing = state.editingId == detail.id,
                            editDraft = state.editDraft,
                            onReveal = { onReveal(detail.id) },
                            onStartEdit = { onStartEdit(detail.id) },
                            onEditDraftChanged = onEditDraftChanged,
                            onSaveEdit = onSaveEdit,
                            onCancelEdit = onCancelEdit,
                            onConfirm = { onSetAssessment(detail.id, ASSESSMENT_CONFIRMED) },
                            onMarkInvalid = { onSetAssessment(detail.id, ASSESSMENT_INVALID) },
                        )
                    }
                }
        }
    }
}

/**
 * One contact-detail row: the field label over its value, with the assessment word, «Показать» and the
 * row `⋮` on the value line for a non-editing row - or, while [editing], [ContactDetailEditor] in its
 * place - and any row error beneath either.
 */
@Composable
private fun ContactDetailRow(
    detail: ContactDetail,
    canSendConversation: Boolean,
    pending: Boolean,
    rowError: RowActionError?,
    editing: Boolean,
    editDraft: String,
    onReveal: () -> Unit,
    onStartEdit: () -> Unit,
    onEditDraftChanged: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onConfirm: () -> Unit,
    onMarkInvalid: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = RowSpacing)) {
        Text(
            text = fieldLabel(detail.kind),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (editing) {
            ContactDetailEditor(
                kind = detail.kind,
                draft = editDraft,
                saving = pending,
                onDraftChanged = onEditDraftChanged,
                onSave = onSaveEdit,
                onCancel = onCancelEdit,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RowSpacing),
            ) {
                Text(
                    text = detail.value.ifBlank { VALUE_ABSENT },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AssessmentWord(assessment = detail.assessment)
                // Driven strictly by `masked`, exactly as the calendar reveal is (`26-53`); a name row
                // (masked=false) never gets the control.
                if (detail.masked) {
                    TextButton(onClick = onReveal, enabled = !pending) {
                        Text(
                            text =
                                stringResource(
                                    if (pending) R.string.contact_details_revealing else R.string.contact_details_reveal,
                                ),
                        )
                    }
                }
                if (canSendConversation) {
                    ContactDetailRowMenu(
                        // Q2: cannot correct a value you cannot read.
                        showEdit = !detail.masked,
                        showConfirm = assessable(detail.kind) && detail.assessment != ASSESSMENT_CONFIRMED,
                        showMarkInvalid = assessable(detail.kind) && detail.assessment != ASSESSMENT_INVALID,
                        enabled = !pending,
                        onEdit = onStartEdit,
                        onConfirm = onConfirm,
                        onMarkInvalid = onMarkInvalid,
                    )
                }
            }
        }

        rowError?.let { error ->
            Text(
                text = rowErrorText(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The row's own «Изменить» editor - a plain [OutlinedTextField] (design Q3: the phone keyboard for a
 * `Phone` row, the email keyboard for `Email`, never the console's own `+7 PhoneInput`) plus «Сохранить»
 * (disabled while blank or [saving]) and «Отмена». Form-over-row: this composable *replaces* the row's
 * value line rather than opening a dialog, the app's own one-card-two-modes idiom
 * (`MastersBody`'s/`ServicesScreen`'s own `WorkerEditForm`/`ServiceEditForm` doc comments). */
@Composable
private fun ContactDetailEditor(
    kind: String,
    draft: String,
    saving: Boolean,
    onDraftChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChanged,
            singleLine = true,
            enabled = !saving,
            keyboardOptions = KeyboardOptions(keyboardType = editorKeyboardType(kind)),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.padding(top = RowSpacing),
            horizontalArrangement = Arrangement.spacedBy(RowSpacing),
        ) {
            TextButton(onClick = onSave, enabled = !saving && draft.isNotBlank()) {
                Text(
                    text =
                        stringResource(
                            if (saving) R.string.contact_details_action_saving else R.string.contact_details_action_save,
                        ),
                )
            }
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.action_cancel))
            }
        }
    }
}

/** Design §3: `Phone` → the phone keyboard, `Email` → the email keyboard, every other kind (`Name`) →
 * the plain keyboard - never a client-side format mask, since neither this port nor the server enforces
 * one beyond non-empty ([ago.chat.android.core.domain.contactdetails.ContactDetailsApi.editContactDetail]'s
 * own doc comment). */
private fun editorKeyboardType(kind: String): KeyboardType =
    when (kind) {
        "Phone" -> KeyboardType.Phone
        "Email" -> KeyboardType.Email
        else -> KeyboardType.Text
    }

/** The row `⋮` - drawn only when at least one of [showEdit]/[showConfirm]/[showMarkInvalid] applies (an
 * empty menu is never drawn, the identical rule
 * [ago.chat.android.analytics.AnalyticsReportsOverflowMenu]'s own doc comment states). `expanded` is local,
 * presentation-only state - the same "a menu's open/closed flag is not data a caller or a test needs to
 * inject" precedent [TagsSection]'s own `AddTagControl` already establishes - and is closed *before* its
 * callback runs, never after, the same ordering that composable's own doc comment gives a reason for. */
@Composable
private fun ContactDetailRowMenu(
    showEdit: Boolean,
    showConfirm: Boolean,
    showMarkInvalid: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
    onMarkInvalid: () -> Unit,
) {
    if (!showEdit && !showConfirm && !showMarkInvalid) return

    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(
                imageVector = AgoIcons.MoreVertical,
                contentDescription = stringResource(R.string.contact_details_row_actions),
            )
        }
        // `26-177`: [ScrimmedDropdownMenu] in place of a plain `DropdownMenu` - see its own doc comment for
        // why this menu, like every other in the app, now dims the screen behind it while open.
        ScrimmedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (showEdit) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.contact_details_action_edit)) },
                    onClick = {
                        expanded = false
                        onEdit()
                    },
                )
            }
            if (showConfirm) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.contact_details_action_confirm)) },
                    onClick = {
                        expanded = false
                        onConfirm()
                    },
                )
            }
            if (showMarkInvalid) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.contact_details_action_mark_invalid)) },
                    onClick = {
                        expanded = false
                        onMarkInvalid()
                    },
                )
            }
        }
    }
}

/** The assessment word beside the value, for whichever of the two settable states
 * [ContactDetail.assessment] currently holds - `"Unset"` (the vast majority of rows, and every `Name` row)
 * draws nothing. «Подтверждено» carries [AgoIcons.Check] beside it in a neutral colour; «Недействительно»
 * is the word alone in `error` - a word either way, never a colour alone (design Q7, `MastersBody`'s own
 * «Неактивен» rule). */
@Composable
private fun AssessmentWord(assessment: String) {
    when (assessment) {
        ASSESSMENT_CONFIRMED ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AssessmentGlyphGap)) {
                Icon(
                    imageVector = AgoIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(AssessmentGlyphSize),
                )
                Text(
                    text = stringResource(R.string.contact_details_assessment_confirmed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        ASSESSMENT_INVALID ->
            Text(
                text = stringResource(R.string.contact_details_assessment_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

        else -> Unit
    }
}

/** `26-169`/console `assessable` parity: only a `Phone`/`Email` row's own channel can be confirmed or
 * marked invalid - a `Name` has no channel to assert about (design §3, `ContactDetailsPanel.tsx`'s own
 * `assessable`). A row of an unrecognised kind is treated as not assessable, the conservative default -
 * offering an action the server would refuse anyway teaches nothing an honest hidden menu does not. */
private fun assessable(kind: String): Boolean = kind == "Phone" || kind == "Email"

/** The row error line's own text - a genuine server refusal shown verbatim regardless of which of the
 * three writes produced it, or one of three write-specific generic lines for a transport failure (this
 * section's own [RowActionError] doc comment states why the [RowActionError.Failed] arm is not shared). */
@Composable
private fun rowErrorText(error: RowActionError): String =
    when (error) {
        is RowActionError.Refused -> error.detail
        is RowActionError.Failed.Reveal -> stringResource(R.string.contact_details_reveal_failed)
        is RowActionError.Failed.Edit -> stringResource(R.string.contact_details_edit_failed)
        is RowActionError.Failed.Assessment -> stringResource(R.string.contact_details_assessment_failed)
    }

/** The section heading, reusing [ago.chat.android.ui.components.SectionLabel]'s typographic treatment at
 * the panel's own gutter (see this file's doc comment on why the shared composable itself is not used). */
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

/** Maps `Ago.Chat.Domain.VisitorContactDetailKind`'s own wire spelling ([ContactDetail.kind], unparsed)
 * to its localized field label. An unrecognised kind renders its raw wire spelling rather than being
 * dropped — the same "the classification lives in `:core:domain`, an unknown value is shown as-is, never
 * guessed at" discipline the state chip's own `StateChipWord` follows. */
@Composable
private fun fieldLabel(kind: String): String =
    when (kind) {
        "Name" -> stringResource(R.string.contact_details_label_name)
        "Phone" -> stringResource(R.string.contact_details_label_phone)
        "Email" -> stringResource(R.string.contact_details_label_email)
        else -> kind
    }

/** `26-148`: the section's root test tag - a stable hook for `ContactDetailsSectionTest` independent of
 * the (Russian, wording-sensitive) heading text, the same reasoning
 * [ago.chat.android.thread.contactpanel.CONTACT_PANEL_TEST_TAG]'s own doc comment gives. */
internal const val CONTACT_DETAILS_SECTION_TEST_TAG: String = "contactDetailsSection"

// The absent-value placeholder, a bare em-dash literal the same way `ConfirmedBookingsScreen` renders an
// absent cell - not a translatable string (a dash reads identically in every locale).
private const val VALUE_ABSENT = "—"

// `Ago.Chat.Domain.VisitorContactDetailAssessment`'s own wire spelling for the two settable values -
// verbatim, unparsed, the identical discipline `ContactDetail.assessment`'s own doc comment states.
// `"Unset"` needs no constant here: it is the `else` branch everywhere it is checked.
private const val ASSESSMENT_CONFIRMED = "Confirmed"
private const val ASSESSMENT_INVALID = "Invalid"

private val RowSpacing = 8.dp
private val SpinnerSize = 16.dp
private val SpinnerStroke = 2.dp
private val AssessmentGlyphSize = 14.dp
private val AssessmentGlyphGap = 4.dp
