package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.businessLocalTimeOrNull
import ago.chat.android.core.domain.workerslots.WorkerSlot
import ago.chat.android.core.domain.workerslots.groupSlotsByDay
import ago.chat.android.ui.components.SectionLabel
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * `26-209`/`adr/0187`: «Перенести оператором»'s own sheet — opened *over* the confirmed booking's own
 * detail sheet ([ConfirmedBookingDetailBody]), never replacing it, the identical
 * [androidx.compose.material3.ModalBottomSheet]-stacking shape this app already allows (Compose renders
 * each `ModalBottomSheet` in its own `Dialog` window, so a dismiss here — a plain "changed my mind", not a
 * write — leaves the operator right back on the booking they started from, nothing re-fetched).
 * [RescheduleBookingViewModel] is obtained here, inside this composable, the identical
 * `hiltViewModel()`-only-while-open discipline that class's own doc comment states.
 *
 * [onRescheduled] is called exactly once, the moment [RescheduleBookingUiState.Saved] is reached — it is
 * the caller's job to both close this sheet (and, per this item's own scope, the booking's own detail
 * sheet under it) and to refresh the confirmed range so the moved booking's new time is what renders next.
 * This composable never calls [ConfirmedBookingsViewModel.refresh] itself — it has no reference to that
 * class, the identical separation [RescheduleBookingViewModel]'s own doc comment states for the view model
 * layer.
 *
 * `26-268` follow-up (author bug report 2026-09-29): [RescheduleSlotList] is a scrollable [LazyColumn], so
 * this sheet carries the identical gestures-disabled fix [ManualBookingSheet]'s own doc comment states in
 * full — `sheetGesturesEnabled = false` (replacing an earlier, broken `confirmValueChange` attempt) means
 * no drag on the slot list moves the sheet at all, and [closeSheet] (the X button, back, a scrim tap) is
 * what still closes the sheet, via [androidx.compose.material3.SheetState.hide] rather than a raw
 * [onDismiss] call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RescheduleBookingSheet(
    bookingId: String,
    workerId: String,
    onDismiss: () -> Unit,
    onRescheduled: () -> Unit,
) {
    val viewModel: RescheduleBookingViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(bookingId, workerId) { viewModel.open(bookingId, workerId) }
    // `RescheduleBookingUiState.Saved`'s own doc comment: this composable's whole job at that point is to
    // tell the caller, which owns both closing this sheet and re-reading the confirmed range.
    LaunchedEffect(state) {
        if (state is RescheduleBookingUiState.Saved) onRescheduled()
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val closeSheet: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = closeSheet,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
    ) {
        RescheduleBookingBody(state = state, onRetry = viewModel::refresh, onPick = viewModel::reschedule, onClose = closeSheet)
    }
}

@Composable
private fun RescheduleBookingBody(
    state: RescheduleBookingUiState,
    onRetry: () -> Unit,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.bookings_confirmed_reschedule_sheet_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            IconButton(onClick = onClose) {
                Icon(imageVector = AgoIcons.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
        if (state is RescheduleBookingUiState.Loaded) {
            state.actionError?.let { error ->
                ActionErrorBanner(error = error, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            }
        }
        // A fixed height rather than `weight(1f)` in an unbounded `Column` — this sheet has no `Scaffold`
        // of its own to hand a `LazyColumn` a bounded parent, the identical fixed-height compromise a
        // `ModalBottomSheet`'s own content column needs whenever its list can outgrow the screen.
        Box(modifier = Modifier.fillMaxWidth().height(RescheduleListHeight)) {
            when (state) {
                RescheduleBookingUiState.Loading -> RescheduleLoadingBody()
                RescheduleBookingUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
                is RescheduleBookingUiState.Refused -> RescheduleRefusedBody(detail = state.detail, onRetry = onRetry)
                is RescheduleBookingUiState.Failed ->
                    RefusalBody(
                        reason = state.reason,
                        onRetry = onRetry,
                        unexpectedMessageRes = R.string.bookings_confirmed_reschedule_load_failed_unexpected,
                    )

                // Transient — `RescheduleBookingSheet`'s own `LaunchedEffect` calls `onRescheduled` the
                // moment this arm is reached, which the caller answers by closing this sheet. Rendered as
                // a spinner rather than left blank for the one frame before that happens.
                RescheduleBookingUiState.Saved -> RescheduleLoadingBody()

                is RescheduleBookingUiState.Loaded ->
                    if (state.availableSlots.isEmpty()) {
                        EmptyBody(stringResource(R.string.bookings_confirmed_reschedule_empty))
                    } else {
                        RescheduleSlotList(state = state, onPick = onPick)
                    }
            }
        }
    }
}

@Composable
private fun RescheduleLoadingBody() {
    Box(modifier = Modifier.fillMaxWidth().height(RescheduleListHeight), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** [RescheduleBookingUiState.Refused]'s own rendering — the identical layout `WorkerSlotsScreen.kt`'s own
 * `RefusedBody` draws for its sibling, unreachable-in-practice arm. */
@Composable
private fun RescheduleRefusedBody(
    detail: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

/**
 * Every available slot, grouped by day — the identical [groupSlotsByDay] grouping
 * `WorkerSlotsScreen.kt`'s own `WorkerSlotsList` already uses, restated here because this list is filtered
 * to [ago.chat.android.core.domain.workerslots.WorkerSlotStatus.Available] alone
 * ([RescheduleBookingUiState.Loaded]'s own doc comment), never every status.
 */
@Composable
private fun RescheduleSlotList(
    state: RescheduleBookingUiState.Loaded,
    onPick: (String) -> Unit,
) {
    val days = groupSlotsByDay(state.availableSlots)
    LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
        days.forEach { day ->
            item(key = "header-${day.localDate}") { SectionLabel(text = day.localDate) }
            items(day.slots, key = { it.eventId }) { slot ->
                RescheduleSlotRow(
                    slot = slot,
                    busy = slot.eventId == state.reschedulingEventId,
                    enabled = state.reschedulingEventId == null,
                    onClick = { onPick(slot.eventId) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RescheduleSlotRow(
    slot: WorkerSlot,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${businessLocalTimeOrNull(slot.startsAt) ?: "—"}–${businessLocalTimeOrNull(slot.endsAt) ?: "—"}",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp))
        }
    }
}

// The slot list's own fixed viewport — see [RescheduleBookingBody]'s own doc comment on why a fixed
// height, rather than `weight`, is what a `ModalBottomSheet` content column needs here.
private val RescheduleListHeight = 360.dp
