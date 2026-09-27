package ago.chat.android.team

import ago.chat.android.R
import ago.chat.android.core.domain.team.OperatorInviteListItem
import ago.chat.android.core.domain.team.OperatorInviteStatus
import ago.chat.android.core.domain.team.OperatorTeamFailure
import ago.chat.android.core.domain.team.OperatorTeamMember
import ago.chat.android.core.domain.team.ROLE_ADMIN
import ago.chat.android.core.domain.team.ROLE_OPERATOR
import ago.chat.android.core.domain.team.RoleSeatSummary
import ago.chat.android.ui.components.IdentifierText
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `26-55`: Люди, for real — the site's own operator roster and per-role seat summary, read-only.
 * Obtains its own [PeopleViewModel] via [hiltViewModel] — the identical wiring
 * [ago.chat.android.bookings.BookingsRoute] already establishes for Записи.
 *
 * Drawn only inside [ago.chat.android.team.TeamScreen]'s own segmented control, never as a standalone
 * destination — an operator lacking `site:manage_operators` never reaches this composable at all
 * ([TeamRoute]'s own doc comment).
 *
 * `26-56`: also this screen's own invite sheet host. [createdInvite] is the one value on this whole
 * screen that must never be lost to a rotation or a process death — the invite's own plaintext code,
 * shown exactly once — so it lives here, in `rememberSaveable`, one level *above*
 * [InviteColleagueSheet]'s own composition, the identical shape [ConversationsTabHost]'s own
 * `openConversationId`/`ThreadRoute` split already establishes for the open conversation.
 * [showInviteSheet] gates the sheet's presence independently of [PeopleUiState] itself — the sheet, once
 * opened, stays mounted (and so keeps [createdInvite] alive) even through a transient `Loading` tick this
 * screen's own [PeopleViewModel] can pass through on relaunch, before `state` has a chance to answer
 * `Loaded` again.
 */
@Composable
public fun PeopleRoute(
    viewModel: PeopleViewModel = hiltViewModel(),
    invitesViewModel: OperatorInvitesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val invitesState by invitesViewModel.state.collectAsStateWithLifecycle()
    var showInviteSheet by rememberSaveable { mutableStateOf(false) }
    var createdInvite by rememberSaveable { mutableStateOf<CreatedInviteUi?>(null) }

    PeopleScreen(
        state = state,
        invitesState = invitesState,
        onRetry = viewModel::refresh,
        onRetryInvites = invitesViewModel::refresh,
        onRevokeInvite = invitesViewModel::revoke,
        onInviteClicked = { showInviteSheet = true },
        onChangeRole = viewModel::changeRole,
        onRemoveOperator = viewModel::removeOperator,
        onToggleSeat = viewModel::toggleSeat,
    )

    if (showInviteSheet) {
        InviteColleagueSheet(
            seatSummary = (state as? PeopleUiState.Loaded)?.seatSummary.orEmpty(),
            createdInvite = createdInvite,
            onInviteCreated = { createdInvite = it },
            onDismiss = {
                showInviteSheet = false
                createdInvite = null
                // `26-242`: a freshly created invite is a new pending row — re-read the list so it shows
                // the moment the sheet closes, the same "no cache, just reload after a write"
                // `OperatorsTeamPage`'s own `loadInvites()` runs after its create succeeds.
                invitesViewModel.refresh()
            },
        )
    }
}

/** The stateless half — [PeopleRoute] wires the [PeopleViewModel] above it, the same "route wires,
 * screen renders" split every other screen in this app already follows. No `Scaffold`/`TopAppBar` of
 * its own: this body renders inside [ago.chat.android.team.TeamScreen]'s one shared Команда app bar,
 * the same body-only shape that screen's own chat content takes for its sibling segment. */
@Composable
internal fun PeopleScreen(
    state: PeopleUiState,
    invitesState: OperatorInvitesUiState,
    onRetry: () -> Unit,
    onRetryInvites: () -> Unit,
    onRevokeInvite: (String) -> Unit,
    onInviteClicked: () -> Unit,
    onChangeRole: (operatorId: String, newRoleName: String) -> Unit,
    onRemoveOperator: (operatorId: String) -> Unit,
    onToggleSeat: (operatorId: String, roleName: String, holdsSeat: Boolean) -> Unit,
) {
    // `26-242`: the revoke confirmation lives here rather than in the view model — it is transient UI
    // intent, not state the server or a rotation needs to survive. `remember` (not `rememberSaveable`):
    // losing an open confirmation to a rotation simply closes it, and the invite is still there to
    // revoke again, the same low-stakes posture `InviteColleagueViewModel`'s own doc comment takes for a
    // half-typed form. The whole «Люди» segment is only reachable behind `site:manage_operators`
    // (`TeamRoute`'s own doc comment), which is the identical permission `ago-chat`'s own
    // `ListOperatorInvitesHandler`/`RevokeOperatorInviteHandler` gate on — so nothing here needs a second
    // gate of its own; an operator who cannot manage operators never sees this screen at all.
    var revokeTarget by remember { mutableStateOf<OperatorInviteListItem?>(null) }
    // `26-253`: the role-change and removal confirmations, held the same transient way — a role change
    // and a removal are both real, consequence-bearing changes to a colleague's access, so each is
    // confirmed before it fires (`ChangeOperatorRoleButton`/`RemoveOperatorButton`'s own dialogs). The
    // seat toggle takes none, deliberately: it is reversible with the same tap and loses no data
    // (`SeatToggleButton`'s own doc comment), so it fires straight through.
    var changeRoleTarget by remember { mutableStateOf<OperatorTeamMember?>(null) }
    var removeTarget by remember { mutableStateOf<OperatorTeamMember?>(null) }

    when (state) {
        PeopleUiState.Loading -> PeopleLoadingBody()
        is PeopleUiState.Failed -> PeopleRefusalBody(reason = state.reason, onRetry = onRetry)
        is PeopleUiState.Loaded ->
            Column(modifier = Modifier.fillMaxSize()) {
                InviteColleagueButtonRow(onInviteClicked = onInviteClicked)
                PeopleContent(
                    members = state.members,
                    seatSummary = state.seatSummary,
                    pendingWrite = state.pendingWrite,
                    writeRefusal = state.writeRefusal,
                    invitesState = invitesState,
                    onRetryInvites = onRetryInvites,
                    onRevokeClicked = { revokeTarget = it },
                    onChangeRoleClicked = { changeRoleTarget = it },
                    onRemoveClicked = { removeTarget = it },
                    onToggleSeat = onToggleSeat,
                    modifier = Modifier.weight(1f),
                )
            }
    }

    revokeTarget?.let { target ->
        RevokeInviteConfirmDialog(
            invite = target,
            onConfirm = {
                onRevokeInvite(target.operatorInviteId)
                revokeTarget = null
            },
            onDismiss = { revokeTarget = null },
        )
    }

    changeRoleTarget?.let { target ->
        ChangeRoleConfirmDialog(
            member = target,
            onConfirm = { newRoleName ->
                onChangeRole(target.operatorId, newRoleName)
                changeRoleTarget = null
            },
            onDismiss = { changeRoleTarget = null },
        )
    }

    removeTarget?.let { target ->
        RemoveOperatorConfirmDialog(
            member = target,
            onConfirm = {
                onRemoveOperator(target.operatorId)
                removeTarget = null
            },
            onDismiss = { removeTarget = null },
        )
    }
}

/** `docs/backlog/26-56-*.md`'s own mockup graph: `People -- "Пригласить" --> InviteSheet` — a persistent
 * header action, present once the roster has loaded regardless of whether it turned out empty (unlike
 * `ago-console`'s own `Panel` `actions` slot, which sits *inside* the non-empty seat-summary panel, this
 * app's own empty state has nothing to attach a header action to, so this row sits above both branches
 * instead of inside either one). */
@Composable
private fun InviteColleagueButtonRow(onInviteClicked: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Button(onClick = onInviteClicked) {
            Text(text = stringResource(R.string.people_invite_button))
        }
    }
}

