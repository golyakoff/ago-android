package ago.chat.android.billing

import ago.chat.android.core.domain.billing.BillingChannelKind
import ago.chat.android.core.domain.billing.BillingPurchasePreview
import ago.chat.android.core.domain.billing.BillingStatus
import ago.chat.android.core.domain.net.NetworkFailure
import ru.yoomoney.sdk.kassa.payments.checkoutParameters.PaymentMethodType

/**
 * `26-301`: [BillingViewModel]'s own state — the identical "one sealed interface, one state field" shape
 * [ago.chat.android.channels.ChannelConnectUiState] already establishes, sized for a screen with three
 * live sections instead of one connect/disconnect toggle.
 */
internal sealed interface BillingUiState {
    data object Loading : BillingUiState

    data class Failed(
        val reason: NetworkFailure,
    ) : BillingUiState

    /**
     * The one loaded shape every section renders from.
     *
     * [seatsToBuy]/[adminsToBuy] are Card A's own quantity steppers - editor state independent of
     * [status], defaulted to `1` on every [BillingViewModel.refresh]. [nextPeriodSeats]/[nextPeriodAdmins]
     * are Card B's own composition editor, defaulted from [status] and compared against it by
     * [nextPeriodDirty] to gate «Сохранить состав». **There is no editable next-period channel set** -
     * see [BillingViewModel]'s own doc comment for why a channel's renewal is always an immediate
     * [BillingBusyAction.Channel] write against [BillingStatus.connectedChannels], never part of this
     * editor.
     */
    data class Loaded(
        val status: BillingStatus,
        val saveCard: Boolean,
        val seatsToBuy: Int,
        val adminsToBuy: Int,
        val seatsPreview: PreviewLoad,
        val adminsPreview: PreviewLoad,
        val channelPreviews: Map<BillingChannelKind, PreviewLoad>,
        val nextPeriodSeats: Int,
        val nextPeriodAdmins: Int,
        val busyAction: BillingBusyAction?,
        val paymentStep: BillingPaymentStep?,
        val actionError: BillingActionErrorUi?,
        val confirmingCancelSubscription: Boolean,
    ) : BillingUiState {
        /** Whether Card B's composition differs from what is actually on the subscription - the gate for
         * enabling «Сохранить состав» (CLAUDE.md rule 8's display-only local edit, never sent until this
         * is true and the button is pressed). */
        val nextPeriodDirty: Boolean
            get() {
                val currentSeats = status.latestSubscription?.requestedSeats ?: status.seatLimit
                return nextPeriodSeats != currentSeats || nextPeriodAdmins != status.extraAdministratorsPurchased
            }
    }
}

/** One buyable row's own prorated-price read (`GET …/purchase-preview`) - never computed on the client
 * (CLAUDE.md rule 8). */
internal sealed interface PreviewLoad {
    data object Idle : PreviewLoad

    data object Loading : PreviewLoad

    data class Loaded(
        val preview: BillingPurchasePreview,
    ) : PreviewLoad

    data class Failed(
        val reason: NetworkFailure,
    ) : PreviewLoad
}

/**
 * Which single write is in flight - the identical "a write is a no-op while one is already in flight"
 * guard [ago.chat.android.channels.ChannelConnectViewModel]'s own doc comment states, read off the
 * *current* state rather than a separate boolean, widened from one flag to a small sealed type because
 * this screen has more than one kind of write and the button that shows a spinner needs to know which.
 */
internal sealed interface BillingBusyAction {
    data object Seats : BillingBusyAction

    data object Admins : BillingBusyAction

    data class Channel(
        val kind: BillingChannelKind,
    ) : BillingBusyAction

    data object NextPeriod : BillingBusyAction

    data object CancelSubscription : BillingBusyAction
}

/** Where a seats purchase without a stored payment method is, inside the native SDK's own
 * tokenize → post → confirm → poll pipeline (`BillingViewModel`'s own doc comment). Only ever set
 * alongside [BillingBusyAction.Seats]. */
internal sealed interface BillingPaymentStep {
    data object AwaitingTokenization : BillingPaymentStep

    data class AwaitingConfirmation(
        val paymentMethodType: PaymentMethodType,
    ) : BillingPaymentStep

    data object Polling : BillingPaymentStep
}

/** How the SDK's own confirmation `Activity` came back - translated by the `Route` from the raw
 * `Activity.RESULT_*`/`Checkout.RESULT_ERROR` codes into a value [BillingViewModel] can branch on without
 * naming an Android framework constant itself (`RESULT_OK` means "the form closed", never "paid" -
 * `26-289` §1; only [Failed] is a genuine authentication failure the SDK itself detected). */
internal enum class BillingConfirmationOutcome {
    Confirmed,
    Cancelled,
    Failed,
}

/** One write's own honest outcome, rendered as an inline banner - the identical split
 * [ago.chat.android.channels.ChannelActionError] already establishes, widened by one arm.
 * [NoStoredPaymentMethod] gets its own case, never the server's raw English `detail` sentence
 * (`BillingApi`'s own doc comment: the live contract has no token-based fallback for this write, so the
 * message must say what a person can actually do about it, not translate a backend log line). */
internal sealed interface BillingActionErrorUi {
    data object NoStoredPaymentMethod : BillingActionErrorUi

    data class ServerRefusal(
        val detail: String,
    ) : BillingActionErrorUi

    data class Unavailable(
        val reason: NetworkFailure,
    ) : BillingActionErrorUi

    data object PaymentTimedOut : BillingActionErrorUi
}

/** `26-301`/§2: what [BillingViewModel] asks the `Route` to launch - an `Intent` is an event, the identical
 * shape `SignInViewModel.authorizationRequests`'s own doc comment already establishes, carrying only this
 * app's own values (never an SDK type) so the event itself stays trivial to construct in a test. */
internal sealed interface BillingPaymentLaunch {
    data class Tokenize(
        val amountRub: Double,
        val savePaymentMethod: Boolean,
    ) : BillingPaymentLaunch

    data class Confirm(
        val confirmationUrl: String,
        val paymentMethodType: PaymentMethodType,
    ) : BillingPaymentLaunch
}
