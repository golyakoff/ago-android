package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.businessLocalTimeOrNull
import ago.chat.android.core.domain.recut.RecutBooking
import ago.chat.android.core.domain.recut.RecutBookingStatus
import ago.chat.android.core.domain.recut.RecutConfirmation
import ago.chat.android.core.domain.recut.RecutDay
import ago.chat.android.core.domain.recut.RecutDecision
import ago.chat.android.core.domain.recut.RecutPreview
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * `26-172` (`26-155` part 4, the epic's own final part): the «Пересчёт» drill-down's own modal page —
 * the identical chrome [WorkerScheduleDrillDownPage]/[WorkerSlotsDrillDownPage]'s own doc comments state
 * in full (this page rides the same `26-170` navigation, one level over the Мастера list, never over
 * Записи's operational view; [onBack] returns to the Masters list, never further, Q8 of
 * `docs/design/26-155-*.md`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkerRecutDrillDownPage(
    workerDisplayName: String?,
    state: WorkerRecutUiState,
    onBack: () -> Unit,
    onFromChanged: (String) -> Unit,
    onPreview: () -> Unit,
    onDecide: (String, RecutDecision) -> Unit,
    onRequestConfirm: () -> Unit,
    onDismissConfirm: () -> Unit,
    onConfirm: () -> Unit,
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
                                workerDisplayName?.let { stringResource(R.string.worker_recut_page_title, it) }
                                    ?: stringResource(R.string.worker_recut_tab),
                        )
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                WorkerRecutBody(
                    state = state,
                    onFromChanged = onFromChanged,
                    onPreview = onPreview,
                    onDecide = onDecide,
                    onRequestConfirm = onRequestConfirm,
                    onDismissConfirm = onDismissConfirm,
                    onConfirm = onConfirm,
                    onReveal = onReveal,
                )
            }
        }
    }
}

/**
 * The body proper — one flat, scrollable [LazyColumn] rather than the exclusive `when` over sealed
 * arms every sibling drill-down body draws ([WorkerScheduleBody]/[WorkerSlotsBody]), because
 * [WorkerRecutUiState] is not step-gated either ([WorkerRecutUiState]'s own class doc comment): the
 * «Пересчитать с» field and «Предпросмотр» button are always the first item, the day cards and «Готово»
 * result are independently-optional sections beneath it, and the `AlertDialog` confirm (Q3) is an
 * overlay, not a step of the column.
 */
@Composable
internal fun WorkerRecutBody(
    state: WorkerRecutUiState,
    onFromChanged: (String) -> Unit,
    onPreview: () -> Unit,
    onDecide: (String, RecutDecision) -> Unit,
    onRequestConfirm: () -> Unit,
    onDismissConfirm: () -> Unit,
    onConfirm: () -> Unit,
    onReveal: (String) -> Unit,
) {
    when (state) {
        WorkerRecutUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
        is WorkerRecutUiState.Loaded ->
            WorkerRecutLoadedBody(
                loaded = state,
                onFromChanged = onFromChanged,
                onPreview = onPreview,
                onDecide = onDecide,
                onRequestConfirm = onRequestConfirm,
                onDismissConfirm = onDismissConfirm,
                onConfirm = onConfirm,
                onReveal = onReveal,
            )
    }
}

