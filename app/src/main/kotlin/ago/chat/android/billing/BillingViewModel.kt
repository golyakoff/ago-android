package ago.chat.android.billing

import ago.chat.android.core.domain.billing.AdminPurchaseResult
import ago.chat.android.core.domain.billing.BillingApi
import ago.chat.android.core.domain.billing.BillingChannelKind
import ago.chat.android.core.domain.billing.BillingErrorCodes
import ago.chat.android.core.domain.billing.BillingPreviewResult
import ago.chat.android.core.domain.billing.BillingPurchaseKind
import ago.chat.android.core.domain.billing.BillingStatus
import ago.chat.android.core.domain.billing.BillingStatusResult
import ago.chat.android.core.domain.billing.CancelResult
import ago.chat.android.core.domain.billing.ChannelPurchaseResult
import ago.chat.android.core.domain.billing.NextPeriodResult
import ago.chat.android.core.domain.billing.SeatsChangeResult
import ago.chat.android.core.domain.billing.TokenPaymentResult
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yoomoney.sdk.kassa.payments.TokenizationResult
import javax.inject.Inject

/**
 * `26-301`: the «Тариф и оплата» screen's own state machine - one [BillingApi] read/write port, an
 * editor for Card A's two quantity steppers and Card B's next-period composition, and the native YooKassa
 * SDK's own tokenize → post → confirm → poll pipeline for the one write that can carry a fresh payment
 * token.
 *
 * **Only [buySeats] can ever reach the SDK.** The live `Ago.Chat.Api` billing v2 contract (`26-299`,
 * confirmed against the deployed backend, not only the design brief) accepts a `paymentToken` on exactly
 * one endpoint - `POST …/checkout-sessions/token`, which changes seats. [buyAdministrators] and
 * [buyChannel] are always instant, charged to the **stored** payment method
 * ([BillingStatus.hasStoredPaymentMethod]), and answer [BillingErrorCodes.NO_STORED_PAYMENT_METHOD] with
 * no token-based fallback when there is none - this state machine surfaces that as
 * [BillingActionErrorUi.NoStoredPaymentMethod] rather than pretending a second SDK path exists. A seats
 * purchase itself only takes the SDK path when [BillingStatus.hasStoredPaymentMethod] is `false`; with a
 * stored card it goes through [BillingApi.changeSeats] exactly like the other two, for the identical
 * "charge the stored method" reason.
 *
 * **Card B's «Сохранить состав» writes seats and administrators only.** The live
 * `SetNextPeriodCompositionRequest` has no channels field - its own handler doc comment states channel
 * renewal is deliberately out of this command's scope. So a channel's own next-period fate is never part
 * of the composition editor here: turning a connected channel's renewal off is an immediate
 * [cancelChannelRenewal] call against that channel's own option subscription id
 * ([BillingStatus.connectedChannels]), the same [BillingApi.cancelSubscription] endpoint
 * [confirmCancelSubscription] uses for the base plan. There is deliberately no way to turn a cancelled
 * channel's renewal back on, or to start renewing a channel that was never purchased, for next period
 * only - no such endpoint exists on the live contract (flagged for the author; `docs/backlog/26-301-*.md`
 * §3 named this gap already).
 *
 * A write is a no-op while [BillingUiState.Loaded.busyAction] is already non-`null` - the identical guard
 * [ago.chat.android.channels.ChannelConnectViewModel]'s own doc comment states, read off the current state
 * rather than a second boolean.
 */
