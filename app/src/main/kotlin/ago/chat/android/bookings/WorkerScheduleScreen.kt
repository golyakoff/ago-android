package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.workerschedule.ScheduleKind
import ago.chat.android.ui.components.SectionLabel
import ago.chat.android.ui.components.russianPluralStringResource
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.ceil

/**
 * `26-170` (`26-155` part 2): the «График» drill-down's own modal page — the same chrome
 * [BookingsScreen]'s own `BookingsConfigModalPage` draws for the four `⋮` screens (a back-button
 * [TopAppBar], no [ago.chat.android.ui.components.AccountAvatarAction]), one level deeper: this page sits
 * *over* the Мастера list rather than over Записи's operational view. [onBack] returns to the Masters
 * list, never to Записи (Q8, `docs/design/26-155-*.md`) — [BookingsScreen] wires it to clear only the
 * drill-down id, leaving `activeConfigTab` (and the Masters roster underneath it) exactly as it was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkerScheduleDrillDownPage(
    workerDisplayName: String?,
    state: WorkerScheduleUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onFormChanged: (WorkerScheduleForm) -> Unit,
    onSubmit: () -> Unit,
    onSwitchToHours: () -> Unit,
    onPreviewRecut: () -> Unit,
    onDismissRecutPreview: () -> Unit,
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
                                workerDisplayName?.let { stringResource(R.string.worker_schedule_page_title, it) }
                                    ?: stringResource(R.string.worker_schedule_tab),
                        )
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                WorkerScheduleBody(
                    state = state,
                    onRetry = onRetry,
                    onFormChanged = onFormChanged,
                    onSubmit = onSubmit,
                    onSwitchToHours = onSwitchToHours,
                    onPreviewRecut = onPreviewRecut,
                    onDismissRecutPreview = onDismissRecutPreview,
                )
            }
        }
    }
}

/**
 * The four-arm body proper — built on the Часы ([ago.chat.android.schedule.WorkingHoursBody]) and
 * Мастера ([MastersBody]) pattern verbatim: the same reused [LoadingBody]/[EmptyBody]/[RefusalBody], and
 * one scrollable form column rather than a list, since a worker has at most one schedule.
 *
 * **Typed `HH:mm`, not a `TimePickerDialog`** (Q4, `docs/design/26-155-*.md`) — the identical
 * `KeyboardType.Number` `OutlinedTextField` [ago.chat.android.schedule.WorkingHoursScreen]'s own
 * `EditWorkingHoursDialog` already uses, unvalidated on this side: the server already refuses a window
 * it does not like, with its own sentence, and that refusal is shown verbatim rather than duplicated
 * here as a second, potentially disagreeing rule.
 */
@Composable
internal fun WorkerScheduleBody(
    state: WorkerScheduleUiState,
    onRetry: () -> Unit,
    onFormChanged: (WorkerScheduleForm) -> Unit,
    onSubmit: () -> Unit,
    onSwitchToHours: () -> Unit,
    onPreviewRecut: () -> Unit,
    onDismissRecutPreview: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state is WorkerScheduleUiState.Loaded) {
            state.actionError?.let { error -> ActionErrorBanner(error = error, modifier = Modifier.fillMaxWidth()) }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                WorkerScheduleUiState.Loading -> LoadingBody()
                WorkerScheduleUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
                is WorkerScheduleUiState.Failed ->
                    RefusalBody(
                        reason = state.reason,
                        onRetry = onRetry,
                        unexpectedMessageRes = R.string.worker_schedule_load_failed_unexpected,
                    )

                is WorkerScheduleUiState.Loaded ->
                    WorkerScheduleFormFields(
                        state = state,
                        onFormChanged = onFormChanged,
                        onSubmit = onSubmit,
                        onSwitchToHours = onSwitchToHours,
                        onPreviewRecut = onPreviewRecut,
                    )
            }
        }
    }

    if (state is WorkerScheduleUiState.Loaded) {
        RecutHookDialog(recut = state.recut, onDismiss = onDismissRecutPreview)
    }
}

