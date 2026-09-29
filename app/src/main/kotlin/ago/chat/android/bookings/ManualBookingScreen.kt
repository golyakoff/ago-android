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
import ago.chat.android.ui.components.RuPhoneField
import ago.chat.android.ui.components.formatRuPhoneForDisplay
import ago.chat.android.ui.components.isRuPhoneComplete
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

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
 *
 * `26-268` follow-up (author bug report 2026-09-29): the Date/Time steps are each a scrollable
 * [LazyColumn], and a bare [ModalBottomSheet] treats any downward drag — including one that starts on that
 * inner list, since Compose's nested-scroll connection hands the unconsumed part of every scroll gesture up
 * to the sheet — as a swipe-to-dismiss. That closed the whole wizard on the very gesture a step's own list
 * needs, forcing a reopen from scratch.
 *
 * `26-268` second follow-up (author regression report 2026-09-29): the first fix for that —
 * `confirmValueChange = { it != SheetValue.Hidden }` on the [androidx.compose.material3.SheetState] —
 * turned out to be broken two ways. Rejecting the `Hidden` settle target mid-drag fights the anchored-drag
 * settling the drag gesture itself drives, so a swipe *up* would oscillate and stick instead of settling
 * cleanly. And [androidx.compose.material3.SheetState.hide] cannot animate to `Hidden` while
 * `confirmValueChange` rejects that exact value either, so [closeSheet] below never completed and the X
 * button stopped closing the sheet at all. The fix here disables the sheet's drag gestures outright instead
 * of fighting them — `sheetGesturesEnabled = false` on [ModalBottomSheet] — so neither swipe direction moves
 * the sheet (no jitter) and the inner [LazyColumn]s scroll freely with nothing left to contest their scroll
 * gestures for. Every dismissal now goes through [androidx.compose.material3.SheetState.hide] unopposed:
 * [closeSheet] is wired to [onDismissRequest] too, so the system back button and a scrim tap — the two
 * dismissal paths a `ModalBottomSheet` still drives on its own even with dragging disabled — animate the
 * sheet away first instead of yanking it out of composition mid-frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualBookingSheet(
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
    // `26-283`: non-`null` when this sheet was opened from a client's own detail hub «+ Записать» rather
    // than the plain header entry point — see [ManualBookingViewModel.open]'s own doc comment. Defaulted
    // to `null` so the header entry point's own call site keeps compiling unchanged.
    prefillClient: PhoneCandidate? = null,
) {
    val viewModel: ManualBookingViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.open(prefillClient) }
    LaunchedEffect(state) {
        if (state is ManualBookingUiState.Created) onCreated()
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // `26-268` follow-up doc comment above: the one path every dismissal - the X button, back, the scrim -
    // now shares, so the sheet always animates to `Hidden` before `onDismiss` tears down the view model.
    val closeSheet: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = closeSheet,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
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
            onSelectDate = viewModel::selectDate,
            onSelectSlot = viewModel::selectSlot,
            onSubmit = viewModel::submit,
            onBack = viewModel::back,
            onClose = closeSheet,
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
    onSelectDate: (String) -> Unit,
    onSelectSlot: (WorkerSlot) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        // `26-268` follow-up: the title shares its row with the sheet's own explicit close control now
        // that a downward drag on the Date/Time steps' own list no longer dismisses it (`ManualBookingSheet`'s
        // own doc comment) - `weight(1f)` on the title keeps the [IconButton] pinned to the trailing edge
        // regardless of how long a given title runs.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.bookings_manual_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            IconButton(onClick = onClose) {
                Icon(imageVector = AgoIcons.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
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
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            )
            WizardStepper(
                currentStep = state.step,
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
                                onBack = onBack,
                            )

                        ManualBookingStep.Service -> ServiceStepBody(services = state.services, onSelect = onSelectService)

                        ManualBookingStep.Worker -> WorkerStepBody(workers = workersOffering(state), onSelect = onSelectWorker)

                        ManualBookingStep.Date -> DateStepBody(wizard = state, onSelect = onSelectDate)

                        ManualBookingStep.Slot -> SlotStepBody(wizard = state, onSelect = onSelectSlot)

                        ManualBookingStep.Review -> ReviewStepBody(wizard = state, onSubmit = onSubmit, onBack = onBack)
                    }
            }
        }
        // `Client`/`Review` embed «Назад» next to their own «Далее»/«Создать запись» button
        // ([ClientStepBody]/[ReviewStepBody]'s own Row) - every other non-[ManualBookingStep.Phone] step
        // advances by tapping a row (one-motion select-and-advance, [ServiceStepBody]'s own doc comment),
        // so it has no forward button of its own for «Назад» to sit beside; this bare row is where it lives
        // instead (author feedback 2026-09-29: the wizard had no way back at all before this).
        if (state is ManualBookingUiState.Wizard &&
            state.step !in setOf(ManualBookingStep.Phone, ManualBookingStep.Client, ManualBookingStep.Review)
        ) {
            BackOnlyRow(onBack = onBack, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * `26-268` follow-up (author feedback 2026-09-29): the mockup's own `.steps` bar — a segmented progress
 * track, one segment per [ManualBookingStep], filled from [ManualBookingStep.Phone] up to and including
 * [currentStep] (`android-design.agochat.ru/manual-booking.html`'s own `.steps div.on { background:
 * var(--brand) }`, confirmed live: every earlier screen's own `.steps` carries one more filled segment than
 * the last). `MaterialTheme.colorScheme.primary`/`.outlineVariant` stand in for `--brand`/`--line` — the
 * identical token mapping `Theme.kt`'s own header states (`primary` *is* `AgoBrandLight`/`AgoBrandDark`),
 * so this reads the app's existing brand colour rather than a literal hex invented for this one call site.
 */
@Composable
private fun WizardStepper(
    currentStep: ManualBookingStep,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ManualBookingStep.entries.forEach { step ->
            val filled = step.ordinal <= currentStep.ordinal
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(2.dp),
                        ),
            )
        }
    }
}

/** «Назад» alone, no neighbouring «Далее» — the step bodies whose own row-tap already advances the wizard
 * ([ServiceStepBody]/[WorkerStepBody]/[DateStepBody]/[SlotStepBody]) render this below their own content
 * rather than a [Row] pairing it with a forward button, since none of the four has one of its own. */
@Composable
private fun BackOnlyRow(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        OutlinedButton(onClick = onBack) {
            Text(text = stringResource(R.string.bookings_manual_back_action))
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
        ManualBookingStep.Date -> 5
        ManualBookingStep.Slot -> 6
        ManualBookingStep.Review -> 7
    }

private fun stepNameRes(step: ManualBookingStep): Int =
    when (step) {
        ManualBookingStep.Phone -> R.string.bookings_manual_step_phone
        ManualBookingStep.Client -> R.string.bookings_manual_step_client
        ManualBookingStep.Service -> R.string.bookings_manual_step_service
        ManualBookingStep.Worker -> R.string.bookings_manual_step_worker
        ManualBookingStep.Date -> R.string.bookings_manual_step_date
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
            // `26-305`: the shared masked `+7 (XXX) XXX-XX-XX` control replaces the plain, unmasked field
            // this used to be — see `RuPhoneField`'s own doc comment for why `wizard.phone`/`onPhoneChanged`
            // need no change at all to start carrying/sending the canonical value. `autoFocus` is new too
            // (`docs/backlog/26-303-phone-input-research.md` §1/§7: the reference control is focused, numeric
            // keyboard up, the moment this step is reached).
            RuPhoneField(
                value = wizard.phone,
                onValueChange = onPhoneChanged,
                label = stringResource(R.string.bookings_manual_phone_label),
                enabled = lookup !is PhoneLookupState.Searching,
                autoFocus = true,
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
                // `26-305`: gated on completeness (all 10 national digits), not `isNotBlank()` — a fixed
                // `+7` with one digit typed used to already read as "non-blank" and enable this button.
                Button(onClick = onSearchPhone, enabled = isRuPhoneComplete(wizard.phone), modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.bookings_manual_search_action))
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    // `26-307`: the number was just entered through `RuPhoneField` (always a complete,
                    // canonical `+7…` value once this step is past the search button) - display it grouped.
                    text = formatRuPhoneForDisplay(wizard.phone),
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
            text = candidate.displayName ?: formatRuPhoneForDisplay(candidate.phone),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = formatRuPhoneForDisplay(candidate.phone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                        text = candidate.displayName ?: formatRuPhoneForDisplay(candidate.phone),
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
    onBack: () -> Unit,
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
                    text = client.candidate.displayName ?: formatRuPhoneForDisplay(client.candidate.phone),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = formatRuPhoneForDisplay(client.candidate.phone),
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.bookings_manual_back_action))
            }
            Button(
                onClick = onConfirm,
                enabled = client != null && !(client is ManualBookingClient.New && client.name.isBlank()),
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.bookings_manual_next_action))
            }
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

/**
 * `26-268` follow-up (author feedback 2026-09-29): [ManualBookingStep.Date]'s own screen — one row per
 * business-local day [groupSlotsByDay] finds in [ManualBookingUiState.Wizard.slots] (the whole default
 * range [ManualBookingViewModel.selectWorker] already fetched, per [ManualBookingStep]'s own doc comment),
 * tapping a day both selects it and advances straight to [ManualBookingStep.Slot] — the identical
 * one-motion shape [ServiceStepBody]/[WorkerStepBody] above already use. The `${localDate} · ${weekday}`
 * label and [R.array.bookings_weekday_full] lookup are the identical pair [WorkerSlotsScreen.kt]'s own day
 * header already renders, restated here as a tappable row rather than a plain section heading.
 */
@Composable
private fun DateStepBody(
    wizard: ManualBookingUiState.Wizard,
    onSelect: (String) -> Unit,
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
    val weekdayLabels = stringArrayResource(R.array.bookings_weekday_full)
    val days = groupSlotsByDay(wizard.slots)
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = StepListHeight), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(days, key = { it.localDate }) { day ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(day.localDate) }.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${day.localDate} · ${weekdayLabels.getOrElse(day.weekday) { "" }}",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.bookings_manual_date_slot_count_format, day.slots.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

/** [ManualBookingStep.Slot]'s own screen — [ManualBookingUiState.Wizard.slots] filtered down to
 * [ManualBookingUiState.Wizard.selectedDate] alone, no grouping and no day header needed since
 * [ManualBookingStep.Date] already settled which day this is (`ManualBookingStep`'s own doc comment: one
 * read, split into two screens, not two reads). Tapping a slot both selects it and advances straight to
 * Проверьте, the identical one-motion shape [DateStepBody] above already uses for the day it reads. */
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
    val daySlots = wizard.slots.filter { it.localDate == wizard.selectedDate }
    if (daySlots.isEmpty()) {
        EmptyBody(stringResource(R.string.bookings_manual_no_slots))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = StepListHeight), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(daySlots, key = { it.eventId }) { slot ->
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

@Composable
private fun ReviewStepBody(
    wizard: ManualBookingUiState.Wizard,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
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
        ReviewRow(label = stringResource(R.string.bookings_confirmed_detail_phone_label), value = formatRuPhoneForDisplay(wizard.phone))
        ReviewRow(label = stringResource(R.string.bookings_manual_email_label), value = emailValue(wizard.client))
        Text(
            text = stringResource(R.string.bookings_manual_no_conversation_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, enabled = !wizard.submitting, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.bookings_manual_back_action))
            }
            Button(onClick = onSubmit, enabled = !wizard.submitting, modifier = Modifier.weight(1f)) {
                if (wizard.submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text(text = stringResource(R.string.bookings_manual_submit_action))
                }
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