@HiltViewModel
internal class BillingViewModel
    @Inject
    constructor(
        private val api: BillingApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        /** Handed to the `Route`, never read by this class itself - the SDK's own key/shop id are UI
         * build config, not a view-model concern ([YooKassaConfig]'s own doc comment); this constructor
         * is the injection seam, since a Hilt `@Composable` cannot receive a plain value the way it
         * receives a `ViewModel`. */
        val yooKassaConfig: YooKassaConfig,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<BillingUiState>(BillingUiState.Loading)
        val state: StateFlow<BillingUiState> = mutableState.asStateFlow()

        private val paymentLaunches = Channel<BillingPaymentLaunch>(Channel.BUFFERED)
        val paymentLaunchRequests: Flow<BillingPaymentLaunch> = paymentLaunches.receiveAsFlow()

        private var seatsPreviewJob: Job? = null
        private var adminsPreviewJob: Job? = null
        private val channelPreviewJobs = mutableMapOf<BillingChannelKind, Job>()
        private var pollJob: Job? = null

        /** The seat total the in-flight [BillingApi.createTokenPayment] targets - stashed here, not in
         * [BillingUiState], because it is a fact about the write already sent, not something the screen
         * renders. */
        private var pendingTokenizeSeatTarget: Int? = null

        init {
            refresh()
        }

        /** The initial load, and every successful write's own reload - the identical "the reloaded status
         * is always this screen's one source of truth" posture
         * [ago.chat.android.channels.ChannelConnectViewModel.refresh]'s own doc comment states. Resets
         * Card A's steppers and Card B's editor to their defaults, since a fresh read is a new baseline to
         * edit from. */
        fun refresh() {
            seatsPreviewJob?.cancel()
            adminsPreviewJob?.cancel()
            channelPreviewJobs.values.forEach { it.cancel() }
            channelPreviewJobs.clear()
            mutableState.update { BillingUiState.Loading }
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.fetchStatus() }) {
                    is BillingStatusResult.Loaded -> onStatusLoaded(result.status)
                    is BillingStatusResult.Failed -> mutableState.update { BillingUiState.Failed(result.reason) }
                }
            }
        }

        private fun onStatusLoaded(status: BillingStatus) {
            mutableState.update {
                BillingUiState.Loaded(
                    status = status,
                    saveCard = status.hasStoredPaymentMethod,
                    seatsToBuy = DEFAULT_BUY_QUANTITY,
                    adminsToBuy = DEFAULT_BUY_QUANTITY,
                    seatsPreview = PreviewLoad.Idle,
                    adminsPreview = PreviewLoad.Idle,
                    channelPreviews = emptyMap(),
                    nextPeriodSeats = currentRequestedSeats(status),
                    nextPeriodAdmins = status.extraAdministratorsPurchased,
                    busyAction = null,
                    paymentStep = null,
                    actionError = null,
                    confirmingCancelSubscription = false,
                )
            }
            if (!status.isFreeTier && status.latestSubscription != null) {
                loadSeatsPreview(DEFAULT_BUY_QUANTITY)
                loadAdminsPreview(DEFAULT_BUY_QUANTITY)
                offeredChannelKinds(status).forEach(::loadChannelPreview)
            }
        }

        fun onSaveCardToggled(enabled: Boolean) {
            mutableState.update { state -> (state as? BillingUiState.Loaded)?.copy(saveCard = enabled) ?: state }
        }

        fun onSeatsToBuyChanged(quantity: Int) {
            val current = loadedOrNull() ?: return
            if (quantity < 1 || quantity == current.seatsToBuy) return
            mutableState.update { state -> (state as? BillingUiState.Loaded)?.copy(seatsToBuy = quantity) ?: state }
            loadSeatsPreview(quantity)
        }

        fun onAdminsToBuyChanged(quantity: Int) {
            val current = loadedOrNull() ?: return
            if (quantity < 1 || quantity == current.adminsToBuy) return
            mutableState.update { state -> (state as? BillingUiState.Loaded)?.copy(adminsToBuy = quantity) ?: state }
            loadAdminsPreview(quantity)
        }

        fun onNextPeriodSeatsChanged(seats: Int) {
            mutableState.update { state -> (state as? BillingUiState.Loaded)?.copy(nextPeriodSeats = seats) ?: state }
        }

        fun onNextPeriodAdminsChanged(admins: Int) {
            mutableState.update { state -> (state as? BillingUiState.Loaded)?.copy(nextPeriodAdmins = admins) ?: state }
        }

        fun onDismissActionError() {
            mutableState.update { state -> (state as? BillingUiState.Loaded)?.copy(actionError = null) ?: state }
        }

        // --------------------------------------------------------------------- Card A: buy now

        /** «Докупить за ₽X» for Операторы. Charges the stored card directly when one exists; otherwise
         * starts the native SDK tokenize flow - see this class's own doc comment for why seats alone can
         * take that path. */
        fun buySeats() {
            val current = loadedOrNull() ?: return
            if (current.busyAction != null) return
            val subscriptionId = current.status.latestSubscription?.subscriptionId ?: return
            val targetSeats = currentRequestedSeats(current.status) + current.seatsToBuy

            setBusy(BillingBusyAction.Seats)
            if (current.status.hasStoredPaymentMethod) {
                viewModelScope.launch {
                    when (val result = withContext(ioDispatcher) { api.changeSeats(subscriptionId, targetSeats) }) {
                        is SeatsChangeResult.Upgraded, is SeatsChangeResult.DowngradeScheduled -> refresh()
                        is SeatsChangeResult.Refused -> failBusy(mapRefusal(result.code, result.detail))
                        is SeatsChangeResult.Failed -> failBusy(BillingActionErrorUi.Unavailable(result.reason))
                    }
                }
            } else {
                val preview = (current.seatsPreview as? PreviewLoad.Loaded)?.preview
                if (preview == null) {
                    // The preview has not answered yet - the button is disabled in that state, so this is
                    // a defensive no-op, never a silent wrong-amount tokenize.
                    clearBusy()
                    return
                }
                pendingTokenizeSeatTarget = targetSeats
                mutableState.update { state ->
                    (state as? BillingUiState.Loaded)?.copy(paymentStep = BillingPaymentStep.AwaitingTokenization) ?: state
                }
                paymentLaunches.trySend(BillingPaymentLaunch.Tokenize(preview.chargedNowRub, current.saveCard))
            }
        }

        /** The `Route`'s own `rememberLauncherForActivityResult` callback for the tokenize step - `null`
         * means the operator dismissed the SDK's own sheet, which is a return to idle, never a failure
         * (`26-289`'s own `MainActivity` comment on a dismissed Custom Tab states the identical posture for
         * AppAuth). */
        fun onTokenized(result: TokenizationResult?) {
            val current = loadedOrNull() ?: return
            if (current.busyAction != BillingBusyAction.Seats || current.paymentStep != BillingPaymentStep.AwaitingTokenization) return
            val targetSeats = pendingTokenizeSeatTarget

            if (result == null || targetSeats == null) {
                pendingTokenizeSeatTarget = null
                clearBusy()
                return
            }

            viewModelScope.launch {
                val paymentResult =
                    withContext(ioDispatcher) {
                        api.createTokenPayment(
                            requestedSeats = targetSeats,
                            paymentToken = result.paymentToken,
                            savePaymentMethod = current.saveCard,
                        )
                    }
                when (paymentResult) {
                    is TokenPaymentResult.Accepted -> {
                        val confirmationUrl = paymentResult.confirmationUrl
                        if (confirmationUrl != null) {
                            mutableState.update { state ->
                                (state as? BillingUiState.Loaded)
                                    ?.copy(paymentStep = BillingPaymentStep.AwaitingConfirmation(result.paymentMethodType))
                                    ?: state
                            }
                            paymentLaunches.trySend(BillingPaymentLaunch.Confirm(confirmationUrl, result.paymentMethodType))
                        } else {
                            startPolling(targetSeats)
                        }
                    }

                    is TokenPaymentResult.Refused -> {
                        pendingTokenizeSeatTarget = null
                        failBusy(mapRefusal(paymentResult.code, paymentResult.detail))
                    }

                    is TokenPaymentResult.Failed -> {
                        pendingTokenizeSeatTarget = null
                        failBusy(BillingActionErrorUi.Unavailable(paymentResult.reason))
                    }
                }
            }
        }

        /** The `Route`'s own callback once the SDK's confirmation `Activity` returns. `RESULT_OK` means
         * "the form closed", never "paid" (`26-289` §1) - success is confirmed only by [startPolling]
         * landing the grant, never by this result on its own. */
        fun onConfirmed(outcome: BillingConfirmationOutcome) {
            val current = loadedOrNull() ?: return
            if (current.busyAction != BillingBusyAction.Seats || current.paymentStep !is BillingPaymentStep.AwaitingConfirmation) return
            val targetSeats = pendingTokenizeSeatTarget

            when (outcome) {
                BillingConfirmationOutcome.Cancelled -> {
                    pendingTokenizeSeatTarget = null
                    clearBusy()
                }

                BillingConfirmationOutcome.Failed -> {
                    pendingTokenizeSeatTarget = null
                    failBusy(BillingActionErrorUi.PaymentTimedOut)
                }

                BillingConfirmationOutcome.Confirmed -> {
                    if (targetSeats == null) {
                        clearBusy()
                    } else {
                        startPolling(targetSeats)
                    }
                }
            }
        }

        private fun startPolling(targetSeats: Int) {
            mutableState.update { state -> (state as? BillingUiState.Loaded)?.copy(paymentStep = BillingPaymentStep.Polling) ?: state }
            pollJob?.cancel()
            pollJob =
                viewModelScope.launch {
                    repeat(POLL_MAX_ATTEMPTS) {
                        delay(POLL_INTERVAL_MS)
                        when (val result = withContext(ioDispatcher) { api.fetchStatus() }) {
                            is BillingStatusResult.Loaded -> {
                                if (currentRequestedSeats(result.status) >= targetSeats) {
                                    pendingTokenizeSeatTarget = null
                                    onStatusLoaded(result.status)
                                    return@launch
                                }
                            }

                            is BillingStatusResult.Failed -> Unit // a transient read failure mid-poll just tries again
                        }
                    }
                    // The grant never landed within the bounded window - never a false "done"
                    // (`26-289`'s own honest-posture note; the identical shape the console's
                    // `usePollUntilCheckoutSettled` already establishes).
                    pendingTokenizeSeatTarget = null
                    failBusy(BillingActionErrorUi.PaymentTimedOut)
                }
        }

        // --------------------------------------------------------------------- Card A: admins / channels

        /** «Докупить за ₽X» for Администраторы - always instant, against the stored payment method; see
         * this class's own doc comment for why there is no SDK fallback here. */
        fun buyAdministrators() {
            val current = loadedOrNull() ?: return
            if (current.busyAction != null) return
            val subscriptionId = current.status.latestSubscription?.subscriptionId ?: return
            val target = current.status.extraAdministratorsPurchased + current.adminsToBuy

            setBusy(BillingBusyAction.Admins)
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.purchaseAdministratorSlot(subscriptionId, target) }) {
                    is AdminPurchaseResult.Purchased -> refresh()
                    is AdminPurchaseResult.Refused -> failBusy(mapRefusal(result.code, result.detail))
                    is AdminPurchaseResult.Failed -> failBusy(BillingActionErrorUi.Unavailable(result.reason))
                }
            }
        }

        /** «Подключить за ₽X» for one offered channel kind (Telegram/MAX) - always instant, against the
         * stored payment method; see this class's own doc comment for why there is no SDK fallback here. */
        fun buyChannel(kind: BillingChannelKind) {
            val current = loadedOrNull() ?: return
            if (current.busyAction != null) return
            val subscriptionId = current.status.latestSubscription?.subscriptionId ?: return

            setBusy(BillingBusyAction.Channel(kind))
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.purchaseChannelAddOn(subscriptionId, kind) }) {
                    is ChannelPurchaseResult.Purchased -> refresh()
                    is ChannelPurchaseResult.Refused -> failBusy(mapRefusal(result.code, result.detail))
                    is ChannelPurchaseResult.Failed -> failBusy(BillingActionErrorUi.Unavailable(result.reason))
                }
            }
        }

        /** Card B's own «Какие каналы продлевать» switch, turned off for an already-connected kind - an
         * immediate [BillingApi.cancelSubscription] against that channel's own option subscription id,
         * never part of «Сохранить состав» (this class's own doc comment). There is no matching "turn back
         * on" - a cancelled channel's switch stays disabled until the current period ends, since the live
         * contract has no un-cancel endpoint. */
        fun cancelChannelRenewal(kind: BillingChannelKind) {
            val current = loadedOrNull() ?: return
            if (current.busyAction != null) return
            val channel = current.status.connectedChannels.firstOrNull { it.kind == kind && !it.cancelRequested } ?: return

            setBusy(BillingBusyAction.Channel(kind))
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.cancelSubscription(channel.subscriptionId) }) {
                    is CancelResult.Cancelled -> refresh()
                    is CancelResult.Refused -> failBusy(mapRefusal(result.code, result.detail))
                    is CancelResult.Failed -> failBusy(BillingActionErrorUi.Unavailable(result.reason))
                }
            }
        }

        // --------------------------------------------------------------------- Card B: next period

        /** «Сохранить состав» - writes the whole seats+administrators target in one call, never on every
         * stepper tap (this class's own doc comment: display-only local edit until this is pressed). */
        fun saveNextPeriodComposition() {
            val current = loadedOrNull() ?: return
            if (current.busyAction != null || !current.nextPeriodDirty) return
            val subscriptionId = current.status.latestSubscription?.subscriptionId ?: return

            setBusy(BillingBusyAction.NextPeriod)
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        api.setNextPeriodComposition(subscriptionId, current.nextPeriodSeats, current.nextPeriodAdmins)
                    }
                when (result) {
                    is NextPeriodResult.Saved -> refresh()
                    is NextPeriodResult.Refused -> failBusy(mapRefusal(result.code, result.detail))
                    is NextPeriodResult.Failed -> failBusy(BillingActionErrorUi.Unavailable(result.reason))
                }
            }
        }

        // --------------------------------------------------------------------- Не продлевать

        fun onRequestCancelSubscription() {
            mutableState.update { state -> (state as? BillingUiState.Loaded)?.copy(confirmingCancelSubscription = true) ?: state }
        }

        fun onDismissCancelSubscription() {
            mutableState.update { state -> (state as? BillingUiState.Loaded)?.copy(confirmingCancelSubscription = false) ?: state }
        }

        fun confirmCancelSubscription() {
            val current = loadedOrNull() ?: return
            if (current.busyAction != null) return
            val subscriptionId = current.status.latestSubscription?.subscriptionId ?: return

            setBusy(BillingBusyAction.CancelSubscription)
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.cancelSubscription(subscriptionId) }) {
                    is CancelResult.Cancelled -> refresh()
                    is CancelResult.Refused -> failBusy(mapRefusal(result.code, result.detail))
                    is CancelResult.Failed -> failBusy(BillingActionErrorUi.Unavailable(result.reason))
                }
            }
        }

        // --------------------------------------------------------------------- previews

        private fun loadSeatsPreview(quantity: Int) {
            val current = loadedOrNull() ?: return
            val subscriptionId = current.status.latestSubscription?.subscriptionId ?: return
            val target = currentRequestedSeats(current.status) + quantity
            seatsPreviewJob?.cancel()
            mutableState.update { state -> (state as? BillingUiState.Loaded)?.copy(seatsPreview = PreviewLoad.Loading) ?: state }
            seatsPreviewJob =
                viewModelScope.launch {
                    val result =
                        withContext(ioDispatcher) {
                            api.previewPurchase(subscriptionId, BillingPurchaseKind.Seats, target, null, null)
                        }
                    mutableState.update { state ->
                        (state as? BillingUiState.Loaded)?.copy(seatsPreview = result.toPreviewLoad()) ?: state
                    }
                }
        }

        private fun loadAdminsPreview(quantity: Int) {
            val current = loadedOrNull() ?: return
            val subscriptionId = current.status.latestSubscription?.subscriptionId ?: return
            val target = current.status.extraAdministratorsPurchased + quantity
            adminsPreviewJob?.cancel()
            mutableState.update { state -> (state as? BillingUiState.Loaded)?.copy(adminsPreview = PreviewLoad.Loading) ?: state }
            adminsPreviewJob =
                viewModelScope.launch {
                    val result =
                        withContext(ioDispatcher) {
                            api.previewPurchase(subscriptionId, BillingPurchaseKind.Administrators, null, target, null)
                        }
                    mutableState.update { state ->
                        (state as? BillingUiState.Loaded)?.copy(adminsPreview = result.toPreviewLoad()) ?: state
                    }
                }
        }

        private fun loadChannelPreview(kind: BillingChannelKind) {
            val current = loadedOrNull() ?: return
            val subscriptionId = current.status.latestSubscription?.subscriptionId ?: return
            channelPreviewJobs[kind]?.cancel()
            mutableState.update { state ->
                (state as? BillingUiState.Loaded)?.copy(channelPreviews = state.channelPreviews + (kind to PreviewLoad.Loading)) ?: state
            }
            channelPreviewJobs[kind] =
                viewModelScope.launch {
                    val result =
                        withContext(ioDispatcher) {
                            api.previewPurchase(subscriptionId, BillingPurchaseKind.Channel, null, null, kind)
                        }
                    mutableState.update { state ->
                        (state as? BillingUiState.Loaded)?.copy(channelPreviews = state.channelPreviews + (kind to result.toPreviewLoad()))
                            ?: state
                    }
                }
        }

        // --------------------------------------------------------------------- helpers

        private fun loadedOrNull(): BillingUiState.Loaded? = mutableState.value as? BillingUiState.Loaded

        private fun setBusy(action: BillingBusyAction) {
            mutableState.update { state -> (state as? BillingUiState.Loaded)?.copy(busyAction = action, actionError = null) ?: state }
        }

        private fun clearBusy() {
            mutableState.update { state ->
                (state as? BillingUiState.Loaded)?.copy(busyAction = null, paymentStep = null) ?: state
            }
        }

        private fun failBusy(error: BillingActionErrorUi) {
            mutableState.update { state ->
                (state as? BillingUiState.Loaded)?.copy(busyAction = null, paymentStep = null, actionError = error) ?: state
            }
        }

        private companion object {
            const val DEFAULT_BUY_QUANTITY = 1
            const val POLL_INTERVAL_MS = 2_000L
            const val POLL_MAX_ATTEMPTS = 30 // ~60s, the console's own bounded-poll window (`26-290` §4)
        }
    }

