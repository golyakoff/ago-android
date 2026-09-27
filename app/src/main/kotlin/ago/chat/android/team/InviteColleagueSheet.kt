package ago.chat.android.team

import ago.chat.android.R
import ago.chat.android.core.domain.team.RoleSeatSummary
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.theme.agoStatusColors
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.Serializable

/**
 * `26-56`: the one-shot invite result, carried in [PeopleRoute]'s own `rememberSaveable` — never in
 * [InviteColleagueViewModel]'s state, that class's own doc comment says why. [Serializable] rather than
 * `@Parcelize`: this module has never taken the `kotlin-parcelize` plugin for one screen's one saved
 * value, and `rememberSaveable`'s own default `Saver` already round-trips any [Serializable] through the
 * saved-instance-state `Bundle` with no library of its own. [shareUrl] is already the full
 * `{consoleUrl}/invite/{code}` link ([InviteColleagueViewModel.submit]'s own doc comment) — the plaintext
 * `code` never exists as a bare value past that point, which is one fewer place for it to leak into a log
 * or a crash report by accident.
 */
internal data class CreatedInviteUi(
    val shareUrl: String,
    val sendFailed: Boolean,
) : Serializable

/**
 * `26-56`: the invite bottom sheet's own host. [createdInvite] is [PeopleRoute]'s own state, not this
 * composable's — passed in and reported back through [onInviteCreated] rather than held here, precisely
 * so the one-shot code survives even if *this* composable is torn down and rebuilt (`PeopleRoute`'s own
 * doc comment on why it lives at that level, the identical shape `ConversationsTabHost`'s own
 * `openConversationId`/`ThreadRoute` split already establishes for the open conversation).
 *
 * **Why the sheet cannot be swiped, tapped-outside, or backed away from once [createdInvite] is set.**
 * Material3's [ModalBottomSheet] funnels an outside tap and a back press through [onDismissRequest]
 * directly (guarded below by the same `createdInvite == null` check), but settles a *drag* to `Hidden`
 * through the sheet's own state machine first, which [onDismissRequest] never gets a say in —
 * `confirmValueChange` (passed to `rememberModalBottomSheetState`) is what vetoes that transition instead.
 * [explicitDismissRequested] is what tells the two apart from the *button*-driven close: without it,
 * `confirmValueChange` would also veto the "Готово" button's own [requestDismiss] call once a result
 * exists, since from that predicate's own point of view a programmatic `hide()` and a swipe are the
 * identical attempted transition to `Hidden`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InviteColleagueSheet(
    seatSummary: List<RoleSeatSummary>,
    createdInvite: CreatedInviteUi?,
    onInviteCreated: (CreatedInviteUi) -> Unit,
    onDismiss: () -> Unit,
    viewModel: InviteColleagueViewModel = hiltViewModel(),
) {
    val formState by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var explicitDismissRequested by remember { mutableStateOf(false) }
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { target -> target != SheetValue.Hidden || createdInvite == null || explicitDismissRequested },
        )

    fun requestDismiss() {
        explicitDismissRequested = true
        scope
            .launch { sheetState.hide() }
            .invokeOnCompletion {
                explicitDismissRequested = false
                if (!sheetState.isVisible) onDismiss()
            }
    }

    ModalBottomSheet(
        onDismissRequest = { if (createdInvite == null) requestDismiss() },
        sheetState = sheetState,
    ) {
        if (createdInvite != null) {
            InviteResultBody(invite = createdInvite, onDone = ::requestDismiss)
        } else {
            InviteFormBody(
                state = formState,
                seatSummary = seatSummary,
                onEmailChanged = viewModel::emailChanged,
                onRoleToggled = viewModel::roleToggled,
                onCancel = ::requestDismiss,
                onSubmit = {
                    viewModel.submit(seatSummary) { shareUrl, sendFailed ->
                        onInviteCreated(CreatedInviteUi(shareUrl = shareUrl, sendFailed = sendFailed))
                    }
                },
            )
        }
    }
}

/**
 * `docs/backlog/26-56-*.md`'s own Scope item 2, generalised by `26-241`: email, a **multi-select** of
 * the two real role names (a checkbox each, at least one required), and a submit that blocks before ever
 * calling the server once [InviteColleagueViewModel.submit]'s own pre-flight says a selected role is at
 * capacity or nothing is selected — [seatSummary] is threaded straight through from
 * [PeopleUiState.Loaded], never re-read here, and is what both the per-role availability line and the
 * submit-disabled gate (`submitBlocked`) read.
 */
