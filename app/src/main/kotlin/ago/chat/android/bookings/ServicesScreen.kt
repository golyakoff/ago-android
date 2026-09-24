package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.ConfiguredService
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * `26-96`: Записи's own «Услуги» body — the tenant's service dictionary, with the edit and the
 * withdrawal this product had no endpoint for until that item. The four-arm `when` below is the
 * identical shape [ContactsBody]/[ConfirmedBookingsBody] already draw.
 *
 * **This screen is deliberately smaller than the console's.** `ago-android` has no Услуги screen at
 * all before this item — `docs/navigation.md` names one as future work under a «Настройка записи» hub
 * that does not exist yet — so this builds the minimum `26-96` itself promises: read the dictionary,
 * correct a row, take one out of rotation. Creating a service, and the rest of that planned screen's
 * own feature list, stay with the item that scopes it.
 *
 * **A withdrawn service stays on the list, marked.** The server keeps returning archived services from
 * `GET /configuration` on purpose — it is what a worker card and a past booking resolve a service name
 * through — so this screen renders [ConfiguredService.isActive] rather than filtering on it
 * (`Ago.Calendar.Domain.Service.IsActive`).
 */
@Composable
internal fun ServicesBody(
    state: ServicesUiState,
    onRetry: () -> Unit,
    onEdit: (ConfiguredService) -> Unit,
    onCancelEdit: () -> Unit,
    onDraftChanged: (ServiceDraft) -> Unit,
    onSubmit: (ServiceDraft) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state is ServicesUiState.Loaded) {
            state.actionError?.let { error -> ActionErrorBanner(error = error, modifier = Modifier.fillMaxWidth()) }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                ServicesUiState.Loading -> LoadingBody()
                ServicesUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
                is ServicesUiState.Failed ->
                    RefusalBody(
                        reason = state.reason,
                        onRetry = onRetry,
                        unexpectedMessageRes = R.string.services_load_failed_unexpected,
                    )

                is ServicesUiState.Loaded -> {
                    val editing = state.editing
                    if (editing != null) {
                        ServiceEditForm(
                            draft = editing,
                            busy = editing.serviceId in state.busyServiceIds,
                            onDraftChanged = onDraftChanged,
                            onCancel = onCancelEdit,
                            onSubmit = { onSubmit(editing) },
                        )
                    } else if (state.services.isEmpty()) {
                        // "Empty is a state, not a blank area" - the identical rule every sibling
                        // segment on this screen already applies.
                        EmptyBody(stringResource(R.string.services_empty))
                    } else {
                        ServicesList(
                            services = state.services,
                            busyServiceIds = state.busyServiceIds,
                            onEdit = onEdit,
                            onToggleActive = { service -> onSubmit(service.toDraft().copy(isActive = !service.isActive)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServicesList(
    services: List<ConfiguredService>,
    busyServiceIds: Set<String>,
    onEdit: (ConfiguredService) -> Unit,
    onToggleActive: (ConfiguredService) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(services, key = { it.serviceId }) { service ->
            ServiceCard(
                service = service,
                busy = service.serviceId in busyServiceIds,
                onEdit = { onEdit(service) },
                onToggleActive = { onToggleActive(service) },
            )
            HorizontalDivider()
        }
    }
}

/**
 * One service: its name, its duration, its price, its description, and whether it is still on offer —
 * the same five facts the console's own table renders, stacked instead of columned because this is a
 * phone.
 *
 * The second action is «Снять с продажи»/«Вернуть в продажу», never «Удалить», and the label is the
 * point: nothing is destroyed, and a label promising deletion would misdescribe what the button does.
 * [busy] disables both actions for *this* card only, never the whole list — the identical per-row rule
 * [PendingBookingCard]'s own `busy` parameter already follows.
 */
@Composable
private fun ServiceCard(
    service: ConfiguredService,
    busy: Boolean,
    onEdit: () -> Unit,
    onToggleActive: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = service.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            // The word, never a colour alone - an operator who cannot tell the two greys apart still
            // reads "Снята с продажи".
            if (!service.isActive) {
                Text(
                    text = stringResource(R.string.services_status_withdrawn),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(
            // `bookings_duration_minutes` ("%d мин"), reused rather than a second identical string -
            // the pending-booking card already renders a duration exactly this way.
            text = stringResource(R.string.bookings_duration_minutes, service.durationMinutes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = priceLine(service),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
        service.description?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onEdit, enabled = !busy) {
                Text(text = stringResource(R.string.services_action_edit))
            }
            TextButton(onClick = onToggleActive, enabled = !busy) {
                Text(
                    text =
                        stringResource(
                            if (service.isActive) R.string.services_action_withdraw else R.string.services_action_restore,
                        ),
                )
            }
        }
    }
}

/**
 * The edit form — the five editable fields plus the on-offer checkbox, over the list rather than
 * beside it, so there is never a second form on screen an operator could submit by mistake (the same
 * one-card-two-modes rule `ago-console`'s own `CalendarServicesPage` follows).
 *
 * The note under a cleared checkbox says what withdrawing actually does *and* what it does not — the
 * bookings already taken do not change. It is rendered before the save, because that is the moment the
 * operator can still change their mind.
 */
@Composable
private fun ServiceEditForm(
    draft: ServiceDraft,
    busy: Boolean,
    onDraftChanged: (ServiceDraft) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraftChanged(draft.copy(name = it)) },
            label = { Text(text = stringResource(R.string.services_field_name)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.durationMinutes,
            onValueChange = { onDraftChanged(draft.copy(durationMinutes = it)) },
            label = { Text(text = stringResource(R.string.services_field_duration)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.priceRubles,
            onValueChange = { onDraftChanged(draft.copy(priceRubles = it)) },
            label = { Text(text = stringResource(R.string.services_field_price)) },
            placeholder = { Text(text = stringResource(R.string.services_field_price_placeholder)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = draft.priceIsFrom,
                // Meaningless without a price - the same disabled-while-blank rule the console's own
                // "от" checkbox follows, and the same normalisation `Service.Create` applies anyway.
                onCheckedChange = { onDraftChanged(draft.copy(priceIsFrom = it)) },
                enabled = !busy && draft.priceRubles.isNotBlank(),
            )
            Text(text = stringResource(R.string.services_field_price_from), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(
            value = draft.description,
            onValueChange = { onDraftChanged(draft.copy(description = it)) },
            label = { Text(text = stringResource(R.string.services_field_description)) },
            enabled = !busy,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = draft.isActive,
                onCheckedChange = { onDraftChanged(draft.copy(isActive = it)) },
                enabled = !busy,
            )
            Text(text = stringResource(R.string.services_field_active), style = MaterialTheme.typography.bodyMedium)
        }
        if (!draft.isActive) {
            Text(
                text = stringResource(R.string.services_withdrawn_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSubmit, enabled = !busy) {
                Text(text = stringResource(R.string.services_action_save))
            }
            TextButton(onClick = onCancel, enabled = !busy) {
                Text(text = stringResource(R.string.action_cancel))
            }
        }
    }
}

/** A stated price, "от" and all, or a stated absence — never a zero standing in for "nobody said".
 * Kopecks into rubles happens here and in [ServiceDraft], nowhere else. */
@Composable
private fun priceLine(service: ConfiguredService): String {
    val minorUnits = service.priceMinorUnits ?: return stringResource(R.string.services_price_unstated)
    // Built by hand rather than through String.format: a locale-sensitive "%.2f" renders a comma in
    // Russian and a dot in English for the same stored kopecks, which would make one price read two
    // ways on one device depending on which screen the operator came from.
    val rubles = "${minorUnits / 100}" + if (minorUnits % 100 == 0) "" else ".${(minorUnits % 100).toString().padStart(2, '0')}"
    return stringResource(if (service.priceIsFrom) R.string.services_price_from else R.string.services_price_exact, rubles)
}