/** `26-301`/§1: the only two paid channel kinds Card A/B ever offer - Сайт is the included built-in and
 * every other [BillingChannelKind] member exists only for a deployment that connected it by another
 * route. */
private val OFFERED_CHANNEL_KINDS = listOf(BillingChannelKind.Telegram, BillingChannelKind.Max)

private fun offeredChannelKinds(status: BillingStatus): List<BillingChannelKind> {
    val connectedKinds = status.connectedChannels.map { it.kind }.toSet()
    return OFFERED_CHANNEL_KINDS.filterNot { it in connectedKinds }
}

private fun currentRequestedSeats(status: BillingStatus): Int = status.latestSubscription?.requestedSeats ?: status.seatLimit

/** The one place a refusal's `code` becomes a decision - [BillingErrorCodes.NO_STORED_PAYMENT_METHOD] gets
 * its own [BillingActionErrorUi] arm (this file's own doc comment on `BillingViewModel` states why), every
 * other code is shown as the server's own [detail] verbatim, the identical
 * [ago.chat.android.channels.ChannelActionError.ServerRefusal] posture. */
private fun mapRefusal(
    code: String,
    detail: String,
): BillingActionErrorUi =
    if (code == BillingErrorCodes.NO_STORED_PAYMENT_METHOD) {
        BillingActionErrorUi.NoStoredPaymentMethod
    } else {
        BillingActionErrorUi.ServerRefusal(detail)
    }

private fun BillingPreviewResult.toPreviewLoad(): PreviewLoad =
    when (this) {
        is BillingPreviewResult.Loaded -> PreviewLoad.Loaded(preview)
        // A preview refusal (e.g. `Billing.PreviewRequestInvalid`) is a shape this screen never sends
        // deliberately - it is shown the same as any other unexpected answer, never surfaced as if it
        // were the server's own priced figure.
        is BillingPreviewResult.Refused -> PreviewLoad.Failed(NetworkFailure.Unexpected)
        is BillingPreviewResult.Failed -> PreviewLoad.Failed(reason)
    }