@Composable
private fun PeopleLoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** `docs/backlog/26-55-*.md`'s own Done-when: "a read failure renders as a refusal with a retry, never
 * as a raw exception class name" — [failureMessage] is the one place this screen turns
 * [OperatorTeamFailure] into the Russian sentence, never [ago.chat.android.core.network.team.KtorOperatorTeamApi]
 * (that adapter's own doc comment: classification lives there, wording lives here — the identical split
 * `BookingsScreen`'s own `RefusalBody`/`failureMessage` already establish). */
@Composable
private fun PeopleRefusalBody(
    reason: OperatorTeamFailure,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = failureMessage(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun failureMessage(reason: OperatorTeamFailure): String =
    when (reason) {
        OperatorTeamFailure.Transport -> stringResource(R.string.people_load_failed_transport)
        OperatorTeamFailure.Unexpected -> stringResource(R.string.people_load_failed_unexpected)
    }

@Composable
private fun PeopleContent(
    members: List<OperatorTeamMember>,
    seatSummary: List<RoleSeatSummary>,
    pendingWrite: OperatorWriteInFlight?,
    writeRefusal: OperatorWriteRefusal?,
    invitesState: OperatorInvitesUiState,
    onRetryInvites: () -> Unit,
    onRevokeClicked: (OperatorInviteListItem) -> Unit,
    onChangeRoleClicked: (OperatorTeamMember) -> Unit,
    onRemoveClicked: (OperatorTeamMember) -> Unit,
    onToggleSeat: (operatorId: String, roleName: String, holdsSeat: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (seatSummary.isNotEmpty()) {
            item(key = "seat-summary") {
                SeatSummaryPanel(seatSummary = seatSummary)
                HorizontalDivider()
            }
        }
        if (members.isEmpty()) {
            // `26-55` kept the empty roster as its own centred body; `26-242` folds it into this one
            // LazyColumn instead so the invite list below still scrolls into view even in the (in
            // practice unreachable — a site always has its founder) no-operators case.
            item(key = "roster-empty") {
                Text(
                    text = stringResource(R.string.people_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                HorizontalDivider()
            }
        } else {
            items(members, key = { it.operatorId }) { member ->
                OperatorCard(
                    member = member,
                    pendingWrite = pendingWrite?.takeIf { it.operatorId == member.operatorId },
                    refusal = writeRefusal?.takeIf { it.operatorId == member.operatorId }?.reason,
                    onChangeRoleClicked = { onChangeRoleClicked(member) },
                    onRemoveClicked = { onRemoveClicked(member) },
                    onToggleSeat = onToggleSeat,
                )
                HorizontalDivider()
            }
        }

        invitesSection(
            invitesState = invitesState,
            onRetryInvites = onRetryInvites,
            onRevokeClicked = onRevokeClicked,
        )
    }
}

/**
 * `26-242`: the sent/pending-invite list, drawn beneath the roster in the same scroll. Its own load
 * lifecycle ([OperatorInvitesUiState]), independent of the roster's — a header plus one row per invite
 * once the list has loaded with at least one entry; nothing at all while it is still loading or came
 * back empty (the same "shown only when at least one invite exists" `OperatorsTeamPage` follows); a
 * small inline error with a retry when the read itself failed, so an invite-read failure never blanks a
 * roster that loaded fine.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.invitesSection(
    invitesState: OperatorInvitesUiState,
    onRetryInvites: () -> Unit,
    onRevokeClicked: (OperatorInviteListItem) -> Unit,
) {
    when (invitesState) {
        OperatorInvitesUiState.Loading -> Unit
        is OperatorInvitesUiState.Failed ->
            item(key = "invites-failed") {
                InvitesLoadFailedRow(reason = invitesState.reason, onRetry = onRetryInvites)
            }
        is OperatorInvitesUiState.Loaded ->
            if (invitesState.invites.isNotEmpty()) {
                item(key = "invites-header") {
                    Text(
                        text = stringResource(R.string.people_invites_list_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    if (invitesState.revokeFailed) {
                        Text(
                            text = stringResource(R.string.people_invite_revoke_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = agoStatusColors().dangerText,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                        )
                    }
                    HorizontalDivider()
                }
                items(invitesState.invites, key = { "invite-${it.operatorInviteId}" }) { invite ->
                    InviteRow(
                        invite = invite,
                        revoking = invitesState.revokingId == invite.operatorInviteId,
                        onRevokeClicked = { onRevokeClicked(invite) },
                    )
                    HorizontalDivider()
                }
            }
    }
}

/** `docs/backlog/26-55-*.md`'s own Scope item 4: one line per role, held against limit, with the
 * over-limit state drawn as the server's own read-time [RoleSeatSummary.overLimit] — never recomputed
 * here (`OperatorTeamApi`'s own doc comment on why that flag is read verbatim). */
@Composable
private fun SeatSummaryPanel(seatSummary: List<RoleSeatSummary>) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        seatSummary.forEach { role -> SeatSummaryRow(role = role) }
    }
}

@Composable
private fun SeatSummaryRow(role: RoleSeatSummary) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = roleDisplayName(role.roleName), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.people_seats_summary_value, role.heldSeats, role.limit),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            )
            if (role.overLimit) {
                Text(
                    text = stringResource(R.string.people_over_limit_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = agoStatusColors().dangerText,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * `docs/backlog/26-55-*.md`'s own Scope item 3: name (or identifier), email, and **one seat line per
 * role** — never one per person. An operator with two roles shows two seat facts, the direct
 * consequence of [OperatorTeamMember.roles] being a list at all ([ago.chat.android.core.domain.team.OperatorRoleSeat]'s
 * own doc comment).
 *
 * `26-253`: also this row's own three writes, mirroring `OperatorsTeamPage`'s per-row actions — a seat
 * toggle beside each role's own badge, plus a change-role and a remove action for the operator as a
 * whole. [pendingWrite] is non-null only when *this* row has a write in flight (the caller filters it by
 * operator id); the control that fired it shows a spinner in its place and takes no second tap, the same
 * in-flight-replaces-the-control shape `InviteRow`'s own revoke already uses. [refusal] is this row's own
 * last refusal (if any), rendered inline beneath the actions the same way `OperatorsTeamPage` shows each
 * button's own failure.
 */
@Composable
private fun OperatorCard(
    member: OperatorTeamMember,
    pendingWrite: OperatorWriteInFlight?,
    refusal: OperatorWriteRefusalReason?,
    onChangeRoleClicked: () -> Unit,
    onRemoveClicked: () -> Unit,
    onToggleSeat: (operatorId: String, roleName: String, holdsSeat: Boolean) -> Unit,
) {
    val anyWriteInFlight = pendingWrite != null
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val nameStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        val displayName = member.displayName
        if (displayName != null) {
            Text(text = displayName, style = nameStyle)
        } else {
            // `docs/backlog/26-55-*.md`'s own Scope item 5: no name on file renders through this app's
            // existing `IdentifierText` — `OperatorsTeamPage.tsx`'s own identical fallback
            // (`operatorId.slice(0, 8)`), reached here through the one composable every id already goes
            // through, never a second, ad hoc truncation.
            IdentifierText(id = member.operatorId, style = nameStyle)
        }

        member.email?.let { email ->
            Text(
                text = email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        member.roles.forEach { role ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = roleDisplayName(role.roleName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                SeatBadge(holdsSeat = role.holdsSeat)
                Spacer(modifier = Modifier.weight(1f))
                val seatToggleInFlight =
                    pendingWrite?.action == OperatorWriteAction.ToggleSeat && pendingWrite.roleName == role.roleName
                if (seatToggleInFlight) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(
                        onClick = { onToggleSeat(member.operatorId, role.roleName, !role.holdsSeat) },
                        enabled = !anyWriteInFlight,
                    ) {
                        Text(text = seatToggleLabel(roleName = role.roleName, holdsSeat = role.holdsSeat))
                    }
                }
            }
        }

        // `26-253`: the operator-level actions, mirroring `ChangeOperatorRoleButton`/`RemoveOperatorButton`.
        // A change of role and a removal are both confirmed first (this screen's own dialogs), so these
        // buttons only open the confirmation — the write itself fires from the dialog's confirm.
        val isAdmin = member.roles.any { it.roleName == ROLE_ADMIN }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pendingWrite?.action == OperatorWriteAction.ChangeRole) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                val changeRoleLabel =
                    if (isAdmin) R.string.people_change_role_to_operator_button else R.string.people_change_role_to_admin_button
                TextButton(onClick = onChangeRoleClicked, enabled = !anyWriteInFlight) {
                    Text(text = stringResource(changeRoleLabel))
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (pendingWrite?.action == OperatorWriteAction.Remove) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onRemoveClicked, enabled = !anyWriteInFlight) {
                    Text(
                        text = stringResource(R.string.people_remove_button),
                        color = agoStatusColors().dangerText,
                    )
                }
            }
        }

        refusal?.let {
            Text(
                text = operatorWriteRefusalMessage(it),
                style = MaterialTheme.typography.bodySmall,
                color = agoStatusColors().dangerText,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** `26-253`: the seat toggle's own label, role-qualified and stating the row's next state — the direct
 * mirror of `SeatToggleButton`'s own `operatorsTeamGrant/RevokeOperator/AdminSeatButton` four-way choice
 * (a row can show one toggle per role, so an unqualified "Grant/Revoke seat" would be ambiguous). */
@Composable
private fun seatToggleLabel(
    roleName: String,
    holdsSeat: Boolean,
): String =
    stringResource(
        when {
            roleName == ROLE_ADMIN && holdsSeat -> R.string.people_seat_revoke_admin_button
            roleName == ROLE_ADMIN -> R.string.people_seat_grant_admin_button
            holdsSeat -> R.string.people_seat_revoke_operator_button
            else -> R.string.people_seat_grant_operator_button
        },
    )

/** `26-253`: the one place a [OperatorWriteRefusalReason] becomes a Russian sentence — the identical
 * `:app`-words-it split `inviteRefusalMessage`/`networkFailureText` already draw. [OperatorWriteRefusalReason.ServerRefusal]
 * shows the server's own `detail` verbatim (`ago-console` shows the identical message for these codes);
 * every other arm is worded here. */
@Composable
private fun operatorWriteRefusalMessage(reason: OperatorWriteRefusalReason): String =
    when (reason) {
        OperatorWriteRefusalReason.LastManager -> stringResource(R.string.people_write_last_manager)
        is OperatorWriteRefusalReason.SeatFull ->
            stringResource(R.string.people_invite_role_seat_full, roleDisplayName(reason.roleName))
        is OperatorWriteRefusalReason.ServerRefusal -> reason.detail
        is OperatorWriteRefusalReason.Unavailable -> networkFailureText(reason.reason)
    }

/** `26-253`: `ChangeOperatorRoleButton`'s own confirm-before-firing dialog — a role change is a real
 * authorization change either way (it can end a colleague's ability to administer, or grant it), so it
 * names what the colleague gains or loses before the tap commits. The direction is decided by whether the
 * operator already holds the `Admin` role, exactly as the console's own button decides. */
@Composable
private fun ChangeRoleConfirmDialog(
    member: OperatorTeamMember,
    onConfirm: (newRoleName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isAdmin = member.roles.any { it.roleName == ROLE_ADMIN }
    val newRoleName = if (isAdmin) ROLE_OPERATOR else ROLE_ADMIN
    val name = member.displayName ?: member.operatorId.take(8)
    val bodyRes =
        if (isAdmin) R.string.people_change_role_to_operator_dialog_body else R.string.people_change_role_to_admin_dialog_body
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.people_change_role_dialog_title)) },
        text = { Text(text = stringResource(bodyRes, name)) },
        confirmButton = {
            TextButton(onClick = { onConfirm(newRoleName) }) {
                Text(text = stringResource(R.string.people_change_role_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.people_invite_cancel))
            }
        },
    )
}

/** `26-253`: `RemoveOperatorButton`'s own confirm-before-firing dialog — removal states its consequence
 * (the operator's assigned conversations return to the waiting queue, and the removal cannot be undone),
 * not merely the fact, and names the colleague so the operator removes the one they meant to. */
@Composable
private fun RemoveOperatorConfirmDialog(
    member: OperatorTeamMember,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = member.displayName ?: member.operatorId.take(8)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.people_remove_dialog_title)) },
        text = { Text(text = stringResource(R.string.people_remove_dialog_body, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.people_remove_confirm_button),
                    color = agoStatusColors().dangerText,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.people_invite_cancel))
            }
        },
    )
}

