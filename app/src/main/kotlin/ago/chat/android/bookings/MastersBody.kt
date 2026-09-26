package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.ConfiguredService
import ago.chat.android.core.domain.workers.Worker
import ago.chat.android.core.domain.workers.WorkerCalendar
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * `26-140`: Записи's own «Мастера» segment — the worker dictionary, with the full create/edit/delete this
 * product had no screen for at all until this item. Built on the Услуги ([ServicesBody]) pattern verbatim:
 * the same four-arm `when`, the same form-over-list idiom, the same reused [LoadingBody]/[EmptyBody]/
 * [RefusalBody]/[ActionErrorBanner]. No new visual language.
 *
 * **The one thing this screen has that Услуги does not is a create path**, because a worker dictionary
 * starts empty and a service dictionary is seeded elsewhere. Add is disabled with a stated note exactly
 * when there is no calendar to put a worker on ([WorkerCalendar]'s own doc comment) — a picker with no
 * options is not an honest control.
 *
 * **An inactive worker stays on the list, marked** — the same reasoning [ServicesBody]'s own withdrawn
 * service records: `«Неактивен»` is a word beside the name, never a colour alone, so an operator who
 * cannot tell two greys apart still reads it.
 */
@Composable
internal fun MastersBody(
    state: MastersUiState,
    onRetry: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Worker) -> Unit,
    onToggleActive: (Worker) -> Unit,
    onDelete: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onFormChanged: (WorkerForm) -> Unit,
    onSubmit: (WorkerForm) -> Unit,
    // `26-170` (`26-155` part 2): the card's own «График» drill-down entry (Q1, console row-action
    // parity). A callback, not a `NavHost` route - [BookingsScreen]'s own `MastersDrillDown` state is
    // what this ends up flipping, one level up.
    onOpenSchedule: (Worker) -> Unit,
    // `26-171` (`26-155` part 3): the sibling «Слоты» entry - 26-170 shipped this button disabled
    // ([WorkerCard]'s own doc comment on why); this item is what wires it.
    onOpenSlots: (Worker) -> Unit,
) {
    // Which worker's delete is being confirmed, if any. Held here rather than in [MastersUiState] for the
    // reason every other transient dialog in this app is: it is a property of this composition, not a
    // fact the view model or a process-death restore has any business carrying (the identical shape
    // [ago.chat.android.schedule.WorkingHoursBody]'s own `confirmingDelete` records).
    var confirmingDelete by remember { mutableStateOf<Worker?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state is MastersUiState.Loaded) {
            state.actionError?.let { error -> ActionErrorBanner(error = error, modifier = Modifier.fillMaxWidth()) }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                MastersUiState.Loading -> LoadingBody()
                MastersUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
                is MastersUiState.Failed ->
                    RefusalBody(
                        reason = state.reason,
                        onRetry = onRetry,
                        unexpectedMessageRes = R.string.masters_load_failed_unexpected,
                    )

                is MastersUiState.Loaded -> {
                    val editing = state.editing
                    if (editing != null) {
                        WorkerEditForm(
                            form = editing,
                            calendars = state.calendars,
                            services = state.services,
                            busy = state.formBusy,
                            onFormChanged = onFormChanged,
                            onCancel = onCancelEdit,
                            onSubmit = { onSubmit(editing) },
                        )
                    } else {
                        MastersList(
                            workers = state.workers,
                            services = state.services,
                            canAdd = state.calendars.isNotEmpty(),
                            busyWorkerIds = state.busyWorkerIds,
                            onAdd = onAdd,
                            onEdit = onEdit,
                            onToggleActive = onToggleActive,
                            onDelete = { confirmingDelete = it },
                            onOpenSchedule = onOpenSchedule,
                            onOpenSlots = onOpenSlots,
                        )
                    }
                }
            }
        }
    }

    confirmingDelete?.let { worker ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text(text = stringResource(R.string.masters_delete_title)) },
            // States what a delete does and does not do *before* it happens — and that a booked worker is
            // deactivated, not deleted (the server refuses the delete for exactly that worker).
            text = { Text(text = stringResource(R.string.masters_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = null
                    onDelete(worker.workerId)
                }) {
                    Text(text = stringResource(R.string.masters_action_delete))
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

@Composable
private fun MastersList(
    workers: List<Worker>,
    services: List<ConfiguredService>,
    canAdd: Boolean,
    busyWorkerIds: Set<String>,
    onAdd: () -> Unit,
    onEdit: (Worker) -> Unit,
    onToggleActive: (Worker) -> Unit,
    onDelete: (Worker) -> Unit,
    onOpenSchedule: (Worker) -> Unit,
    onOpenSlots: (Worker) -> Unit,
) {
    val serviceNames = remember(services) { services.associate { it.serviceId to it.name } }
    Column(modifier = Modifier.fillMaxSize()) {
        // The create control lives above the list, always drawn: disabled with a note when there is no
        // calendar to put a worker on, so the reason it cannot be tapped is stated rather than left to
        // guess ([WorkerCalendar]'s own doc comment).
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(onClick = onAdd, enabled = canAdd) {
                Text(text = stringResource(R.string.masters_action_add))
            }
            if (!canAdd) {
                Text(
                    text = stringResource(R.string.masters_add_disabled_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        HorizontalDivider()
        if (workers.isEmpty()) {
            // "Empty is a state, not a blank area" — the identical rule every sibling segment applies.
            EmptyBody(stringResource(R.string.masters_empty))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(workers, key = { it.workerId }) { worker ->
                    WorkerCard(
                        worker = worker,
                        serviceNames = serviceNames,
                        busy = worker.workerId in busyWorkerIds,
                        onEdit = { onEdit(worker) },
                        onToggleActive = { onToggleActive(worker) },
                        onDelete = { onDelete(worker) },
                        onOpenSchedule = { onOpenSchedule(worker) },
                        onOpenSlots = { onOpenSlots(worker) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * One worker: the display name in bold, a subdued line of the services he performs (resolved from ids to
 * names), and an `error`-worded `«Неактивен»` when he is out of rotation. [busy] disables all three
 * actions for *this* card only, never the whole list — the identical per-row rule [ServiceCard]'s own
 * `busy` parameter follows.
 *
 * **`«Удалить»` is a real delete here, unlike Услуги's `«Снять с продажи»`** — a worker who was never
 * booked can be removed outright, and the confirmation dialog plus the server's own refusal for a booked
 * worker are what make that safe ([MastersViewModel.delete]'s own doc comment).
 *
 * **`26-170`/`26-171`: «График»/«Слоты» are a second row, below the roster actions above.** Two card
 * buttons (Q1, console row-action parity), not a third `⋮` menu entry — the identical reasoning
 * [ago.chat.android.bookings.MastersViewModel]'s own sibling `WorkersApi` gate needs no restating, since
 * both open a drill-down over this same screen rather than a different permission surface. **«Слоты»
 * shipped disabled in `26-170`** — its own screen was `26-155`'s follow-up ticket
 * (`docs/design/26-155-*.md`'s ticket split) — and `26-171` is that follow-up landing, wiring the button
 * to the sibling drill-down [WorkerScheduleScreen.kt][WorkerScheduleDrillDownPage] already established
 * the nav mechanism for.
 */
@Composable
private fun WorkerCard(
    worker: Worker,
    serviceNames: Map<String, String>,
    busy: Boolean,
    onEdit: () -> Unit,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenSlots: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = worker.displayName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            if (!worker.isActive) {
                Text(
                    text = stringResource(R.string.masters_status_inactive),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        val resolved = worker.serviceIds.mapNotNull { serviceNames[it] }
        Text(
            text =
                if (resolved.isEmpty()) {
                    stringResource(R.string.masters_no_services)
                } else {
                    resolved.joinToString(", ")
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onEdit, enabled = !busy) {
                Text(text = stringResource(R.string.masters_action_edit))
            }
            TextButton(onClick = onToggleActive, enabled = !busy) {
                Text(
                    text =
                        stringResource(
                            if (worker.isActive) R.string.masters_action_deactivate else R.string.masters_action_reactivate,
                        ),
                )
            }
            TextButton(onClick = onDelete, enabled = !busy) {
                Text(
                    text = stringResource(R.string.masters_action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        // `26-170`/`26-171`: the drill-down entry row - a second row rather than crowding the roster
        // actions above, since these two open a whole different screen rather than acting on this row in
        // place.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onOpenSchedule, enabled = !busy) {
                Text(text = stringResource(R.string.masters_action_open_schedule))
            }
            TextButton(onClick = onOpenSlots, enabled = !busy) {
                Text(text = stringResource(R.string.masters_action_open_slots))
            }
        }
    }
}

/**
 * The add/edit form — the four name fields, the calendar picker (create only), the service checkboxes and,
 * on an edit, the Активен toggle. Over the list rather than beside it, so there is never a second form on
 * screen an operator could submit by mistake (the same one-card-two-modes rule [ServiceEditForm] follows).
 *
 * **The calendar picker is shown only while creating.** A worker's calendar is fixed at creation and the
 * server refuses to move one ([ago.chat.android.core.domain.workers.WorkersApi.updateWorker]'s own doc
 * comment), so on an edit there is no choice to offer — offering one would promise a move that cannot
 * happen. The Активен toggle is the mirror image: shown only on an edit, because a new worker is always
 * active and the create request has no active field.
 */
@Composable
private fun WorkerEditForm(
    form: WorkerForm,
    calendars: List<WorkerCalendar>,
    services: List<ConfiguredService>,
    busy: Boolean,
    onFormChanged: (WorkerForm) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = form.lastName,
            onValueChange = { onFormChanged(form.copy(lastName = it)) },
            label = { Text(text = stringResource(R.string.masters_field_last_name)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.firstName,
            onValueChange = { onFormChanged(form.copy(firstName = it)) },
            label = { Text(text = stringResource(R.string.masters_field_first_name)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.middleName,
            onValueChange = { onFormChanged(form.copy(middleName = it)) },
            label = { Text(text = stringResource(R.string.masters_field_middle_name)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.displayName,
            onValueChange = { onFormChanged(form.copy(displayName = it)) },
            label = { Text(text = stringResource(R.string.masters_field_display_name)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        if (form.isCreating) {
            Text(text = stringResource(R.string.masters_field_calendar), style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                calendars.forEach { calendar ->
                    FilterChip(
                        selected = form.calendarId == calendar.calendarId,
                        onClick = { onFormChanged(form.copy(calendarId = calendar.calendarId)) },
                        label = { Text(text = calendar.name) },
                        enabled = !busy,
                    )
                }
            }
        }

        if (services.isNotEmpty()) {
            Text(text = stringResource(R.string.masters_field_services), style = MaterialTheme.typography.labelMedium)
            services.forEach { service ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = service.serviceId in form.serviceIds,
                        onCheckedChange = { checked ->
                            val next =
                                if (checked) form.serviceIds + service.serviceId else form.serviceIds - service.serviceId
                            onFormChanged(form.copy(serviceIds = next))
                        },
                        enabled = !busy,
                    )
                    Text(text = service.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (!form.isCreating) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.isActive,
                    onCheckedChange = { onFormChanged(form.copy(isActive = it)) },
                    enabled = !busy,
                )
                Text(text = stringResource(R.string.masters_field_active), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSubmit, enabled = !busy) {
                Text(text = stringResource(R.string.masters_action_save))
            }
            TextButton(onClick = onCancel, enabled = !busy) {
                Text(text = stringResource(R.string.action_cancel))
            }
        }
    }
}