@Composable
private fun WorkerScheduleFormFields(
    state: WorkerScheduleUiState.Loaded,
    onFormChanged: (WorkerScheduleForm) -> Unit,
    onSubmit: () -> Unit,
    onSwitchToHours: () -> Unit,
    onPreviewRecut: () -> Unit,
) {
    val form = state.form
    val busy = state.formBusy
    val isCycle = form.kind == ScheduleKind.Cycle

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
    ) {
        if (state.existing == null) {
            Text(
                text = stringResource(R.string.worker_schedule_empty_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        SectionLabel(text = stringResource(R.string.worker_schedule_field_template))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !isCycle,
                onClick = { onFormChanged(form.copy(kind = ScheduleKind.Weekly)) },
                label = { Text(text = stringResource(R.string.worker_schedule_template_weekly)) },
                enabled = !busy,
            )
            FilterChip(
                selected = isCycle,
                onClick = { onFormChanged(form.copy(kind = ScheduleKind.Cycle)) },
                label = { Text(text = stringResource(R.string.worker_schedule_template_cycle)) },
                enabled = !busy,
            )
        }
        // Only the Cycle -> Weekly direction actually clears anything at save time
        // ([WorkerScheduleForm.toDraftOrNull]) - the console's own identical condition
        // (`WorkerScheduleSection.tsx`'s own `switchingAwayFromCycle`).
        if (state.existing?.kind == ScheduleKind.Cycle && !isCycle) {
            FieldCaption(text = stringResource(R.string.worker_schedule_switching_to_weekly_note))
        }

        if (isCycle) {
            SingleDatePickerField(
                label = stringResource(R.string.worker_schedule_field_cycle_anchor),
                value = form.cycleAnchor,
                enabled = !busy,
                onPicked = { onFormChanged(form.copy(cycleAnchor = it)) },
            )
            NumberField(
                label = stringResource(R.string.worker_schedule_field_cycle_working_days),
                value = form.cycleWorkingDays,
                enabled = !busy,
                onValueChange = { onFormChanged(form.copy(cycleWorkingDays = it)) },
            )
            FieldCaption(text = stringResource(R.string.worker_schedule_cycle_shift_pattern_note))
            NumberField(
                label = stringResource(R.string.worker_schedule_field_cycle_rest_days),
                value = form.cycleRestDays,
                enabled = !busy,
                onValueChange = { onFormChanged(form.copy(cycleRestDays = it)) },
            )
            TimeField(
                label = stringResource(R.string.working_hours_opens_label),
                value = form.cycleStartsAt,
                enabled = !busy,
                onValueChange = { onFormChanged(form.copy(cycleStartsAt = it)) },
            )
            TimeField(
                label = stringResource(R.string.working_hours_closes_label),
                value = form.cycleEndsAt,
                enabled = !busy,
                onValueChange = { onFormChanged(form.copy(cycleEndsAt = it)) },
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    text = stringResource(R.string.worker_schedule_weekly_hours_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The tappable half of the note (`docs/design/26-155-*.md`'s own "tappable (in-hub swap
                // to Часы)") - a button rather than an inline hyperlink: this app has no inline-link
                // text component yet, and every existing in-hub swap (`BookingsScreen`'s own
                // `onFixReadiness`) is already a plain tap target, not styled text.
                TextButton(onClick = onSwitchToHours) {
                    Text(text = stringResource(R.string.working_hours_tab))
                }
            }
        }

        SectionLabel(text = stringResource(R.string.worker_schedule_field_slots))
        NumberField(
            label = stringResource(R.string.worker_schedule_field_slot_minutes),
            value = form.slotMinutes,
            enabled = !busy,
            onValueChange = { onFormChanged(form.copy(slotMinutes = it)) },
        )
        FieldCaption(text = stringResource(R.string.worker_schedule_slot_minutes_note))
        NumberField(
            label = stringResource(R.string.worker_schedule_field_buffer_minutes),
            value = form.bufferMinutes,
            enabled = !busy,
            onValueChange = { onFormChanged(form.copy(bufferMinutes = it)) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = form.buffersCountTowardServiceDuration,
                onCheckedChange = { onFormChanged(form.copy(buffersCountTowardServiceDuration = it)) },
                enabled = !busy,
            )
            Text(
                text = stringResource(R.string.worker_schedule_field_buffers_count),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ArithmeticCaption(form = form)

        SectionLabel(text = stringResource(R.string.worker_schedule_field_horizon_label))
        NumberField(
            label = stringResource(R.string.worker_schedule_field_horizon),
            value = form.horizonDays,
            enabled = !busy,
            onValueChange = { onFormChanged(form.copy(horizonDays = it)) },
        )
        // No client-side maximum: `WORKER_SCHEDULE_MAX_HORIZON_DAYS` is caption-only, the cap itself is
        // enforced server-side (`docs/design/26-155-*.md`'s own accepted "console decision, kept").
        FieldCaption(text = stringResource(R.string.worker_schedule_horizon_cap_note, WORKER_SCHEDULE_MAX_HORIZON_DAYS))

        SingleDatePickerField(
            label = stringResource(R.string.worker_schedule_field_materialize_from),
            value = form.materializeFrom,
            enabled = !busy,
            onPicked = { onFormChanged(form.copy(materializeFrom = it)) },
        )
        // No client `min` either: the server's own 400 `detail` for a backward move is shown verbatim
        // through [ActionErrorBanner] - this caption only states the bound the console's own field
        // description already states, it does not enforce it.
        state.existing?.let { existing ->
            FieldCaption(
                text = stringResource(R.string.worker_schedule_materialize_from_cannot_move_note, existing.materializeFrom),
            )
        }

        if (state.existing != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.worker_schedule_recut_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onPreviewRecut, enabled = state.recut !is RecutHookUiState.Loading) {
                    Text(text = stringResource(R.string.worker_schedule_recut_button))
                }
            }
        }

        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(onClick = onSubmit, enabled = !busy) {
                Text(
                    text =
                        stringResource(
                            if (state.existing == null) R.string.worker_schedule_action_create else R.string.worker_schedule_action_save,
                        ),
                )
            }
        }
    }
}

/**
 * `26-155` Q2's minimal re-cut hook, surfaced — a read-only summary of [RecutHookUiState.Loaded], never
 * a decision or a confirm/execute control (the full three-step «Пересчёт» screen is a follow-up slice,
 * [WorkerScheduleUiState.Loaded.recut]'s own doc comment).
 */
@Composable
private fun RecutHookDialog(
    recut: RecutHookUiState,
    onDismiss: () -> Unit,
) {
    if (recut is RecutHookUiState.Idle) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.worker_schedule_recut_preview_title)) },
        text = {
            when (recut) {
                RecutHookUiState.Idle -> Unit
                RecutHookUiState.Loading -> CircularProgressIndicator()
                is RecutHookUiState.Loaded -> {
                    val decidableCount = recut.preview.days.sumOf { day -> day.bookings.count { it.canDecide } }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text =
                                stringResource(
                                    R.string.worker_schedule_recut_preview_summary,
                                    recut.preview.days.size,
                                    decidableCount,
                                ),
                        )
                        Text(
                            text = stringResource(R.string.worker_schedule_recut_preview_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is RecutHookUiState.Refused -> Text(text = recut.detail)
                // `failureMessage` (`BookingsScreen.kt`) is `private` to that file - this restates its
                // two-arm classification rather than widening that function's visibility for one more
                // caller, the identical choice every sibling adapter's own `classify()` doc comment makes
                // for not sharing a four-line function across a package boundary.
                is RecutHookUiState.Failed ->
                    Text(
                        text =
                            when (recut.reason) {
                                BookingsQueueFailure.Transport -> stringResource(R.string.bookings_load_failed_transport)
                                BookingsQueueFailure.Unexpected ->
                                    stringResource(R.string.worker_schedule_recut_load_failed_unexpected)
                            },
                    )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_dismiss)) }
        },
    )
}