/** `ConversationListScreen`'s own private `StatusPill` shape, restated locally rather than shared — the
 * identical "this file is the shape's only caller" reasoning `TeamChatScreen`'s own `TeamAdminBadge`
 * already gives. */
@Composable
private fun SeatBadge(holdsSeat: Boolean) {
    Surface(
        color = if (holdsSeat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (holdsSeat) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(
            text = stringResource(if (holdsSeat) R.string.people_seat_held else R.string.people_seat_not_held),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** `OperatorsTeamPage.tsx`'s own `roleDisplayName`, restated — every role name this deployment has ever
 * seeded is one of the two named here; a role name this app does not recognise still renders (as the
 * Operator wording) rather than crashing, the same fail-open-to-a-label posture the console's own
 * ternary already takes. `internal`, not `private` — `26-56`'s own invite sheet (`InviteColleagueSheet.kt`)
 * reads the identical wording for its role picker and its at-capacity refusal, rather than growing a
 * second copy of this exact `when`. */
@Composable
internal fun roleDisplayName(roleName: String): String =
    when (roleName) {
        ROLE_ADMIN -> stringResource(R.string.people_role_admin)
        else -> stringResource(R.string.people_role_operator)
    }

/** `26-242`: one sent invite — email, its computed status, the sent/expiry dates, and a revoke action
 * offered only on a still-pending row ([OperatorInviteStatus.isRevocable]). A revoke in flight for this
 * row shows a spinner in place of the button (hide-not-disable applies to the whole feature at the
 * segment gate; within the row, an in-flight revoke replaces the control so it cannot be tapped twice).
 * The status of a terminal invite (Revoked/Redeemed/Expired) shows plainly, with no action at all. */
@Composable
private fun InviteRow(
    invite: OperatorInviteListItem,
    revoking: Boolean,
    onRevokeClicked: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = invite.email,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = inviteStatusLabel(invite),
                style = MaterialTheme.typography.labelMedium,
                color =
                    when (invite.status) {
                        OperatorInviteStatus.SendFailed -> agoStatusColors().dangerText
                        OperatorInviteStatus.Sent -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = stringResource(R.string.people_invite_sent_label, inviteDateLabel(invite.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = stringResource(R.string.people_invite_expires_label, inviteDateLabel(invite.expiresAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (revoking) {
            CircularProgressIndicator(modifier = Modifier.padding(start = 12.dp).size(24.dp), strokeWidth = 2.dp)
        } else if (invite.status.isRevocable) {
            TextButton(onClick = onRevokeClicked, modifier = Modifier.padding(start = 8.dp)) {
                Text(text = stringResource(R.string.people_invite_revoke_button))
            }
        }
    }
}

/** The invite read (not the roster) failed on its own — a compact inline row with a retry, deliberately
 * not the full-screen [PeopleRefusalBody]: the roster above loaded fine and must stay on screen. Reuses
 * [failureMessage], the same Russian wording the roster's own refusal uses for the two failure kinds. */
@Composable
private fun InvitesLoadFailedRow(
    reason: OperatorTeamFailure,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = failureMessage(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

/** `26-242`: the destructive-action confirmation before a revoke fires — revoke is not trivially
 * reversible (the invitee can no longer redeem the link), so it is confirmed first, the same
 * confirm-before-firing shape `CannedResponsesScreen`'s own delete dialog takes. Names the email so the
 * operator revokes the invite they meant to. */
@Composable
private fun RevokeInviteConfirmDialog(
    invite: OperatorInviteListItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.people_invite_revoke_dialog_title)) },
        text = { Text(text = stringResource(R.string.people_invite_revoke_dialog_body, invite.email)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.people_invite_revoke_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.people_invite_cancel))
            }
        },
    )
}

@Composable
private fun inviteStatusLabel(invite: OperatorInviteListItem): String =
    when (invite.status) {
        OperatorInviteStatus.Sent -> stringResource(R.string.people_invite_status_sent)
        // `${wording} ${code}` - the identical shape `OperatorsTeamPage`'s own `inviteStatusLabel` builds
        // for a delivery failure, the SMTP code appended (or "?" when the server sent none).
        OperatorInviteStatus.SendFailed ->
            "${stringResource(R.string.people_invite_status_send_failed)} ${invite.smtpErrorCode ?: "?"}"
        OperatorInviteStatus.Revoked -> stringResource(R.string.people_invite_status_revoked)
        OperatorInviteStatus.Redeemed -> stringResource(R.string.people_invite_status_redeemed)
        OperatorInviteStatus.Expired -> stringResource(R.string.people_invite_status_expired)
    }

/** The server's raw ISO-8601 instant, rendered in the device's own zone (`date-and-time.md`: "render in
 * the user's zone"). `null`-safe parse falls back to the raw string rather than crashing — the same
 * `runCatching { … }.getOrNull()` posture every `*OrNull` formatter in this app already takes. The date
 * pattern itself is `PhoneRevealsReportScreen`'s own `d MMM yyyy, HH:mm`, restated here rather than
 * shared: that screen's formatter is `private` to it, and this is the only other caller. */
@Composable
private fun inviteDateLabel(iso: String): String =
    runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(INVITE_DATE_FORMAT) }
        .getOrNull() ?: iso

private val INVITE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru"))
