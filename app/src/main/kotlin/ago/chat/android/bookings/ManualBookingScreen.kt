package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfiguredService
import ago.chat.android.core.domain.bookings.PhoneCandidate
import ago.chat.android.core.domain.bookings.businessLocalTimeOrNull
import ago.chat.android.core.domain.bookings.confirmedBookingsCountLabel
import ago.chat.android.core.domain.workers.Worker
import ago.chat.android.core.domain.workerslots.WorkerSlot
import ago.chat.android.core.domain.workerslots.groupSlotsByDay
import ago.chat.android.ui.components.SectionLabel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * `26-268`/`adr/0188`: «Добавить вручную»'s own sheet — obtains [ManualBookingViewModel] here, inside this
 * composable, the identical `hiltViewModel()`-only-while-open discipline
 * [RescheduleBookingSheet]'s own doc comment states, and for the identical reason: [BookingsScreen]'s own
 * bare-`ComponentActivity` androidTest (`BookingsConfigMenuTest`) never opens this sheet, so it never
 * triggers this lookup.
 *
 * [onCreated] fires exactly once, the moment [ManualBookingUiState.Created] is reached — the caller's job,
 * per [BookingsScreen]'s own wiring, is to close this sheet and switch to Утверждены so the new booking is
 * what renders next (`docs/backlog/26-268-*.md` §5.2's own last frame). This composable never touches
 * [ConfirmedBookingsViewModel] itself, the identical separation [RescheduleBookingSheet] already draws.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualBookingSheet(
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val viewModel: ManualBookingViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.open() }
    LaunchedEffect(state) {
        if (state is ManualBookingUiState.Created) onCreated()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ManualBookingBody(
            state = state,
            onRetry = viewModel::refresh,
            onPhoneChanged = viewModel::onPhoneChanged,
            onSearchPhone = viewModel::searchPhone,
            onChooseCandidate = viewModel::chooseCandidate,
            onChooseNewClient = viewModel::chooseNewClient,
            onEditPhone = viewModel::editPhone,
            onNewClientNameChanged = viewModel::onNewClientNameChanged,
            onNewClientEmailChanged = viewModel::onNewClientEmailChanged,
            onConfirmClientStep = viewModel::confirmClientStep,
            onSelectService = viewModel::selectService,
            onSelectWorker = viewModel::selectWorker,
            onSelectSlot = viewModel::selectSlot,
            onSubmit = viewModel::submit,
        )
    }
}