/**
 * The item's own worked example - `ConsecutiveRunFinder.ComputeSlotsNeeded`'s exact rule, mirrored here
 * for *display only* (the server's own copy is the one that ever decides anything), restating the
 * console's own `WorkerScheduleSection.tsx` `arithmeticNote` verbatim so the two surfaces show the
 * identical worked numbers for the identical inputs. Draws nothing when either field does not yet parse
 * to a usable number - a half-typed field is not a caption's problem to guess at.
 */
@Composable
private fun ArithmeticCaption(form: WorkerScheduleForm) {
    val slotMinutes = form.slotMinutes.trim().toIntOrNull() ?: return
    val bufferMinutes = form.bufferMinutes.trim().toIntOrNull() ?: return
    if (slotMinutes <= 0 || bufferMinutes < 0) return

    val slotsNeeded =
        if (form.buffersCountTowardServiceDuration) {
            ceil((ARITHMETIC_EXAMPLE_MINUTES + bufferMinutes).toDouble() / (slotMinutes + bufferMinutes)).toInt()
        } else {
            ceil(ARITHMETIC_EXAMPLE_MINUTES.toDouble() / slotMinutes).toInt()
        }
    val spanMinutes = slotsNeeded * slotMinutes + (slotsNeeded - 1) * bufferMinutes
    val exampleStartMinutes = 12 * 60 // 12:00, the console's own illustrative anchor.

    val slotsPhrase =
        russianPluralStringResource(
            count = slotsNeeded.toLong(),
            one = R.string.worker_schedule_slots_one,
            few = R.string.worker_schedule_slots_few,
            many = R.string.worker_schedule_slots_many,
        )
    FieldCaption(
        text =
            stringResource(
                R.string.worker_schedule_arithmetic_caption,
                ARITHMETIC_EXAMPLE_MINUTES,
                slotsPhrase,
                clockOf(exampleStartMinutes),
                clockOf(exampleStartMinutes + spanMinutes),
            ),
    )
}

