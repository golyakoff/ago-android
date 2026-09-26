package ago.chat.android.thread.contactpanel.sections

import ago.chat.android.R
import ago.chat.android.thread.contactpanel.CloseActionError
import ago.chat.android.thread.contactpanel.RestrictionActionError
import ago.chat.android.thread.contactpanel.RestrictionSectionState
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * `26-153`: the sixth and final section of the contact-detail panel — «Закрыть диалог» + the reversible
 * «Ограничить»/«Снять ограничение» action (`docs/design/26-111-contact-panel-slices.md`'s own S-K line:
 * "confirm dialogs; close dismisses + returns to queue"). One stateless composable, its own file under
 * `thread/contactpanel/sections/`, called from
 * [ago.chat.android.thread.contactpanel.ContactDetailPanel]'s marked insertion point — the additive
 * convention that container's own doc comment prescribes, so nothing in the shell, the header or the five
 * sections above it is restructured to add this one. It takes plain state and plain callbacks, no view
 * model and no Hilt — the same shape [TagsSection]/[NotesSection]/[AttachmentUploadSection] already
 * establish, which is what lets this file's own test drive it with a hand-built state.
 *
 * ## Two independently-gated buttons, not one section-wide gate
 *
 * Unlike [AttachmentUploadSection] (one permission gates its one control), this section holds two
 * unrelated write capabilities — `conversation:close` and `conversation:block`
 * (`docs/design/26-111-thread-contact-detail-panel.md`'s own Appendix table) — and an operator may hold
 * either, both, or neither. Each button is hidden on its own permission (hide-not-disable, design Q7);
 * the section itself draws nothing only when *both* are absent. The view model stays permission-agnostic
 * regardless (it always exposes [ago.chat.android.thread.contactpanel.ContactPanelViewModel.closeConversation]/
 * [ago.chat.android.thread.contactpanel.ContactPanelViewModel.toggleRestriction]) — the gate lives here in
 * the UI layer, the identical split every sibling section's own doc comment states for its own permission
 * Boolean.
 *
 * ## Confirm dialogs on both actions
 *
 * `docs/design/26-111-thread-contact-detail-panel.md` §"Per-action confirmation shape": both A1 (close)
 * and A2 (restrict) get a confirmation dialog on this panel — unlike [AttachmentUploadSection]'s own
 * single-tap toggle, closing ends the operator's work item for good and blocking is destructive to the
 * visitor's own access, so a confirm dialog sits between the tap and the write for both, mirroring the
 * identical shape [ago.chat.android.conversations.ConversationListScreen]'s own `EraseConfirmDialog`
 * already establishes for this app's other irreversible-feeling swipe action. **Reversal (lift) also gets
 * its own confirm dialog** — the ticket's own scope line is explicit that each of the two directions of
 * the reversible action asks first, not only the forward one.
 *
 * ## Wording mirrors `ago-console`'s own dialogs where the same action exists there
 *
 * `closeConversationDialogTitle`/`closeConversationDialogBody` and `blockVisitorDialogTitle`/
 * `blockVisitorDialogBody` (`ago-console/src/i18n/ru.ts`) are carried over near-verbatim — the same
 * action, described the same way on both surfaces, the identical choice
 * [ago.chat.android.thread.contactpanel.sections.AttachmentUploadSection]'s own doc comment states for its
 * own wording. The lift confirmation has no console dialog to mirror (the console's own
 * `RestrictedVisitorsPage` lifts with a plain button, no dialog) — its wording is this panel's own, kept
 * to the same shape as the other two.
 *
 * ## Why [RestrictionSectionState.Unavailable] draws nothing
 *
 * That arm means no visitor id is known for this conversation at all (`RestrictionSectionState`'s own doc
 * comment on the restored-thread edge case) — there is no visitor to block or lift a block from, so the
 * honest rendering is the same "nothing here" this section already gives an operator without
 * `conversation:block`, not an inline error about a read that was never attempted.
 */
@Composable
internal fun ConversationActionsSection(
    restriction: RestrictionSectionState,
    closing: Boolean,
    closeError: CloseActionError?,
    canClose: Boolean,
    onClose: () -> Unit,
    canRestrict: Boolean,
    onToggleRestriction: () -> Unit,
    onRetryRestriction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!canClose && !canRestrict) return

    Column(modifier = modifier.fillMaxWidth().testTag(CONVERSATION_ACTIONS_SECTION_TEST_TAG)) {
        if (canClose) {
            CloseConversationControl(closing = closing, closeError = closeError, onClose = onClose)
        }

        if (canRestrict) {
            RestrictionControl(
                state = restriction,
                onToggle = onToggleRestriction,
                onRetry = onRetryRestriction,
                modifier = if (canClose) Modifier.padding(top = ButtonSpacing) else Modifier,
            )
        }
    }
}

/** «Закрыть диалог» — a confirm dialog, then [onClose]. `docs/design/26-111-thread-contact-detail-panel.md`
 * §4, A1: "confirmation dialog... on success the sheet dismisses and the thread returns to the queue" —
 * the dismiss-and-return itself is [ago.chat.android.thread.ThreadRoute]'s own job, reached through
 * [ago.chat.android.thread.contactpanel.ContactPanelViewModel.conversationClosed]; this control only ever
 * asks and calls back. */
