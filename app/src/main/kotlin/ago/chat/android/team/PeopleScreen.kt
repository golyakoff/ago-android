package ago.chat.android.team

import ago.chat.android.R
import ago.chat.android.core.domain.team.OperatorTeamFailure
import ago.chat.android.core.domain.team.OperatorTeamMember
import ago.chat.android.core.domain.team.ROLE_ADMIN
import ago.chat.android.core.domain.team.RoleSeatSummary
import ago.chat.android.ui.components.IdentifierText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
public fun PeopleRoute(viewModel: PeopleViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showInviteSheet by rememberSaveable { mutableStateOf(false) }
    var createdInvite by rememberSaveable { mutableStateOf<CreatedInviteUi?>(null) }

    PeopleScreen(state = state, onRetry = viewModel::refresh, onInviteClicked = { showInviteSheet = true })

    if (showInviteSheet) {
        InviteColleagueSheet(
            seatSummary = (state as? PeopleUiState.Loaded)?.seatSummary.orEmpty(),
            createdInvite = createdInvite,
            onInviteCreated = { createdInvite = it },
            onDismiss = {
                showInviteSheet = false
                createdInvite = null
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
    onRetry: () -> Unit,
    onInviteClicked: () -> Unit,
) {
    when (state) {
        PeopleUiState.Loading -> PeopleLoadingBody()
        is PeopleUiState.Failed -> PeopleRefusalBody(reason = state.reason, onRetry = onRetry)
        is PeopleUiState.Loaded ->
            Column(modifier = Modifier.fillMaxSize()) {
                InviteColleagueButtonRow(onInviteClicked = onInviteClicked)
                if (state.members.isEmpty()) {
                    PeopleEmptyBody(modifier = Modifier.weight(1f))
                } else {
                    PeopleContent(members = state.members, seatSummary = state.seatSummary, modifier = Modifier.weight(1f))
                }
            }
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

@Composable
private fun PeopleEmptyBody(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.people_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (seatSummary.isNotEmpty()) {
            item(key = "seat-summary") {
                SeatSummaryPanel(seatSummary = seatSummary)
                HorizontalDivider()
            }
        }
        items(members, key = { it.operatorId }) { member ->
            OperatorCard(member = member)
            HorizontalDivider()
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
                    color = MaterialTheme.colorScheme.error,
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
 */
@Composable
private fun OperatorCard(member: OperatorTeamMember) {
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
            Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = roleDisplayName(role.roleName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                SeatBadge(holdsSeat = role.holdsSeat)
            }
        }
    }
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
