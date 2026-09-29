package ago.chat.android.billing

import ago.chat.android.R
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.core.domain.billing.BillingChannelKind
import ago.chat.android.core.domain.billing.BillingConnectedChannel
import ago.chat.android.core.domain.billing.BillingStatus
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `26-301`: the v2 «Тариф и оплата» screen - a pure renderer of [BillingUiState], the same Route/Screen
 * split [ago.chat.android.channels.ChannelConnectScreen] already establishes. The desktop mockup's three
 * side-by-side/stacked cards (Текущий тариф / Докупить сейчас / Следующий период) become one scrolling
 * column here (this file's own doc comment on [BuyNowSection] and [NextPeriodSection] states why), because
 * a phone has one column, not two (`docs/backlog/26-301-*.md` §1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BillingScreen(
    state: BillingUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSaveCardToggled: (Boolean) -> Unit,
    onSeatsToBuyChanged: (Int) -> Unit,
    onAdminsToBuyChanged: (Int) -> Unit,
    onBuySeats: () -> Unit,
    onBuyAdministrators: () -> Unit,
    onBuyChannel: (BillingChannelKind) -> Unit,
    onCancelChannelRenewal: (BillingChannelKind) -> Unit,
    onNextPeriodSeatsChanged: (Int) -> Unit,
    onNextPeriodAdminsChanged: (Int) -> Unit,
    onSaveNextPeriodComposition: () -> Unit,
    onRequestCancelSubscription: () -> Unit,
    onDismissCancelSubscription: () -> Unit,
    onConfirmCancelSubscription: () -> Unit,
    onDismissActionError: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.more_administration_billing_row)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    BillingUiState.Loading -> LoadingBody()
                    is BillingUiState.Failed -> BillingFailedBody(reason = state.reason, onRetry = onRetry)
                    is BillingUiState.Loaded ->
                        BillingLoadedBody(
                            state = state,
                            onSaveCardToggled = onSaveCardToggled,
                            onSeatsToBuyChanged = onSeatsToBuyChanged,
                            onAdminsToBuyChanged = onAdminsToBuyChanged,
                            onBuySeats = onBuySeats,
                            onBuyAdministrators = onBuyAdministrators,
                            onBuyChannel = onBuyChannel,
                            onCancelChannelRenewal = onCancelChannelRenewal,
                            onNextPeriodSeatsChanged = onNextPeriodSeatsChanged,
                            onNextPeriodAdminsChanged = onNextPeriodAdminsChanged,
                            onSaveNextPeriodComposition = onSaveNextPeriodComposition,
                            onRequestCancelSubscription = onRequestCancelSubscription,
                            onDismissCancelSubscription = onDismissCancelSubscription,
                            onConfirmCancelSubscription = onConfirmCancelSubscription,
                            onDismissActionError = onDismissActionError,
                        )
                }
            }
        }
    }
}

