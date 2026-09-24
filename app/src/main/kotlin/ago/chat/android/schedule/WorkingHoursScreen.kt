package ago.chat.android.schedule

import ago.chat.android.R
import ago.chat.android.bookings.ActionErrorBanner
import ago.chat.android.bookings.EmptyBody
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.bookings.RefusalBody
import ago.chat.android.core.domain.schedule.WorkingHoursReconciliation
import ago.chat.android.core.domain.schedule.WorkingHoursRule
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * `26-97`: Записи's own «Часы» segment — the working-hours rules this tenant has, each with the Edit
 * and Delete that did not exist anywhere in this product until this item.
 *
 * **This is not «График мастера».** That screen (the `26-90` planning pass's item **E**) is a separate,
 * larger surface and does not exist in this repository yet; this is the minimum list-plus-edit the
 * promise "an existing working-hours rule can be corrected or removed" needs in order to be true on the
 * phone, placed where it is already reachable rather than behind a screen nobody has built. When **E**
 * lands, this body is what it absorbs.
 *
 * **The reconciliation is drawn above the list, never instead of it.** A correction is always allowed —
 * see [WorkingHoursViewModel]'s own doc comment for why it cannot damage a booking — so what the
 * operator is owed is not a refusal but the statement of which already-generated days still carry the
 * old hours. A screen that swallowed it would leave an already-booked slot silently unreconciled, which
 * is the one thing `26-97` explicitly must not do.
 */
@Composable
internal fun WorkingHoursBody(
    state: WorkingHoursUiState,
    onRetry: () -> Unit,
    onSave: (String, Int, String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    // Which rule's dialog is open, if any. Held here rather than in [WorkingHoursUiState] for the
    // reason every other transient dialog in this app is: it is a property of this composition, not a
    // fact the view model or a process-death restore has any business carrying.
    var editing by remember { mutableStateOf<WorkingHoursRule?>(null) }
    var confirmingDelete by remember { mutableStateOf<WorkingHoursRule?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state is WorkingHoursUiState.Loaded) {
            state.notice?.let { RecutNotice(notice = it, modifier = Modifier.fillMaxWidth()) }
            state.actionError?.let { error -> ActionErrorBanner(error = error, modifier = Modifier.fillMaxWidth()) }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                WorkingHoursUiState.Loading -> LoadingBody()
                WorkingHoursUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
                is WorkingHoursUiState.Failed ->
                    RefusalBody(
                        reason = state.reason,
                        onRetry = onRetry,
                        unexpectedMessageRes = R.string.working_hours_load_failed_unexpected,
                    )

                is WorkingHoursUiState.Loaded ->
                    if (state.rules.isEmpty()) {
                        EmptyBody(stringResource(R.string.working_hours_empty))
                    } else {
                        WorkingHoursList(
                            rules = state.rules,
                            busyRuleIds = state.busyRuleIds,
                            onEdit = { editing = it },
                            onDelete = { confirmingDelete = it },
                        )
                    }
            }
        }
    }

    editing?.let { rule ->
        EditWorkingHoursDialog(
            rule = rule,
            onDismiss = { editing = null },
            onSave = { dayOfWeek, startsAt, endsAt ->
                editing = null
                onSave(rule.ruleId, dayOfWeek, startsAt, endsAt)
            },
        )
    }

    confirmingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text(text = stringResource(R.string.working_hours_delete_title)) },
            // States what the removal will and will not change *before* it happens - the phone's own
            // half of the same honesty the reconciliation notice provides afterwards.
            text = { Text(text = stringResource(R.string.working_hours_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = null
                    onDelete(rule.ruleId)
                }) {
                    Text(text = stringResource(R.string.working_hours_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * `26-97`: what the correction did not reach, rendered from the server's own three numbers and nothing
 * else - no day arithmetic here, and no guess at whether re-cutting is worth it.
 */
@Composable
private fun RecutNotice(
    notice: WorkingHoursReconciliation,
    modifier: Modifier = Modifier,
) {
    val days = notice.alreadyCutDays.joinToString(", ")
    val from = notice.recutFrom ?: return
    Text(
        text =
            if (notice.liveBookingCount > 0) {
                stringResource(R.string.working_hours_recut_notice, days, notice.liveBookingCount, from)
            } else {
                stringResource(R.string.working_hours_recut_notice_no_bookings, days, from)
            },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun WorkingHoursList(
    rules: List<WorkingHoursRule>,
    busyRuleIds: Set<String>,
    onEdit: (WorkingHoursRule) -> Unit,
    onDelete: (WorkingHoursRule) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(rules, key = { it.ruleId }) { rule ->
            WorkingHoursCard(
                rule = rule,
                busy = rule.ruleId in busyRuleIds,
                onEdit = { onEdit(rule) },
                onDelete = { onDelete(rule) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun WorkingHoursCard(
    rule: WorkingHoursRule,
    busy: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val weekdays = stringArrayResource(R.array.working_hours_weekday)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = "${weekdays.getOrElse(rule.dayOfWeek) { "" }} ${rule.startsAt}–${rule.endsAt}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "${rule.workerName} · ${rule.calendarName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit, enabled = !busy) {
                Text(text = stringResource(R.string.working_hours_edit))
            }
            TextButton(onClick = onDelete, enabled = !busy) {
                Text(text = stringResource(R.string.working_hours_delete))
            }
        }
    }
}

/**
 * The weekday and the two wall-clock times - the three fields a human types and can mistype, and
 * deliberately no worker or calendar picker: a rule is corrected where it is, never moved, and the
 * server refuses to move one (`WorkingHoursRule.ChangeTo`).
 *
 * The times are typed as plain `HH:mm` text rather than validated here. The server already refuses a
 * window that ends before it starts, with its own sentence, and that refusal is shown verbatim - a
 * second, client-side copy of that rule is a second place for the two to disagree.
 */
@Composable
private fun EditWorkingHoursDialog(
    rule: WorkingHoursRule,
    onDismiss: () -> Unit,
    onSave: (Int, String, String) -> Unit,
) {
    val weekdays = stringArrayResource(R.array.working_hours_weekday)
    var dayOfWeek by remember(rule.ruleId) { mutableStateOf(rule.dayOfWeek) }
    var startsAt by remember(rule.ruleId) { mutableStateOf(rule.startsAt) }
    var endsAt by remember(rule.ruleId) { mutableStateOf(rule.endsAt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.working_hours_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.working_hours_day_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    weekdays.forEachIndexed { index, label ->
                        FilterChip(
                            selected = dayOfWeek == index,
                            onClick = { dayOfWeek = index },
                            label = { Text(text = label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = startsAt,
                    onValueChange = { startsAt = it },
                    label = { Text(text = stringResource(R.string.working_hours_opens_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = endsAt,
                    onValueChange = { endsAt = it },
                    label = { Text(text = stringResource(R.string.working_hours_closes_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(dayOfWeek, startsAt, endsAt) }) {
                Text(text = stringResource(R.string.working_hours_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}