/** `20-18`'s own fixed illustrative service length - not any real service on this tenant's catalogue,
 * the identical constant the console's own `ARITHMETIC_EXAMPLE_MINUTES` is. */
private const val ARITHMETIC_EXAMPLE_MINUTES = 70

private fun clockOf(totalMinutesFromMidnight: Int): String {
    val hours = (totalMinutesFromMidnight / 60) % 24
    val minutes = totalMinutesFromMidnight % 60
    return "%02d:%02d".format(hours, minutes)
}

@Composable
private fun FieldCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** The typed `HH:mm` field (Q4) - unvalidated here, see this file's own top-of-file doc comment. */
@Composable
private fun TimeField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * A single ISO `yyyy-MM-dd` field, picked through [DatePickerDialog] - the identical UTC-midnight round
 * trip [ago.chat.android.analytics.AnalyticsDateRangeControl] already establishes, restated here rather
 * than shared: that composable is `internal` to the `analytics` package and stateful over a *pair* of
 * dates with its own `Apply` button, a different shape than this file's one-field-at-a-time need
 * (the identical "restating a four-line date/millis round trip costs less than the coupling" call
 * [AnalyticsDateRangeControl]'s own doc comment already makes for its sibling reports).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleDatePickerField(
    label: String,
    value: String,
    enabled: Boolean,
    onPicked: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { showPicker = true }, enabled = enabled) {
            Text(text = value)
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = epochMillisAtUtcMidnight(value))
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { onPicked(localDateAtUtcMidnight(it)) }
                        showPicker = false
                    },
                ) {
                    Text(text = stringResource(R.string.analytics_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(text = stringResource(R.string.analytics_dialog_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** [DatePicker] speaks in UTC-midnight epoch millis regardless of the device's own zone - its own
 * documented contract, the identical pair [ago.chat.android.analytics.AnalyticsDateRangeControl]'s own
 * private `epochMillisAtUtcMidnight`/`localDateAtUtcMidnight` already are (restated, not shared - see
 * [SingleDatePickerField]'s own doc comment). */
private fun epochMillisAtUtcMidnight(isoLocalDate: String): Long? =
    runCatching {
        LocalDate
            .parse(isoLocalDate)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

private fun localDateAtUtcMidnight(epochMillis: Long): String =
    Instant
        .ofEpochMilli(epochMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .toString()