@Composable
private fun CloseConversationControl(
    closing: Boolean,
    closeError: CloseActionError?,
    onClose: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    Button(
        onClick = { confirming = true },
        enabled = !closing,
        modifier = Modifier.fillMaxWidth().testTag(CLOSE_CONVERSATION_BUTTON_TEST_TAG),
    ) {
        Text(
            text =
                stringResource(
                    if (closing) R.string.conversation_actions_closing_button else R.string.conversation_actions_close_button,
                ),
        )
    }

    closeError?.let { error ->
        Text(
            text =
                when (error) {
                    is CloseActionError.Refused -> error.detail
                    is CloseActionError.Failed -> stringResource(R.string.conversation_actions_close_failed)
                },
            style = MaterialTheme.typography.bodySmall,
            color = agoStatusColors().dangerText,
            modifier = Modifier.padding(top = RowSpacing),
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(text = stringResource(R.string.conversation_actions_close_confirm_title)) },
            text = { Text(text = stringResource(R.string.conversation_actions_close_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onClose()
                    },
                ) {
                    Text(text = stringResource(R.string.conversation_actions_close_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** «Ограничить» / «Снять ограничение», each behind its own confirm dialog — this control's own
 * [RestrictionSectionState] arms mirror [AttachmentUploadSection]'s Loading/Failed-with-retry/Loaded
 * shape, restated here for a reversible block rather than a reversible grant. */
@Composable
private fun RestrictionControl(
    state: RestrictionSectionState,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        RestrictionSectionState.Loading ->
            CircularProgressIndicator(
                modifier = modifier.padding(top = RowSpacing).size(SpinnerSize),
                strokeWidth = SpinnerStroke,
            )

        // No visitor id is known for this conversation - nothing to check or act on. See this file's own
        // doc comment on why that is drawn as silently as the permission-absent case, not as an error.
        RestrictionSectionState.Unavailable -> Unit

        is RestrictionSectionState.Failed ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(top = RowSpacing)) {
                Text(
                    text = stringResource(R.string.conversation_actions_restriction_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) {
                    Text(text = stringResource(R.string.action_retry))
                }
            }

        is RestrictionSectionState.Loaded -> LoadedRestriction(state, onToggle, modifier)
    }
}

@Composable
private fun LoadedRestriction(
    state: RestrictionSectionState.Loaded,
    onToggle: () -> Unit,
    modifier: Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    Button(
        onClick = { confirming = true },
        enabled = !state.toggling,
        // Destructive-styled only for the forward direction (§4: "Destructive-styled") - lifting a
        // restriction is a plain reversal, not a second destructive verb, so it keeps this app's ordinary
        // button colours.
        colors =
            if (!state.restricted) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        modifier = modifier.fillMaxWidth().testTag(RESTRICTION_BUTTON_TEST_TAG),
    ) {
        Text(
            text =
                stringResource(
                    when {
                        state.toggling && state.restricted -> R.string.conversation_actions_lifting_button
                        state.toggling -> R.string.conversation_actions_restricting_button
                        state.restricted -> R.string.conversation_actions_lift_button
                        else -> R.string.conversation_actions_restrict_button
                    },
                ),
        )
    }

    state.actionError?.let { error ->
        Text(
            text =
                when (error) {
                    is RestrictionActionError.Refused -> error.detail
                    is RestrictionActionError.Failed -> stringResource(R.string.conversation_actions_restriction_action_failed)
                },
            style = MaterialTheme.typography.bodySmall,
            color = agoStatusColors().dangerText,
            modifier = Modifier.padding(top = RowSpacing),
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = {
                Text(
                    text =
                        stringResource(
                            if (state.restricted) {
                                R.string.conversation_actions_lift_confirm_title
                            } else {
                                R.string.conversation_actions_restrict_confirm_title
                            },
                        ),
                )
            },
            text = {
                Text(
                    text =
                        stringResource(
                            if (state.restricted) {
                                R.string.conversation_actions_lift_confirm_body
                            } else {
                                R.string.conversation_actions_restrict_confirm_body
                            },
                        ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onToggle()
                    },
                ) {
                    Text(
                        text =
                            stringResource(
                                if (state.restricted) {
                                    R.string.conversation_actions_lift_confirm_action
                                } else {
                                    R.string.conversation_actions_restrict_confirm_action
                                },
                            ),
                        color = if (!state.restricted) agoStatusColors().dangerText else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** `26-153`: the section's own root test tag - a stable hook for `ConversationActionsSectionTest`
 * independent of the (Russian, wording-sensitive) button labels, the same reasoning
 * [ATTACHMENT_UPLOAD_SECTION_TEST_TAG]'s own doc comment gives. */
internal const val CONVERSATION_ACTIONS_SECTION_TEST_TAG: String = "conversationActionsSection"

/** `26-153`: the «Закрыть диалог» button's own test hook - independent of its (Russian) label, which
 * itself changes between the idle and in-flight arms. */
internal const val CLOSE_CONVERSATION_BUTTON_TEST_TAG: String = "closeConversationButton"

/** `26-153`: the «Ограничить»/«Снять ограничение» button's own test hook - independent of its (Russian)
 * label, which itself changes across all four Loaded/toggling combinations. */
internal const val RESTRICTION_BUTTON_TEST_TAG: String = "restrictionButton"

private val RowSpacing = 8.dp
private val ButtonSpacing = 12.dp
private val SpinnerSize = 16.dp
private val SpinnerStroke = 2.dp