@Composable
private fun BillingFailedBody(
    reason: NetworkFailure,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = networkFailureText(reason),
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
private fun BillingLoadedBody(
    state: BillingUiState.Loaded,
    onSaveCardToggled: (Boolean) -> Unit,
    onSeatsToBuyChanged: (Int) -> Unit,
    onAdminsToBuyChanged: (Int) -> Unit,
    onBuySeats: () -> Unit,
    onBuyAdministrators: () -> Unit,
    onBuyChannel: (BillingChannelKind) -> Unit,
    onCancelChannelRenewal: (BillingChannelKind) -> Unit,
    onNextPeriodSeatsChanged: (Int) -> Unit,
    onNextPeriodAdminsChanged: (Int) -> Unit,
    onSaveNextPeriodComposition: () -> Unit,
    onRequestCancelSubscription: () -> Unit,
    onDismissCancelSubscription: () -> Unit,
    onConfirmCancelSubscription: () -> Unit,
    onDismissActionError: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { SectionLabel(stringResource(R.string.billing_section_current_plan)) }
        item { CurrentPlanCard(state.status) }

        item { SectionLabel(stringResource(R.string.billing_section_buy_now)) }
        item {
            BuyNowSection(
                state = state,
                onSaveCardToggled = onSaveCardToggled,
                onSeatsToBuyChanged = onSeatsToBuyChanged,
                onAdminsToBuyChanged = onAdminsToBuyChanged,
                onBuySeats = onBuySeats,
                onBuyAdministrators = onBuyAdministrators,
                onBuyChannel = onBuyChannel,
            )
        }

        if (state.paymentStep != null) {
            item { PaymentInProgressCard(state.paymentStep) }
        }

        state.actionError?.let { error ->
            item { ActionErrorBanner(error = error, onDismiss = onDismissActionError) }
        }

        if (state.status.latestSubscription != null) {
            item { SectionLabel(stringResource(R.string.billing_section_next_period)) }
            item {
                NextPeriodSection(
                    state = state,
                    onSeatsChanged = onNextPeriodSeatsChanged,
                    onAdminsChanged = onNextPeriodAdminsChanged,
                    onSave = onSaveNextPeriodComposition,
                    onCancelChannelRenewal = onCancelChannelRenewal,
                    onRequestCancelSubscription = onRequestCancelSubscription,
                )
            }
        }
    }

    if (state.confirmingCancelSubscription) {
        CancelSubscriptionDialog(
            currentPeriodEnd =
                state.status.latestSubscription
                    ?.currentPeriodEnd
                    ?.let(::formatDate),
            busy = state.busyAction == BillingBusyAction.CancelSubscription,
            onDismiss = onDismissCancelSubscription,
            onConfirm = onConfirmCancelSubscription,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
    )
}

// --------------------------------------------------------------------- Card C: Текущий тариф

@Composable
private fun CurrentPlanCard(status: BillingStatus) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = status.tierDisplayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                StatusPill(
                    text =
                        stringResource(
                            if (status.isFreeTier) R.string.billing_status_free else R.string.billing_status_active,
                        ),
                    tinted = !status.isFreeTier,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            KeyValueRow(
                label = stringResource(R.string.billing_field_operators),
                value =
                    stringResource(
                        R.string.billing_seats_value,
                        status.seatsUsed,
                        status.seatLimit,
                        status.seatPricing.baseSeats,
                    ),
            )
            if (status.adminLimit > 0 || status.adminsUsed > 0) {
                KeyValueRow(
                    label = stringResource(R.string.billing_field_administrators),
                    value =
                        stringResource(
                            R.string.billing_seats_value,
                            status.adminsUsed,
                            status.adminLimit,
                            status.adminLimit - status.extraAdministratorsPurchased,
                        ),
                )
            }
            KeyValueRow(label = stringResource(R.string.billing_field_channels), value = channelsSummary(status))
            KeyValueRow(
                label = stringResource(R.string.billing_field_current_charge),
                value =
                    status.nextChargeRub?.let { "${formatRub(it)} ${stringResource(R.string.billing_value_per_month)}" }
                        ?: stringResource(R.string.billing_value_free),
            )
            status.latestSubscription?.currentPeriodEnd?.let { periodEnd ->
                KeyValueRow(label = stringResource(R.string.billing_field_paid_until), value = formatDate(periodEnd))
            }
            KeyValueRow(label = stringResource(R.string.billing_field_payment_method), value = paymentMethodSummary(status))

            val latestSubscription = status.latestSubscription
            if (latestSubscription != null && latestSubscription.cancelRequested) {
                Spacer(modifier = Modifier.height(4.dp))
                InlineNote(
                    text =
                        stringResource(
                            R.string.billing_cancel_scheduled_note,
                            latestSubscription.currentPeriodEnd?.let(::formatDate).orEmpty(),
                        ),
                    danger = true,
                )
            }
        }
    }
}

@Composable
private fun KeyValueRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
private fun channelsSummary(status: BillingStatus): String {
    val website = stringResource(R.string.billing_channel_website)
    val extra =
        status.connectedChannels
            .filterNot { it.cancelRequested }
            .mapNotNull { channelDisplayName(it.kind) }
    return if (extra.isEmpty()) website else "$website + ${extra.joinToString(", ")}"
}

