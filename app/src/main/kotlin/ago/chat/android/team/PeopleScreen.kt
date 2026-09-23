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
 */
@Composable
public fun PeopleRoute(viewModel: PeopleViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PeopleScreen(state = state, onRetry = viewModel::refresh)
}

/** The stateless half — [PeopleRoute] wires the [PeopleViewModel] above it, the same "route wires,
 * screen renders" split every other screen in this app already follows. No `Scaffold`/`TopAppBar` of
 * its own: this body renders inside [ago.chat.android.team.TeamScreen]'s one shared Команда app bar,
 * the same body-only shape that screen's own chat content takes for its sibling segment. */
@Composable
internal fun PeopleScreen(
    state: PeopleUiState,
    onRetry: () -> Unit,
) {
    when (state) {
        PeopleUiState.Loading -> PeopleLoadingBody()
        is PeopleUiState.Failed -> PeopleRefusalBody(reason = state.reason, onRetry = onRetry)
        is PeopleUiState.Loaded ->
            if (state.members.isEmpty()) {
                PeopleEmptyBody()
            } else {
                PeopleContent(members = state.members, seatSummary = state.seatSummary)
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
private fun PeopleEmptyBody() {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
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
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
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
 * ternary already takes. */
@Composable
private fun roleDisplayName(roleName: String): String =
    when (roleName) {
        ROLE_ADMIN -> stringResource(R.string.people_role_admin)
        else -> stringResource(R.string.people_role_operator)
    }