@Composable
private fun InviteFormBody(
    state: InviteColleagueFormState,
    seatSummary: List<RoleSeatSummary>,
    onEmailChanged: (String) -> Unit,
    onRoleToggled: (String, Boolean) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    // `26-241`: mirrors the pre-flight [InviteColleagueViewModel.submit] re-checks — submit is disabled
    // while no role is ticked or any ticked role's own pool is full, with the form (and its checkboxes)
    // left visible so a tenant can untick a full role and invite the rest, never a whole-form dead-end.
    val submitBlocked =
        state.roleNames.isEmpty() || state.roleNames.any { roleSeatFull(seatSummary, it) }

    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Text(
            text = stringResource(R.string.people_invite_dialog_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )

        OutlinedTextField(
            value = state.email,
            onValueChange = onEmailChanged,
            label = { Text(text = stringResource(R.string.people_invite_email_label)) },
            singleLine = true,
            enabled = !state.submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )

        Text(
            text = stringResource(R.string.people_invite_roles_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        // `26-241`: one checkbox per seeded role, in `INVITE_ROLE_ORDER` (the same order `roleNames` goes
        // on the wire in) — the Android mirror of `ago-console`'s own checkbox list. Each row names the
        // role and its own live seat availability; a full role's count is shown in the danger colour so
        // the reason a ticked role blocks submit is visible on the row itself, not only in the message.
        INVITE_ROLE_ORDER.forEach { roleName ->
            val roleSummary = seatSummary.firstOrNull { it.roleName == roleName }
            val full = roleSeatFull(seatSummary, roleName)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = state.roleNames.contains(roleName),
                    onCheckedChange = { onRoleToggled(roleName, it) },
                    enabled = !state.submitting,
                )
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(text = roleDisplayName(roleName), style = MaterialTheme.typography.bodyLarge)
                    if (roleSummary != null) {
                        Text(
                            text = stringResource(R.string.people_invite_role_seats_taken, roleSummary.heldSeats, roleSummary.limit),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (full) agoStatusColors().dangerText else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        state.refusal?.let { refusal ->
            Text(
                text = inviteRefusalMessage(refusal),
                style = MaterialTheme.typography.bodySmall,
                color = agoStatusColors().dangerText,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        Button(
            onClick = onSubmit,
            enabled = !state.submitting && !submitBlocked,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        ) {
            Text(text = stringResource(if (state.submitting) R.string.people_invite_sending else R.string.people_invite_submit))
        }
        TextButton(
            onClick = onCancel,
            enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        ) {
            Text(text = stringResource(R.string.people_invite_cancel))
        }
    }
}

@Composable
private fun inviteRefusalMessage(refusal: InviteRefusalUi): String =
    when (refusal) {
        InviteRefusalUi.EmptyEmail -> stringResource(R.string.people_invite_email_required)
        InviteRefusalUi.NoRoleSelected -> stringResource(R.string.people_invite_no_role_selected)
        is InviteRefusalUi.RolesAtCapacity ->
            // `26-241`: one line per full role — `map` is inline, so the @Composable `roleDisplayName`
            // and `stringResource` calls inside its lambda are legal. Both the client-side pre-flight
            // (every selected full role) and a server `402` (the one role it refused) render here.
            refusal.roleNames
                .map { stringResource(R.string.people_invite_role_seat_full, roleDisplayName(it)) }
                .joinToString("\n")
        is InviteRefusalUi.ServerRefusal -> refusal.detail
        is InviteRefusalUi.Unavailable -> networkFailureText(refusal.reason)
    }

/**
 * `docs/backlog/26-56-*.md`'s own Scope item 4: **the primary action is the Android share sheet**, not
 * the copy button beside it — [Button] (filled, the app's own primary emphasis) for
 * [R.string.people_invite_share_button], [OutlinedButton] for the copy fallback, the emphasis ordering
 * `ago-console`'s own dialog has no way to draw at all — that item's own Found section states why: "a
 * browser has nowhere to send a link to", which is exactly the gap a phone's share sheet closes. Scope
 * item 5: [CreatedInviteUi.sendFailed] renders as a warning banner on an otherwise-successful screen,
 * never as this screen's own failure state — the code above and the share/copy actions below are
 * identical either way.
 */
@Composable
private fun InviteResultBody(
    invite: CreatedInviteUi,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        if (invite.sendFailed) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.people_invite_send_failed_warning),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            Text(
                text = stringResource(R.string.people_invite_success_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        }

        Text(
            text = stringResource(R.string.people_invite_success_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )

        Text(
            text = invite.shareUrl,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        Button(
            onClick = {
                val shareIntent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, invite.shareUrl)
                    }
                try {
                    context.startActivity(Intent.createChooser(shareIntent, null))
                } catch (missing: ActivityNotFoundException) {
                    // A device with no share target at all, the only way this throws - the identical
                    // posture `MainActivity.openInBrowser` already takes: nothing useful to offer in its
                    // place, and the link is still on screen, copyable, either way.
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        ) {
            Text(text = stringResource(R.string.people_invite_share_button))
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    // "invite-link" is the OS-level clip label (shown, if at all, only in system UI
                    // like a clipboard history), never user-facing copy - it needs no string resource.
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("invite-link", invite.shareUrl)))
                    copied = true
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(text = stringResource(R.string.people_invite_copy_button))
        }
        if (copied) {
            Text(
                text = stringResource(R.string.people_invite_copied_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        TextButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        ) {
            Text(text = stringResource(R.string.people_invite_done_button))
        }
    }
}