@Composable
private fun WorkerRecutLoadedBody(
    loaded: WorkerRecutUiState.Loaded,
    onFromChanged: (String) -> Unit,
    onPreview: () -> Unit,
    onDecide: (String, RecutDecision) -> Unit,
    onRequestConfirm: () -> Unit,
    onDismissConfirm: () -> Unit,
    onConfirm: () -> Unit,
    onReveal: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        loaded.error?.let { error -> ActionErrorBanner(error = error, modifier = Modifier.fillMaxWidth()) }

        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            item(key = "from-field") {
                Column {
                    SingleDatePickerField(
                        label = stringResource(R.string.worker_recut_from_label),
                        value = loaded.from,
                        enabled = !loaded.previewing,
                        onPicked = onFromChanged,
                    )
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Button(onClick = onPreview, enabled = !loaded.previewing) {
                            Text(text = stringResource(R.string.worker_recut_preview_button))
                        }
                    }
                }
            }

            loaded.result?.let { result ->
                item(key = "result") { RecutResultCard(result = result) }
            }

            loaded.preview?.let { preview ->
                if (preview.days.all { it.bookings.isEmpty() && it.availableSlotsToDelete == 0 }) {
                    item(key = "nothing-generated") {
                        Text(
                            text = stringResource(R.string.worker_recut_nothing_generated_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                items(preview.days, key = { "day-${it.localDate}" }) { day ->
                    RecutDayCard(
                        day = day,
                        decisions = loaded.decisions,
                        revealingPersonIds = loaded.revealingPersonIds,
                        onDecide = onDecide,
                        onReveal = onReveal,
                    )
                    HorizontalDivider()
                }

                item(key = "review-and-confirm") {
                    val enabled = everyDecisionMade(preview, loaded.decisions)
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Button(onClick = onRequestConfirm, enabled = enabled) {
                            Text(text = stringResource(R.string.worker_recut_review_and_confirm_button))
                        }
                        if (!enabled) {
                            Text(
                                text = stringResource(R.string.worker_recut_choose_decision_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (loaded.confirming) {
        loaded.preview?.let { preview ->
            RecutConfirmDialog(
                preview = preview,
                decisions = loaded.decisions,
                busy = loaded.busy,
                onConfirm = onConfirm,
                onDismiss = onDismissConfirm,
            )
        }
    }
}

@Composable
private fun RecutResultCard(result: RecutConfirmation) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.worker_recut_done_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text =
                stringResource(
                    R.string.worker_recut_result_summary,
                    result.recutDays.size,
                    result.skippedDays.size,
                    result.slotsDeleted,
                    result.slotsInserted,
                    result.bookingsCancelled,
                ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (result.skippedDays.isNotEmpty()) {
            Text(
                text = stringResource(R.string.worker_recut_result_left_in_old_grid, result.skippedDays.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RecutDayCard(
    day: RecutDay,
    decisions: Map<String, RecutDecision>,
    revealingPersonIds: Set<String>,
    onDecide: (String, RecutDecision) -> Unit,
    onReveal: (String) -> Unit,
) {
    val kept = dayIsKept(day, decisions)
    val keptNote = stringResource(R.string.worker_recut_day_kept_note)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        SectionLabel(text = if (kept) "${day.localDate} $keptNote" else day.localDate)
        if (day.availableSlotsToDelete > 0) {
            Text(
                text = stringResource(R.string.worker_recut_day_slots_note, day.availableSlotsToDelete),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        if (day.bookings.isEmpty()) {
            Text(
                text = stringResource(R.string.worker_recut_no_bookings_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            day.bookings.forEach { booking ->
                RecutBookingRow(
                    booking = booking,
                    decision = decisions[booking.bookingId],
                    revealing = booking.personId != null && booking.personId in revealingPersonIds,
                    onDecide = { decision -> onDecide(booking.bookingId, decision) },
                    onReveal = { booking.personId?.let(onReveal) },
                )
            }
        }
    }
}

/**
 * One booking inside a re-cut day: the business-local time range, the service (or «—»), the person and
 * masked/revealed phone (the identical [IdentifierText]/«Показать» shape [WorkerSlotsScreen.kt][WorkerSlotRow]
 * already draws, restated for [RecutBooking] rather than [ago.chat.android.core.domain.workerslots.WorkerSlot]),
 * the status word, and either the Отменить/Оставить `FilterChip` pair or, for a recorded no-show, the
 * note stating why it has neither.
 */
@Composable
private fun RecutBookingRow(
    booking: RecutBooking,
    decision: RecutDecision?,
    revealing: Boolean,
    onDecide: (RecutDecision) -> Unit,
    onReveal: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${businessLocalTimeOrNull(booking.startsAt) ?: "—"}–${businessLocalTimeOrNull(booking.endsAt) ?: "—"}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.width(88.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = booking.serviceName ?: "—", style = MaterialTheme.typography.bodySmall)
                booking.personId?.let { personId ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IdentifierText(id = personId, style = MaterialTheme.typography.bodySmall)
                        booking.phone?.let { phone ->
                            Text(
                                text = phone,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                            if (booking.masked) {
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
            Text(
                text = recutBookingStatusLabel(booking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (booking.canDecide) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                FilterChip(
                    selected = decision == RecutDecision.Cancel,
                    onClick = { onDecide(RecutDecision.Cancel) },
                    label = { Text(text = stringResource(R.string.worker_recut_decision_cancel)) },
                )
                FilterChip(
                    selected = decision == RecutDecision.Keep,
                    onClick = { onDecide(RecutDecision.Keep) },
                    label = { Text(text = stringResource(R.string.worker_recut_decision_keep)) },
                )
            }
        } else {
            Text(
                text = stringResource(R.string.worker_recut_already_no_show_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** [RecutBookingStatus]'s own three wire spellings, worded - [RecutBookingStatus.Unknown] renders
 * [RecutBooking.rawStatus] verbatim rather than a translated word, the identical reason
 * [WorkerSlotsScreen.kt][workerSlotStatusLabel] already gives for its own defensive arm. */
@Composable
private fun recutBookingStatusLabel(booking: RecutBooking): String =
    when (booking.status) {
        RecutBookingStatus.PendingConfirmation -> stringResource(R.string.worker_slots_status_pending_confirmation)
        RecutBookingStatus.Booked -> stringResource(R.string.worker_slots_status_booked)
        RecutBookingStatus.NoShow -> stringResource(R.string.worker_slots_status_no_show)
        RecutBookingStatus.Unknown -> booking.rawStatus
    }

/**
 * Q3's accepted confirm step (`docs/design/26-155-*.md`) — the app's own destructive `AlertDialog`
 * idiom, the identical shape `MastersBody`'s own delete confirmation and
 * [ago.chat.android.schedule.WorkingHoursBody]'s own delete confirmation already draw, over the four
 * numbers `ago-console`'s own confirm panel states (`CalendarWorkerRecutPage.tsx`'s own
 * `daysToBeRecut`/`availableSlotsToDelete`/`bookingsToBeCancelled`/`daysToBeSkipped`).
 */
@Composable
private fun RecutConfirmDialog(
    preview: RecutPreview,
    decisions: Map<String, RecutDecision>,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.worker_recut_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text =
                        stringResource(
                            R.string.worker_recut_confirm_message,
                            daysToRecut(preview, decisions).size,
                            availableSlotsToDelete(preview, decisions),
                            bookingsToCancel(preview, decisions),
                            daysToSkip(preview, decisions).size,
                        ),
                )
                Text(
                    text = stringResource(R.string.worker_recut_cannot_be_undone),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(text = stringResource(R.string.worker_recut_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}