@Composable
private fun ManualBookingBody(
    state: ManualBookingUiState,
    onRetry: () -> Unit,
    onPhoneChanged: (String) -> Unit,
    onSearchPhone: () -> Unit,
    onChooseCandidate: (PhoneCandidate) -> Unit,
    onChooseNewClient: () -> Unit,
    onEditPhone: () -> Unit,
    onNewClientNameChanged: (String) -> Unit,
    onNewClientEmailChanged: (String) -> Unit,
    onConfirmClientStep: () -> Unit,
    onSelectService: (ConfiguredService) -> Unit,
    onSelectWorker: (Worker) -> Unit,
    onSelectSlot: (WorkerSlot) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = stringResource(R.string.bookings_manual_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (state is ManualBookingUiState.Wizard) {
            Text(
                text =
                    stringResource(
                        R.string.bookings_manual_step_format,
                        stepNumber(state.step),
                        stringResource(stepNameRes(state.step)),
                    ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = StepMinHeight)) {
            when (state) {
                ManualBookingUiState.Loading, is ManualBookingUiState.Created -> StepLoadingBody()
                ManualBookingUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
                is ManualBookingUiState.Failed ->
                    RefusalBody(
                        reason = state.reason,
                        onRetry = onRetry,
                        unexpectedMessageRes = R.string.bookings_manual_load_failed_unexpected,
                    )

                is ManualBookingUiState.Wizard ->
                    when (state.step) {
                        ManualBookingStep.Phone ->
                            PhoneStepBody(
                                wizard = state,
                                onPhoneChanged = onPhoneChanged,
                                onSearchPhone = onSearchPhone,
                                onChooseCandidate = onChooseCandidate,
                                onChooseNewClient = onChooseNewClient,
                                onEditPhone = onEditPhone,
                            )

                        ManualBookingStep.Client ->
                            ClientStepBody(
                                wizard = state,
                                onNameChanged = onNewClientNameChanged,
                                onEmailChanged = onNewClientEmailChanged,
                                onConfirm = onConfirmClientStep,
                            )

                        ManualBookingStep.Service -> ServiceStepBody(services = state.services, onSelect = onSelectService)

                        ManualBookingStep.Worker -> WorkerStepBody(workers = workersOffering(state), onSelect = onSelectWorker)

                        ManualBookingStep.Slot -> SlotStepBody(wizard = state, onSelect = onSelectSlot)

                        ManualBookingStep.Review -> ReviewStepBody(wizard = state, onSubmit = onSubmit)
                    }
            }
        }
    }
}

@Composable
private fun StepLoadingBody() {
    Box(modifier = Modifier.fillMaxWidth().height(StepMinHeight), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

private fun stepNumber(step: ManualBookingStep): Int =
    when (step) {
        ManualBookingStep.Phone -> 1
        ManualBookingStep.Client -> 2
        ManualBookingStep.Service -> 3
        ManualBookingStep.Worker -> 4
        ManualBookingStep.Slot -> 5
        ManualBookingStep.Review -> 6
    }

private fun stepNameRes(step: ManualBookingStep): Int =
    when (step) {
        ManualBookingStep.Phone -> R.string.bookings_manual_step_phone
        ManualBookingStep.Client -> R.string.bookings_manual_step_client
        ManualBookingStep.Service -> R.string.bookings_manual_step_service
        ManualBookingStep.Worker -> R.string.bookings_manual_step_worker
        ManualBookingStep.Slot -> R.string.bookings_manual_step_slot
        ManualBookingStep.Review -> R.string.bookings_manual_step_review
    }

/** [ManualBookingStep.Worker]'s own list — the workers who both offer [ManualBookingUiState.Wizard.selectedService]
 * and are still active, filtered client-side from the roster [ManualBookingViewModel.open] already fetched
 * once (`docs/backlog/26-268-*.md` §3.6: no second read per step). */
private fun workersOffering(wizard: ManualBookingUiState.Wizard): List<Worker> {
    val serviceId = wizard.selectedService?.serviceId ?: return emptyList()
    return wizard.workers.filter { it.isActive && serviceId in it.serviceIds }
}

@Composable
private fun PhoneStepBody(
    wizard: ManualBookingUiState.Wizard,
    onPhoneChanged: (String) -> Unit,
    onSearchPhone: () -> Unit,
    onChooseCandidate: (PhoneCandidate) -> Unit,
    onChooseNewClient: () -> Unit,
    onEditPhone: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        val lookup = wizard.phoneLookup
        if (lookup is PhoneLookupState.Idle || lookup is PhoneLookupState.Searching) {
            OutlinedTextField(
                value = wizard.phone,
                onValueChange = onPhoneChanged,
                label = { Text(text = stringResource(R.string.bookings_manual_phone_label)) },
                placeholder = { Text(text = stringResource(R.string.bookings_manual_phone_placeholder)) },
                singleLine = true,
                enabled = lookup !is PhoneLookupState.Searching,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.bookings_manual_phone_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.bookings_manual_phone_required_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            if (lookup is PhoneLookupState.Searching) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else {
                Button(onClick = onSearchPhone, enabled = wizard.phone.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.bookings_manual_search_action))
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = wizard.phone,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEditPhone) { Text(text = stringResource(R.string.bookings_manual_edit_phone_action)) }
            }
            Spacer(modifier = Modifier.height(12.dp))
            when (lookup) {
                is PhoneLookupState.One ->
                    PhoneCandidateFoundCard(
                        candidate = lookup.candidate,
                        onChoose = { onChooseCandidate(lookup.candidate) },
                        onChooseNew = onChooseNewClient,
                    )

                is PhoneLookupState.Many ->
                    PhoneCandidateManyList(
                        candidates = lookup.candidates,
                        onChoose = onChooseCandidate,
                        onChooseNew = onChooseNewClient,
                    )

                PhoneLookupState.None -> PhoneCandidateNoneBody(onContinueAsNew = onChooseNewClient)

                is PhoneLookupState.Failed -> InlineFailureRetry(reason = lookup.reason, onRetry = onSearchPhone)

                PhoneLookupState.Idle, PhoneLookupState.Searching -> Unit
            }
        }
    }
}

@Composable
private fun InlineFailureRetry(
    reason: BookingsQueueFailure,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(
            text =
                when (reason) {
                    BookingsQueueFailure.Transport -> stringResource(R.string.bookings_load_failed_transport)
                    BookingsQueueFailure.Unexpected -> stringResource(R.string.bookings_manual_load_failed_unexpected)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun PhoneCandidateFoundCard(
    candidate: PhoneCandidate,
    onChoose: () -> Unit,
    onChooseNew: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.bookings_manual_found_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = candidate.displayName ?: candidate.phone,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(text = candidate.phone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = phoneCandidateHistoryLabel(candidate),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = stringResource(R.string.bookings_manual_phone_hint_not_proof),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
        )
        Button(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.bookings_manual_choose_this_client_action))
        }
        OutlinedButton(onClick = onChooseNew, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(text = stringResource(R.string.bookings_manual_new_client_action))
        }
    }
}

@Composable
private fun PhoneCandidateManyList(
    candidates: List<PhoneCandidate>,
    onChoose: (PhoneCandidate) -> Unit,
    onChooseNew: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.bookings_manual_many_found_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        candidates.forEach { candidate ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onChoose(candidate) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.displayName ?: candidate.phone,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = phoneCandidateHistoryLabel(candidate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onChooseNew).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.bookings_manual_new_client_action),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
        Text(
            text = stringResource(R.string.bookings_manual_phone_hint_not_proof),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** «Постоянный · N записи» once this person has more than one booking with this tenant, a bare count
 * otherwise (`docs/backlog/26-268-*.md` §3.4's own mockup: "1 запись" alone vs. "Постоянный · 3 записи"). */
@Composable
private fun phoneCandidateHistoryLabel(candidate: PhoneCandidate): String {
    val countLabel = confirmedBookingsCountLabel(candidate.bookingCount)
    return if (candidate.bookingCount > 1) {
        stringResource(R.string.bookings_manual_returning_client_prefix_format, countLabel)
    } else {
        countLabel
    }
}

@Composable
private fun PhoneCandidateNoneBody(onContinueAsNew: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.bookings_manual_none_found_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Text(
            text = stringResource(R.string.bookings_manual_none_found_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        Button(onClick = onContinueAsNew, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.bookings_manual_continue_as_new_action))
        }
    }
}

@Composable
private fun ClientStepBody(
    wizard: ManualBookingUiState.Wizard,
    onNameChanged: (String) -> Unit,
    onEmailChanged: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val client = wizard.client
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        when (client) {
            is ManualBookingClient.Existing -> {
                Text(
                    text = stringResource(R.string.bookings_manual_client_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = client.candidate.displayName ?: client.candidate.phone,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = client.candidate.phone,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = phoneCandidateHistoryLabel(client.candidate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = stringResource(R.string.bookings_manual_existing_client_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                )
            }

            else -> {
                val name = (client as? ManualBookingClient.New)?.name ?: wizard.newClientName
                val email = (client as? ManualBookingClient.New)?.email ?: wizard.newClientEmail
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChanged,
                    label = { Text(text = stringResource(R.string.bookings_manual_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = onEmailChanged,
                    label = { Text(text = stringResource(R.string.bookings_manual_email_label)) },
                    placeholder = { Text(text = stringResource(R.string.bookings_manual_email_optional_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.bookings_manual_email_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
            }
        }
        Button(
            onClick = onConfirm,
            enabled = client != null && !(client is ManualBookingClient.New && client.name.isBlank()),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.bookings_manual_next_action))
        }
    }
}

@Composable
private fun ServiceStepBody(
    services: List<ConfiguredService>,
    onSelect: (ConfiguredService) -> Unit,
) {
    if (services.isEmpty()) {
        EmptyBody(stringResource(R.string.services_empty))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = StepListHeight), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(services, key = { it.serviceId }) { service ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(service) }.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = service.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.bookings_duration_minutes, service.durationMinutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun WorkerStepBody(
    workers: List<Worker>,
    onSelect: (Worker) -> Unit,
) {
    if (workers.isEmpty()) {
        EmptyBody(stringResource(R.string.bookings_manual_no_workers))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = StepListHeight), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(workers, key = { it.workerId }) { worker ->
            Row(modifier = Modifier.fillMaxWidth().clickable { onSelect(worker) }.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text(text = worker.displayName, style = MaterialTheme.typography.bodyLarge)
            }
            HorizontalDivider()
        }
    }
}

/** The identical [groupSlotsByDay] grouping and one-tap-per-slot shape [RescheduleBookingScreen.kt]'s own
 * `RescheduleSlotList` already uses (`docs/backlog/26-268-*.md` §3.6: "reuse the slot-picker the reschedule
 * flow uses") — tapping a slot both selects it and advances straight to Проверьте, the identical
 * one-motion shape [ServiceStepBody]/[WorkerStepBody] above already use. */
@Composable
private fun SlotStepBody(
    wizard: ManualBookingUiState.Wizard,
    onSelect: (WorkerSlot) -> Unit,
) {
    if (wizard.loadingSlots) {
        StepLoadingBody()
        return
    }
    wizard.actionError?.let {
        ActionErrorBanner(
            error = it,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
    if (wizard.slots.isEmpty()) {
        EmptyBody(stringResource(R.string.bookings_manual_no_slots))
        return
    }
    val days = groupSlotsByDay(wizard.slots)
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = StepListHeight), contentPadding = PaddingValues(vertical = 8.dp)) {
        days.forEach { day ->
            item(key = "header-${day.localDate}") { SectionLabel(text = day.localDate) }
            items(day.slots, key = { it.eventId }) { slot ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(slot) }.padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "${businessLocalTimeOrNull(slot.startsAt) ?: "—"}–${businessLocalTimeOrNull(slot.endsAt) ?: "—"}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ReviewStepBody(
    wizard: ManualBookingUiState.Wizard,
    onSubmit: () -> Unit,
) {
    val service = wizard.selectedService
    val worker = wizard.selectedWorker
    val slot = wizard.selectedSlot

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        wizard.actionError?.let { ActionErrorBanner(error = it, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) }
        if (slot != null) {
            Text(
                text = "${businessLocalTimeOrNull(
                    slot.startsAt,
                ) ?: "—"}–${businessLocalTimeOrNull(slot.endsAt) ?: "—"} · ${slot.localDate}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        ReviewRow(label = stringResource(R.string.bookings_manual_client_label), value = clientTypeLabel(wizard.client))
        ReviewRow(label = stringResource(R.string.bookings_card_service_label), value = service?.name ?: "—")
        ReviewRow(label = stringResource(R.string.bookings_card_worker_label), value = worker?.displayName ?: "—")
        ReviewRow(label = stringResource(R.string.bookings_confirmed_detail_phone_label), value = wizard.phone)
        ReviewRow(label = stringResource(R.string.bookings_manual_email_label), value = emailValue(wizard.client))
        Text(
            text = stringResource(R.string.bookings_manual_no_conversation_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(onClick = onSubmit, enabled = !wizard.submitting, modifier = Modifier.fillMaxWidth()) {
            if (wizard.submitting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text(text = stringResource(R.string.bookings_manual_submit_action))
            }
        }
    }
}

@Composable
private fun ReviewRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
    }
    HorizontalDivider()
}

@Composable
private fun clientTypeLabel(client: ManualBookingClient?): String =
    when (client) {
        is ManualBookingClient.New -> stringResource(R.string.bookings_manual_new_client_value)
        is ManualBookingClient.Existing -> stringResource(R.string.bookings_manual_returning_client_value)
        null -> "—"
    }

@Composable
private fun emailValue(client: ManualBookingClient?): String =
    when (client) {
        is ManualBookingClient.New -> client.email.trim().ifBlank { stringResource(R.string.bookings_manual_email_not_provided) }
        is ManualBookingClient.Existing, null -> stringResource(R.string.bookings_manual_email_not_provided)
    }

// Every step body sits in a fixed-height viewport for the same reason [RescheduleBookingScreen.kt]'s own
// `RescheduleListHeight` does — a `ModalBottomSheet` content column has no `Scaffold` of its own to hand a
// `LazyColumn` a bounded parent.
private val StepMinHeight = 280.dp
private val StepListHeight = 360.dp