@Composable
private fun channelDisplayName(kind: BillingChannelKind): String? =
    when (kind) {
        BillingChannelKind.Telegram -> stringResource(R.string.channels_telegram_title)
        BillingChannelKind.Max -> stringResource(R.string.channels_max_title)
        else -> null
    }

@Composable
private fun paymentMethodSummary(status: BillingStatus): String =
    when {
        !status.hasStoredPaymentMethod && status.latestSubscription != null -> stringResource(R.string.billing_payment_method_not_saved)
        status.hasStoredPaymentMethod -> stringResource(R.string.billing_payment_method_saved)
        else -> stringResource(R.string.billing_payment_method_none)
    }

// --------------------------------------------------------------------- Card A: Докупить сейчас

/**
 * The mockup's `.grid-2` (Card A beside Card B on a desktop) becomes this one full-width section stacked
 * above [NextPeriodSection] - a phone has one column, not two (`docs/backlog/26-301-*.md` §1). Collapses
 * to [FreeTierBuyNowNote] on the free tier: a one-off top-up is a Business-only purchase, per the mockup's
 * own final decision.
 */
@Composable
private fun BuyNowSection(
    state: BillingUiState.Loaded,
    onSaveCardToggled: (Boolean) -> Unit,
    onSeatsToBuyChanged: (Int) -> Unit,
    onAdminsToBuyChanged: (Int) -> Unit,
    onBuySeats: () -> Unit,
    onBuyAdministrators: () -> Unit,
    onBuyChannel: (BillingChannelKind) -> Unit,
) {
    val status = state.status
    if (status.isFreeTier || status.latestSubscription == null) {
        FreeTierBuyNowNote()
        return
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            SaveCardRow(saveCard = state.saveCard, onToggle = onSaveCardToggled)

            if (!state.saveCard) {
                InlineNote(text = stringResource(R.string.billing_savecard_off_warning), danger = false)
            } else {
                InlineNote(text = stringResource(R.string.billing_savecard_on_warning), danger = false)
            }

            BuyRow(
                name = stringResource(R.string.billing_field_operators),
                subLabel =
                    stringResource(
                        R.string.billing_buy_hint,
                        status.seatPricing.baseSeats,
                        formatRub(status.seatPricing.pricePerExtraSeatRub),
                    ),
                quantity = state.seatsToBuy,
                onQuantityChanged = onSeatsToBuyChanged,
                maxQuantity = (status.seatPricing.maxSeats - currentTotalSeats(status)).coerceAtLeast(0),
                preview = state.seatsPreview,
                buttonEnabled =
                    state.busyAction == null &&
                        (status.hasStoredPaymentMethod || state.seatsPreview is PreviewLoad.Loaded),
                busy = state.busyAction == BillingBusyAction.Seats,
                saveCard = state.saveCard,
                onBuy = onBuySeats,
                buyLabelRes = R.string.billing_buy_action,
                buyLabelYooKassaRes = R.string.billing_buy_action_yookassa,
            )

            BuyRow(
                name = stringResource(R.string.billing_field_administrators),
                subLabel =
                    stringResource(
                        R.string.billing_buy_hint,
                        status.adminLimit - status.extraAdministratorsPurchased,
                        status.adminExtraPriceRub?.let(::formatRub) ?: "—",
                    ),
                quantity = state.adminsToBuy,
                onQuantityChanged = onAdminsToBuyChanged,
                maxQuantity = null,
                preview = state.adminsPreview,
                buttonEnabled = state.busyAction == null && status.hasStoredPaymentMethod,
                busy = state.busyAction == BillingBusyAction.Admins,
                saveCard = state.saveCard,
                onBuy = onBuyAdministrators,
                buyLabelRes = R.string.billing_buy_action,
                buyLabelYooKassaRes = R.string.billing_buy_action_yookassa,
            )

            Text(
                text = stringResource(R.string.billing_channels_group_label, status.channelAddOnPriceRub?.let(::formatRub) ?: "—"),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
            )
            ChannelBuyRow(kind = null, connected = true, cancelled = false, preview = null, busy = false, buttonEnabled = false, onBuy = {})
            OFFERED_CHANNEL_ORDER.forEach { kind ->
                val connectedChannel = status.connectedChannels.firstOrNull { it.kind == kind }
                ChannelBuyRow(
                    kind = kind,
                    connected = connectedChannel != null && !connectedChannel.cancelRequested,
                    cancelled = connectedChannel?.cancelRequested == true,
                    preview = state.channelPreviews[kind],
                    busy = state.busyAction == BillingBusyAction.Channel(kind),
                    buttonEnabled = state.busyAction == null && status.hasStoredPaymentMethod,
                    onBuy = { onBuyChannel(kind) },
                )
            }

            Text(
                text = stringResource(R.string.billing_proration_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private fun currentTotalSeats(status: BillingStatus): Int = status.latestSubscription?.requestedSeats ?: status.seatLimit

@Composable
private fun FreeTierBuyNowNote() {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.billing_free_tier_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SaveCardRow(
    saveCard: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = saveCard, onCheckedChange = onToggle)
        Column {
            Text(
                text = stringResource(R.string.billing_savecard_label),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text =
                    stringResource(
                        if (saveCard) R.string.billing_savecard_hint_on else R.string.billing_savecard_hint_off,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BuyRow(
    name: String,
    subLabel: String,
    quantity: Int,
    onQuantityChanged: (Int) -> Unit,
    maxQuantity: Int?,
    preview: PreviewLoad,
    buttonEnabled: Boolean,
    busy: Boolean,
    saveCard: Boolean,
    onBuy: () -> Unit,
    buyLabelRes: Int,
    buyLabelYooKassaRes: Int,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(text = subLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            QuantityStepper(
                quantity = quantity,
                onQuantityChanged = onQuantityChanged,
                canIncrement = maxQuantity == null || quantity < maxQuantity,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        val amountLabel =
            when (preview) {
                is PreviewLoad.Loaded -> formatRub(preview.preview.chargedNowRub)
                else -> null
            }
        Button(
            onClick = onBuy,
            enabled = buttonEnabled && amountLabel != null && !busy && (maxQuantity == null || maxQuantity > 0),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy || preview is PreviewLoad.Loading) {
                CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text =
                        stringResource(
                            if (saveCard) buyLabelRes else buyLabelYooKassaRes,
                            amountLabel ?: "—",
                        ),
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun ChannelBuyRow(
    kind: BillingChannelKind?,
    connected: Boolean,
    cancelled: Boolean,
    preview: PreviewLoad?,
    busy: Boolean,
    buttonEnabled: Boolean,
    onBuy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val name = kind?.let { channelDisplayName(it) } ?: stringResource(R.string.billing_channel_website)
        Text(text = name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)

        when {
            kind == null -> Text(text = stringResource(R.string.billing_channel_included), color = MaterialTheme.colorScheme.primary)
            connected -> Text(text = stringResource(R.string.billing_channel_connected), color = MaterialTheme.colorScheme.primary)
            else -> {
                val amountLabel = (preview as? PreviewLoad.Loaded)?.preview?.chargedNowRub?.let(::formatRub)
                Button(onClick = onBuy, enabled = buttonEnabled && amountLabel != null && !busy) {
                    if (busy || preview is PreviewLoad.Loading) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(text = stringResource(R.string.billing_connect_action, amountLabel ?: "—"))
                    }
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun QuantityStepper(
    quantity: Int,
    onQuantityChanged: (Int) -> Unit,
    canIncrement: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (quantity > 1) onQuantityChanged(quantity - 1) }, enabled = quantity > 1) {
            Text(text = "−")
        }
        Text(text = "+$quantity", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        IconButton(onClick = { onQuantityChanged(quantity + 1) }, enabled = canIncrement) {
            Text(text = "+")
        }
    }
}

// --------------------------------------------------------------------- Payment progress / errors

@Composable
private fun PaymentInProgressCard(step: BillingPaymentStep) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            Column {
                Text(text = stringResource(R.string.billing_payment_processing_title), fontWeight = FontWeight.Bold)
                Text(
                    text = stringResource(R.string.billing_payment_processing_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActionErrorBanner(
    error: BillingActionErrorUi,
    onDismiss: () -> Unit,
) {
    val text =
        when (error) {
            BillingActionErrorUi.NoStoredPaymentMethod -> stringResource(R.string.billing_no_stored_payment_method)
            is BillingActionErrorUi.ServerRefusal -> error.detail
            is BillingActionErrorUi.Unavailable -> networkFailureText(error.reason)
            BillingActionErrorUi.PaymentTimedOut -> stringResource(R.string.billing_payment_timed_out)
        }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) }
        }
    }
}

// --------------------------------------------------------------------- Card B: Следующий период

@Composable
private fun NextPeriodSection(
    state: BillingUiState.Loaded,
    onSeatsChanged: (Int) -> Unit,
    onAdminsChanged: (Int) -> Unit,
    onSave: () -> Unit,
    onCancelChannelRenewal: (BillingChannelKind) -> Unit,
    onRequestCancelSubscription: () -> Unit,
) {
    val status = state.status
    val willTransitionToBusiness = status.isFreeTier && state.nextPeriodSeats > status.seatPricing.maxSeats

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            status.latestSubscription?.currentPeriodEnd?.let { periodEnd ->
                Text(
                    text = stringResource(R.string.billing_next_period_intro, formatDate(periodEnd)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.billing_field_operators), fontWeight = FontWeight.Bold)
                QuantityStepper(
                    quantity = state.nextPeriodSeats,
                    onQuantityChanged = { onSeatsChanged(it.coerceAtLeast(status.seatPricing.minSeats)) },
                    canIncrement = state.nextPeriodSeats < status.seatPricing.maxSeats || status.isFreeTier,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.billing_field_administrators), fontWeight = FontWeight.Bold)
                QuantityStepper(
                    quantity = state.nextPeriodAdmins,
                    onQuantityChanged = { onAdminsChanged(it.coerceAtLeast(0)) },
                    canIncrement = true,
                )
            }

            if (status.connectedChannels.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.billing_next_period_channels_label),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                )
                status.connectedChannels.forEach { channel ->
                    NextPeriodChannelRow(
                        channel = channel,
                        busy =
                            state.busyAction ==
                                BillingBusyAction.Channel(
                                    channel.kind,
                                ),
                        onToggleOff = {
                            onCancelChannelRenewal(channel.kind)
                        },
                    )
                }
            }

            if (willTransitionToBusiness) {
                TransitionToBusinessNote()
            } else if (!status.isFreeTier) {
                NextPeriodBreakdown(state = state)
            }

            Text(
                text = stringResource(R.string.billing_next_period_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )

            Button(
                onClick = onSave,
                enabled = state.busyAction == null && state.nextPeriodDirty,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                if (state.busyAction == BillingBusyAction.NextPeriod) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(text = stringResource(R.string.billing_save_composition_action))
                }
            }

            if (!status.isFreeTier) {
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                CancelSubscriptionRow(
                    currentPeriodEnd = status.latestSubscription?.currentPeriodEnd?.let(::formatDate),
                    cancelRequested = status.latestSubscription?.cancelRequested == true,
                    onRequestCancel = onRequestCancelSubscription,
                )
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun NextPeriodChannelRow(
    channel: BillingConnectedChannel,
    busy: Boolean,
    onToggleOff: () -> Unit,
) {
    val name = channelDisplayName(channel.kind) ?: return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = name, style = MaterialTheme.typography.bodyMedium)
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
        } else {
            // `26-301`/§3: there is no un-cancel endpoint on the live contract - a channel whose renewal
            // is already cancelled shows a disabled switch, never a re-enable that would silently do
            // nothing.
            Switch(
                checked = !channel.cancelRequested,
                onCheckedChange = { if (it.not()) onToggleOff() },
                enabled = !channel.cancelRequested,
            )
        }
    }
}

@Composable
private fun TransitionToBusinessNote() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.billing_transition_title),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = stringResource(R.string.billing_transition_body), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NextPeriodBreakdown(state: BillingUiState.Loaded) {
    val status = state.status
    val extraSeats = (state.nextPeriodSeats - status.seatPricing.baseSeats).coerceAtLeast(0)
    val seatCost = extraSeats * status.seatPricing.pricePerExtraSeatRub
    val adminCost = state.nextPeriodAdmins * (status.adminExtraPriceRub ?: 0.0)
    val renewingChannels = status.connectedChannels.filterNot { it.cancelRequested }
    val channelCost = renewingChannels.size * (status.channelAddOnPriceRub ?: 0.0)
    val total = status.seatPricing.baseSeatPriceRub + seatCost + adminCost + channelCost
    val currentRecurring = status.nextChargeRub

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        BreakdownLine(
            stringResource(R.string.billing_breakdown_base, status.tierDisplayName),
            formatRub(status.seatPricing.baseSeatPriceRub),
        )
        if (extraSeats > 0) {
            BreakdownLine(stringResource(R.string.billing_breakdown_extra_operators, extraSeats), formatRub(seatCost))
        }
        if (state.nextPeriodAdmins > 0) {
            BreakdownLine(stringResource(R.string.billing_breakdown_extra_admins, state.nextPeriodAdmins), formatRub(adminCost))
        }
        renewingChannels.mapNotNull { channelDisplayName(it.kind) }.forEach { name ->
            BreakdownLine(stringResource(R.string.billing_breakdown_channel, name), formatRub(status.channelAddOnPriceRub ?: 0.0))
        }

        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Row(
                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.billing_next_charge_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = formatRub(total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (currentRecurring != null && currentRecurring != total) {
                        val delta = total - currentRecurring
                        val deltaRes = if (delta > 0) R.string.billing_next_charge_delta_up else R.string.billing_next_charge_delta_down
                        Text(
                            text = stringResource(deltaRes, formatRub(kotlin.math.abs(delta))),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (delta > 0) agoStatusColors().warning else agoStatusColors().dangerText,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakdownLine(
    label: String,
    amount: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = amount, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CancelSubscriptionRow(
    currentPeriodEnd: String?,
    cancelRequested: Boolean,
    onRequestCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.billing_cancel_row_title),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.billing_cancel_row_subtitle, currentPeriodEnd.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!cancelRequested) {
            OutlinedButton(onClick = onRequestCancel) {
                Text(text = stringResource(R.string.billing_cancel_action), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CancelSubscriptionDialog(
    currentPeriodEnd: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.billing_cancel_dialog_title)) },
        text = { Text(text = stringResource(R.string.billing_cancel_dialog_body, currentPeriodEnd.orEmpty())) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(text = stringResource(R.string.billing_cancel_action), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.billing_cancel_dialog_keep)) }
        },
    )
}

// --------------------------------------------------------------------- small shared bits

@Composable
private fun InlineNote(
    text: String,
    danger: Boolean,
) {
    Surface(
        color = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (danger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun StatusPill(
    text: String,
    tinted: Boolean,
) {
    val containerColor = if (tinted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (tinted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = containerColor, contentColor = contentColor, shape = RoundedCornerShape(percent = 50)) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

private val OFFERED_CHANNEL_ORDER = listOf(BillingChannelKind.Telegram, BillingChannelKind.Max)

private val PERIOD_END_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault())

private fun formatDate(instant: Instant): String = PERIOD_END_FORMAT.format(instant)

/** Rub amounts are display-only figures this screen never computes a write decision from - formatted
 * with the project's own numeric, locale-independent style
 * ([ago.chat.android.bookings.PendingBookingsScreen]'s own `ROW_DATE_FORMAT` states the identical
 * reasoning for a date instead of a currency amount), dropping a `.00` remainder rather than always
 * showing two decimals. */
private fun formatRub(amount: Double): String {
    val rounded = Math.round(amount * 100) / 100.0
    val text =
        if (rounded == Math.floor(rounded)) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.2f", rounded)
        }
    return "$text ₽"
}
