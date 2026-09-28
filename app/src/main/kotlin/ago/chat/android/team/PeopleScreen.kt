package ago.chat.android.team

import ago.chat.android.R
import ago.chat.android.core.domain.team.OperatorInviteEffectiveStatus
import ago.chat.android.core.domain.team.OperatorInviteListItem
import ago.chat.android.core.domain.team.OperatorInviteStatus
import ago.chat.android.core.domain.team.OperatorTeamFailure
import ago.chat.android.core.domain.team.OperatorTeamMember
import ago.chat.android.core.domain.team.ROLE_ADMIN
import ago.chat.android.core.domain.team.ROLE_OPERATOR
import ago.chat.android.core.domain.team.RoleSeatSummary
import ago.chat.android.ui.components.IdentifierText
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-55`: Люди — the site's own operator roster and per-role seat summary. `26-263`: reworked into three
 * sections the approved design (`ago-android-design/team.html`) lays out — «Активные пользователи»
 * (the roster), «Приглашения» (still-pending invites), and a collapsible «Архив» (terminal invites).
 * The sectioning is driven off each invite's [OperatorInviteEffectiveStatus] (a second, distinct status
 * from the delivery lifecycle [OperatorInviteStatus], which stays for the SMTP-failure wording), never
 * the delivery status.
 *
 * Obtains its own [PeopleViewModel]/[OperatorInvitesViewModel] via [hiltViewModel] — the roster VM and
 * the invites VM stay two separate reads (each VM's own doc comment says why), composed into the one
 * sectioned screen here.
 *
 * `26-56`/`26-263`: the invite sheet's own host. [createdInvite] — the one value on this screen that
 * must survive a rotation or process death, the invite's plaintext link shown exactly once — lives here
 * in `rememberSaveable`, one level above [InviteColleagueSheet]. [showInviteSheet] is hoisted one level
 * further, to [TeamRoute], because `26-263` moved «Пригласить» out of an inline button and into the
 * Команда top-app-bar `⋮` overflow, which [TeamScreen] owns: the overflow toggles the sheet, this route
 * hosts it and keeps [createdInvite] alive across the transient `Loading` tick a relaunch can pass
 * through.
 */
@Composable
public fun PeopleRoute(
    viewModel: PeopleViewModel = hiltViewModel(),
    invitesViewModel: OperatorInvitesViewModel = hiltViewModel(),
    showInviteSheet: Boolean = false,
    onDismissInviteSheet: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val invitesState by invitesViewModel.state.collectAsStateWithLifecycle()
    var createdInvite by rememberSaveable { mutableStateOf<CreatedInviteUi?>(null) }

    PeopleScreen(
        state = state,
        invitesState = invitesState,
        onRetry = viewModel::refresh,
        onRetryInvites = invitesViewModel::refresh,
        onRevokeInvite = invitesViewModel::revoke,
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
                onDismissInviteSheet()
                createdInvite = null
                // `26-242`: a freshly created invite is a new pending row — re-read the list so it shows
                // the moment the sheet closes, the same "no cache, just reload after a write"
                // `OperatorsTeamPage`'s own `loadInvites()` runs after its create succeeds.
                invitesViewModel.refresh()
            },
        )
    }
}

/** The stateless half — [PeopleRoute] wires the view models above it, the same "route wires, screen
 * renders" split every other screen in this app already follows. No `Scaffold`/`TopAppBar` of its own:
 * this body renders inside [ago.chat.android.team.TeamScreen]'s one shared Команда app bar (whose `⋮`
 * overflow now carries «Пригласить»), the same body-only shape that screen's own chat content takes. */
@Composable
internal fun PeopleScreen(
    state: PeopleUiState,
    invitesState: OperatorInvitesUiState,
    onRetry: () -> Unit,
    onRetryInvites: () -> Unit,
    onRevokeInvite: (String) -> Unit,
    onChangeRole: (operatorId: String, newRoleName: String) -> Unit,
    onRemoveOperator: (operatorId: String) -> Unit,
    onToggleSeat: (operatorId: String, roleName: String, holdsSeat: Boolean) -> Unit,
) {
    // `26-242`/`26-253`: the confirmations live here as transient UI intent, not state the server or a
    // rotation needs to survive (`remember`, not `rememberSaveable` — losing an open confirmation to a
    // rotation simply closes it). The whole «Люди» segment is only reachable behind
    // `site:manage_operators`, so nothing here needs a second gate of its own.
    var revokeTarget by remember { mutableStateOf<OperatorInviteListItem?>(null) }
    var changeRoleTarget by remember { mutableStateOf<OperatorTeamMember?>(null) }
    var removeTarget by remember { mutableStateOf<OperatorTeamMember?>(null) }

    when (state) {
        PeopleUiState.Loading -> PeopleLoadingBody()
        is PeopleUiState.Failed -> PeopleRefusalBody(reason = state.reason, onRetry = onRetry)
        is PeopleUiState.Loaded ->
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
            )
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

@Composable
private fun PeopleLoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** `docs/backlog/26-55-*.md`'s own Done-when: "a read failure renders as a refusal with a retry, never
 * as a raw exception class name" — [failureMessage] is the one place this screen turns
 * [OperatorTeamFailure] into the Russian sentence, never the adapter. */
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

/**
 * `26-263`: the three sections, drawn in one scroll. The seat summary panel is kept above them (its
 * `26-55` capacity read is unchanged and out of this item's scope to remove). «Активные пользователи»
 * is driven by [members]; «Приглашения» and «Архив» are driven by [invitesState]'s own list, partitioned
 * by [OperatorInviteEffectiveStatus] — an invite that is [OperatorInviteEffectiveStatus.InTeam] shows
 * through the roster above, never in the invite list.
 */
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
) {
    // `26-263`: the archive starts collapsed (`rememberSaveable` so a rotation keeps it open once the
    // operator has opened it), matching the design's own closed-by-default chevron.
    var archiveExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        if (seatSummary.isNotEmpty()) {
            item(key = "seat-summary") { SeatSummaryPanel(seatSummary = seatSummary) }
        }

        // ---- «Активные пользователи» -------------------------------------------------------------
        item(key = "active-users-header") { SectionLabel(text = stringResource(R.string.people_active_users_title)) }
        if (members.isEmpty()) {
            item(key = "roster-empty") {
                Text(
                    text = stringResource(R.string.people_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            items(members, key = { "member-${it.operatorId}" }) { member ->
                ActiveOperatorCard(
                    member = member,
                    pendingWrite = pendingWrite?.takeIf { it.operatorId == member.operatorId },
                    refusal = writeRefusal?.takeIf { it.operatorId == member.operatorId }?.reason,
                    onChangeRoleClicked = { onChangeRoleClicked(member) },
                    onRemoveClicked = { onRemoveClicked(member) },
                    onToggleSeat = onToggleSeat,
                )
            }
        }

        // ---- «Приглашения» + «Архив» -------------------------------------------------------------
        invitesSection(
            invitesState = invitesState,
            archiveExpanded = archiveExpanded,
            onToggleArchive = { archiveExpanded = !archiveExpanded },
            onRetryInvites = onRetryInvites,
            onRevokeClicked = onRevokeClicked,
        )
    }
}

/**
 * `26-263`: «Приглашения» holds only [OperatorInviteEffectiveStatus.Pending] invites (each with a revoke
 * action); «Архив» is a collapsible section holding every terminal invite
 * ([OperatorInviteEffectiveStatus.Removed]/[OperatorInviteEffectiveStatus.Revoked]/[OperatorInviteEffectiveStatus.Expired]),
 * no actions. Both come from the one invite read; nothing is drawn for either while it is still loading,
 * and an invite-read failure surfaces as a small inline retry so a roster that loaded fine stays on
 * screen.
 */
private fun LazyListScope.invitesSection(
    invitesState: OperatorInvitesUiState,
    archiveExpanded: Boolean,
    onToggleArchive: () -> Unit,
    onRetryInvites: () -> Unit,
    onRevokeClicked: (OperatorInviteListItem) -> Unit,
) {
    when (invitesState) {
        OperatorInvitesUiState.Loading -> Unit
        is OperatorInvitesUiState.Failed ->
            item(key = "invites-failed") {
                InvitesLoadFailedRow(reason = invitesState.reason, onRetry = onRetryInvites)
            }
        is OperatorInvitesUiState.Loaded -> {
            val pending = invitesState.invites.filter { it.effectiveStatus.placement() is InvitePlacement.Pending }
            val archive =
                invitesState.invites.mapNotNull { invite ->
                    (invite.effectiveStatus.placement() as? InvitePlacement.Archive)?.let { invite to it.kind }
                }

            if (pending.isNotEmpty()) {
                item(key = "invites-header") {
                    SectionLabel(text = stringResource(R.string.people_invites_list_title))
                    if (invitesState.revokeFailed) {
                        Text(
                            text = stringResource(R.string.people_invite_revoke_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = agoStatusColors().dangerText,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp),
                        )
                    }
                }
                items(pending, key = { "invite-${it.operatorInviteId}" }) { invite ->
                    PendingInviteCard(
                        invite = invite,
                        revoking = invitesState.revokingId == invite.operatorInviteId,
                        onRevokeClicked = { onRevokeClicked(invite) },
                    )
                }
            }

            if (archive.isNotEmpty()) {
                item(key = "archive-header") {
                    ArchiveSectionHeader(expanded = archiveExpanded, onToggle = onToggleArchive)
                }
                if (archiveExpanded) {
                    items(archive, key = { "archive-${it.first.operatorInviteId}" }) { (invite, kind) ->
                        ArchiveInviteCard(invite = invite, kind = kind)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// `26-263`: which section an invite lands in, decided purely from its effective status — extracted from
// the composable so it is exercised directly on a plain JVM (`PeopleInvitePlacementTest`). An
// `InTeam` invite belongs to no invite-list section at all: that operator is drawn in the roster above.
// -------------------------------------------------------------------------------------------------

internal enum class InviteArchiveKind { Removed, Revoked, Expired }

internal sealed interface InvitePlacement {
    /** «Приглашения» — a still-open invite, with a revoke action. */
    data object Pending : InvitePlacement

    /** «Архив» — a terminal invite, no action. */
    data class Archive(
        val kind: InviteArchiveKind,
    ) : InvitePlacement

    /** Shown through the roster (the operator is on the team), never in the invite list. */
    data object InRoster : InvitePlacement
}

internal fun OperatorInviteEffectiveStatus.placement(): InvitePlacement =
    when (this) {
        OperatorInviteEffectiveStatus.Pending -> InvitePlacement.Pending
        OperatorInviteEffectiveStatus.InTeam -> InvitePlacement.InRoster
        OperatorInviteEffectiveStatus.Removed -> InvitePlacement.Archive(InviteArchiveKind.Removed)
        OperatorInviteEffectiveStatus.Revoked -> InvitePlacement.Archive(InviteArchiveKind.Revoked)
        OperatorInviteEffectiveStatus.Expired -> InvitePlacement.Archive(InviteArchiveKind.Expired)
    }

/** `26-263`: the neuter pill label each archived invite shows — «Удалено»/«Отозвано»/«Истекло». Reuses
 * the existing `people_invite_status_*` wording where it already matches; `people_status_removed` is the
 * one new word. Not `@Composable`, so the mapping is unit-testable off a plain JVM. */
@StringRes
internal fun InviteArchiveKind.pillLabelRes(): Int =
    when (this) {
        InviteArchiveKind.Removed -> R.string.people_status_removed
        InviteArchiveKind.Revoked -> R.string.people_invite_status_revoked
        InviteArchiveKind.Expired -> R.string.people_invite_status_expired
    }

// -------------------------------------------------------------------------------------------------
// Cards
// -------------------------------------------------------------------------------------------------

/**
 * `26-263`: one active operator — name (or identifier), a green «В команде» pill, role chips, and a
 * «Принято <date>» line from [OperatorTeamMember.joinedAt] (omitted for the founder, whose `joinedAt`
 * is `null`). Preserves every `26-253` write: a per-role seat toggle, a change-role and a remove action,
 * each with its own in-flight spinner and inline refusal.
 */
@Composable
private fun ActiveOperatorCard(
    member: OperatorTeamMember,
    pendingWrite: OperatorWriteInFlight?,
    refusal: OperatorWriteRefusalReason?,
    onChangeRoleClicked: () -> Unit,
    onRemoveClicked: () -> Unit,
    onToggleSeat: (operatorId: String, roleName: String, holdsSeat: Boolean) -> Unit,
) {
    val anyWriteInFlight = pendingWrite != null
    PeopleCard {
        val nameStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        CardHeader(
            pill = {
                StatusPill(
                    text = stringResource(R.string.people_status_in_team),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            },
        ) {
            val displayName = member.displayName
            if (displayName != null) {
                Text(text = displayName, style = nameStyle)
            } else {
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
        }

        RoleChipRow(roleNames = member.roles.map { it.roleName })

        member.joinedAt?.let { joinedAt ->
            HintText(text = stringResource(R.string.people_accepted_at_label, fullDateLabel(joinedAt)))
        }

        // `26-253`: the per-role seat toggles, one line per role.
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
                    Text(text = stringResource(R.string.people_remove_button), color = agoStatusColors().dangerText)
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

/**
 * `26-263`: one still-pending invite — email, a lavender/accent «Ожидает» pill (the design's `.pill.live`
 * = brand tint + brand deep, mapped to Material's `primaryContainer`/`onPrimaryContainer`), role chips, a
 * «Действует до <date>» line, and the existing revoke action. The delivery-lifecycle SMTP-failure wording
 * is preserved: a pending invite whose [OperatorInviteStatus] is [OperatorInviteStatus.SendFailed] still
 * shows the "delivery failed, SMTP code" line, exactly where the previous screen did.
 */
@Composable
private fun PendingInviteCard(
    invite: OperatorInviteListItem,
    revoking: Boolean,
    onRevokeClicked: () -> Unit,
) {
    PeopleCard {
        CardHeader(
            pill = {
                StatusPill(
                    text = stringResource(R.string.people_status_pending),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            },
        ) {
            Text(
                text = invite.email,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        }

        RoleChipRow(roleNames = invite.roles)

        HintText(text = stringResource(R.string.people_invite_valid_until_label, fullDateLabel(invite.expiresAt)))

        if (invite.status == OperatorInviteStatus.SendFailed) {
            Text(
                text = "${stringResource(R.string.people_invite_status_send_failed)} ${invite.smtpErrorCode ?: "?"}",
                style = MaterialTheme.typography.bodySmall,
                color = agoStatusColors().dangerText,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (revoking) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 11.dp).size(24.dp), strokeWidth = 2.dp)
        } else {
            OutlinedButton(
                onClick = onRevokeClicked,
                modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
                border = BorderStroke(1.dp, agoStatusColors().dangerText),
            ) {
                Text(text = stringResource(R.string.people_invite_revoke_button), color = agoStatusColors().dangerText)
            }
        }
    }
}

/**
 * `26-263`: one archived invite — email, a plain grey pill with the terminal state's neuter label, role
 * chips, and the hint line for that state. No action. «Удалено» shows two lines («Принято <date>» then
 * «Удалено <date>»); «Отозвано» and «Истекло» show one.
 */
@Composable
private fun ArchiveInviteCard(
    invite: OperatorInviteListItem,
    kind: InviteArchiveKind,
) {
    PeopleCard {
        CardHeader(
            pill = {
                StatusPill(
                    text = stringResource(kind.pillLabelRes()),
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ) {
            Text(
                text = invite.email,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        }

        RoleChipRow(roleNames = invite.roles)

        when (kind) {
            InviteArchiveKind.Removed -> {
                invite.redeemedAt?.let { HintText(text = stringResource(R.string.people_accepted_at_label, fullDateLabel(it))) }
                invite.removedAt?.let { HintText(text = stringResource(R.string.people_removed_at_label, fullDateLabel(it))) }
            }
            // `26-263`: the revocation instant when the backend sent one; from an older backend that does
            // not yet carry `revokedAt`, the «Отозвано» state shows with no date rather than substituting
            // another instant that is not the revocation (the brief's own fallback).
            InviteArchiveKind.Revoked ->
                invite.revokedAt?.let { HintText(text = stringResource(R.string.people_revoked_at_label, fullDateLabel(it))) }
            InviteArchiveKind.Expired ->
                HintText(text = stringResource(R.string.people_expired_at_label, fullDateLabel(invite.expiresAt)))
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Shared card pieces
// -------------------------------------------------------------------------------------------------

/** `26-263`: the design's own `.card` — a bordered, rounded, padded surface with a small gap beneath. */
@Composable
private fun PeopleCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

/** The design's own `.cardhead` — the identifying text on the left, the status pill top-right. */
@Composable
private fun CardHeader(
    pill: @Composable () -> Unit,
    text: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) { text() }
        pill()
    }
}

/** The design's own `.pill` — a small, bold, rounded label. Colours are passed in so the one composable
 * serves the green «В команде», the accent «Ожидает» and the grey archive states alike. */
@Composable
private fun StatusPill(
    text: String,
    container: Color,
    content: Color,
) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(5.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** The design's own `.cardroles` — the role chips, wrapped. Draws nothing when there are no roles. */
@Composable
private fun RoleChipRow(roleNames: List<String>) {
    if (roleNames.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        roleNames.forEach { roleName -> RoleChip(roleName = roleName) }
    }
}

/** The design's own `.chip` — an outlined pill naming a role. */
@Composable
private fun RoleChip(roleName: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text = roleDisplayName(roleName),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/** The design's own `.hint` — a small, faint caption line. */
@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 5.dp),
    )
}

/** The design's own `.slabel` — an uppercase, tracked section heading. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 7.dp),
    )
}

/** `26-263`: the collapsible «Архив» header — the `.slabel` plus a chevron that points down when
 * collapsed and up when open. The whole row is the toggle. */
@Composable
private fun ArchiveSectionHeader(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.people_archive_title),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = AgoIcons.ChevronRight,
            // ChevronRight points right; rotate to point down (collapsed) or up (expanded).
            contentDescription = stringResource(R.string.people_archive_toggle),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(if (expanded) 270f else 90f),
        )
    }
}

/** `26-253`: the seat toggle's own label, role-qualified and stating the row's next state. */
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

/** `26-253`: the one place a [OperatorWriteRefusalReason] becomes a Russian sentence. */
@Composable
private fun operatorWriteRefusalMessage(reason: OperatorWriteRefusalReason): String =
    when (reason) {
        OperatorWriteRefusalReason.LastManager -> stringResource(R.string.people_write_last_manager)
        is OperatorWriteRefusalReason.SeatFull ->
            stringResource(R.string.people_invite_role_seat_full, roleDisplayName(reason.roleName))
        is OperatorWriteRefusalReason.ServerRefusal -> reason.detail
        is OperatorWriteRefusalReason.Unavailable -> networkFailureText(reason.reason)
    }

/** `26-253`: `ChangeOperatorRoleButton`'s own confirm-before-firing dialog. */
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

/** `26-253`: `RemoveOperatorButton`'s own confirm-before-firing dialog. */
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
                Text(text = stringResource(R.string.people_remove_confirm_button), color = agoStatusColors().dangerText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.people_invite_cancel))
            }
        },
    )
}

/** `ConversationListScreen`'s own private `StatusPill` shape, restated locally. */
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

/** `OperatorsTeamPage.tsx`'s own `roleDisplayName`, restated. `internal`, not `private` — `26-56`'s
 * invite sheet reads the identical wording for its role picker. */
@Composable
internal fun roleDisplayName(roleName: String): String =
    when (roleName) {
        ROLE_ADMIN -> stringResource(R.string.people_role_admin)
        else -> stringResource(R.string.people_role_operator)
    }

/** `docs/backlog/26-55-*.md`'s own Scope item 4: one line per role, held against limit, with the
 * over-limit state drawn as the server's own read-time [RoleSeatSummary.overLimit] — never recomputed
 * here. Kept from `26-55`; `26-263` reworks the people list around it without touching this read. */
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

/** The invite read (not the roster) failed on its own — a compact inline row with a retry. */
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

/** `26-242`: the destructive-action confirmation before a revoke fires — names the email so the operator
 * revokes the invite they meant to. */
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

/** `26-263`: the server's raw ISO-8601 instant, rendered as a full Russian date («28 сентября 2026») in
 * the device's own zone and locale (`date-and-time.md`), the same `d MMMM yyyy` + `LocalConfiguration`
 * pattern `ModulesFaqScreen`/`ConsentDocumentReaderScreen` already use. `null`-safe parse falls back to
 * the raw string rather than crashing. */
@Composable
private fun fullDateLabel(iso: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return runCatching {
        OffsetDateTime
            .parse(iso)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))
    }.getOrNull() ?: iso
}
