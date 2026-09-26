package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.businessLocalTimeOrNull
import ago.chat.android.core.domain.workerslots.WorkerSlot
import ago.chat.android.core.domain.workerslots.WorkerSlotStatus
import ago.chat.android.core.domain.workerslots.groupSlotsByDay
import ago.chat.android.ui.components.IdentifierText
import ago.chat.android.ui.components.SectionLabel
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * `26-171` (`26-155` part 3): the «Слоты» drill-down's own modal page — the identical chrome
 * [WorkerScheduleDrillDownPage]'s own doc comment states in full (this page rides the same `26-170`
 * navigation, one level over the Мастера list, never over Записи's operational view; [onBack] returns to
 * the Masters list, never further, Q8 of `docs/design/26-155-*.md`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkerSlotsDrillDownPage(
    workerDisplayName: String?,
    state: WorkerSlotsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onReveal: (String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    title = {
                        Text(
                            text =
                                workerDisplayName?.let { stringResource(R.string.worker_slots_page_title, it) }
                                    ?: stringResource(R.string.worker_slots_tab),
                        )
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                WorkerSlotsBody(state = state, onRetry = onRetry, onReveal = onReveal)
            }
        }
    }
}

/**
 * The four-arm body proper — built on [WorkerScheduleBody]'s own reused [LoadingBody]/[EmptyBody]/
 * [RefusalBody]/[ActionErrorBanner], plus [RefusedBody] for [WorkerSlotsUiState.Refused]'s own genuine
 * server sentence ([WorkerSlotsUiState]'s own class doc comment on why that arm exists at all).
 *
 * **One flat [LazyColumn], grouped by day, never merged by `bookingId`** — [groupSlotsByDay]'s own doc
 * comment states why two slots of one multi-slot run stay two separate rows.
 */
@Composable
internal fun WorkerSlotsBody(
    state: WorkerSlotsUiState,
    onRetry: () -> Unit,
    onReveal: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state is WorkerSlotsUiState.Loaded) {
            state.actionError?.let { error -> ActionErrorBanner(error = error, modifier = Modifier.fillMaxWidth()) }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                WorkerSlotsUiState.Loading -> LoadingBody()
                WorkerSlotsUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
                is WorkerSlotsUiState.Refused -> RefusedBody(detail = state.detail, onRetry = onRetry)
                is WorkerSlotsUiState.Failed ->
                    RefusalBody(
                        reason = state.reason,
                        onRetry = onRetry,
                        unexpectedMessageRes = R.string.worker_slots_load_failed_unexpected,
                    )

                is WorkerSlotsUiState.Loaded ->
                    if (state.slots.isEmpty()) {
                        EmptyBody(stringResource(R.string.worker_slots_empty))
                    } else {
                        WorkerSlotsList(state = state, onReveal = onReveal)
                    }
            }
        }
    }
}

/** [WorkerSlotsUiState.Refused]'s own rendering — the identical layout [RefusalBody] draws, for a raw
 * server [detail] rather than an [ago.chat.android.core.domain.bookings.BookingsQueueFailure]
 * classification (this arm is unreachable in practice, [WorkerSlotsUiState]'s own class doc comment, but
 * kept real rather than folded away). */
@Composable
private fun RefusedBody(
    detail: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = detail,
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
private fun WorkerSlotsList(
    state: WorkerSlotsUiState.Loaded,
    onReveal: (String) -> Unit,
) {
    val days = groupSlotsByDay(state.slots)
    val weekdayLabels = stringArrayResource(R.array.bookings_weekday_full)

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        days.forEach { day ->
            item(key = "header-${day.localDate}") {
                SectionLabel(text = "${day.localDate} · ${weekdayLabels.getOrElse(day.weekday) { "" }}")
            }
            items(day.slots, key = { it.eventId }) { slot ->
                WorkerSlotRow(
                    slot = slot,
                    revealing = slot.personId != null && slot.personId in state.revealingPersonIds,
                    onReveal = { slot.personId?.let(onReveal) },
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * One slot: the business-local time range, the status word, the service (or «—»), and — only when
 * [WorkerSlot.personId] is present — the person, rendered through [IdentifierText] rather than an
 * invented name (`docs/design/26-155-*.md`'s own degraded rendering for a person this screen does not
 * resolve a display name for). The phone is drawn only when the server sent one at all
 * ([WorkerSlot.phone]'s own doc comment), masked with the identical «Показать»/«Показ…» reveal pair
 * `26-53` already established, never a second copy of that string pair for this fourth surface.
 */
@Composable
private fun WorkerSlotRow(
    slot: WorkerSlot,
    revealing: Boolean,
    onReveal: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${businessLocalTimeOrNull(slot.startsAt) ?: "—"}–${businessLocalTimeOrNull(slot.endsAt) ?: "—"}",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.width(88.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(text = workerSlotStatusLabel(slot), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = slot.serviceName ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            slot.personId?.let { personId ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IdentifierText(id = personId, style = MaterialTheme.typography.bodySmall)
                    slot.phone?.let { phone ->
                        Text(
                            text = phone,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        if (slot.masked) {
                            TextButton(onClick = onReveal, enabled = !revealing) {
                                Text(
                                    text =
                                        stringResource(
                                            if (revealing) {
                                                R.string.bookings_contacts_revealing_phone
                                            } else {
                                                R.string.bookings_contacts_reveal_phone
                                            },
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** [WorkerSlotStatus]'s own six wire spellings, worded — [WorkerSlotStatus.Unknown] renders
 * [WorkerSlot.rawStatus] verbatim rather than a translated word, the identical reason that enum's own doc
 * comment states for keeping it. */
@Composable
private fun workerSlotStatusLabel(slot: WorkerSlot): String =
    when (slot.status) {
        WorkerSlotStatus.Available -> stringResource(R.string.worker_slots_status_available)
        WorkerSlotStatus.PendingConfirmation -> stringResource(R.string.worker_slots_status_pending_confirmation)
        WorkerSlotStatus.Booked -> stringResource(R.string.worker_slots_status_booked)
        // Q6's accepted decision (`docs/design/26-155-*.md`): shown, not filtered out.
        WorkerSlotStatus.Cancelled -> stringResource(R.string.worker_slots_status_cancelled)
        WorkerSlotStatus.NoShow -> stringResource(R.string.worker_slots_status_no_show)
        WorkerSlotStatus.Blocked -> stringResource(R.string.worker_slots_status_blocked)
        WorkerSlotStatus.Unknown -> slot.rawStatus
    }
