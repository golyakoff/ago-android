package ago.chat.android.billing

import ago.chat.android.R
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.yoomoney.sdk.kassa.payments.Checkout
import ru.yoomoney.sdk.kassa.payments.checkoutParameters.Amount
import ru.yoomoney.sdk.kassa.payments.checkoutParameters.PaymentParameters
import ru.yoomoney.sdk.kassa.payments.checkoutParameters.SavePaymentMethod
import java.math.BigDecimal
import java.util.Currency

/**
 * `26-301`/§2: Ещё → Администрирование → «Тариф и оплата» - obtains its own [BillingViewModel] via
 * [hiltViewModel], the identical wiring [ago.chat.android.channels.TelegramChannelRoute] already
 * establishes for an Ещё drill-in.
 *
 * **The YooKassa SDK's own `Intent`s are launched from here, never from [BillingViewModel].** A view
 * model must not hold an `Activity`/`Context` or start an activity-for-result - it outlives configuration
 * changes and has none of its own (`docs/backlog/26-301-*.md` §2's own recommendation over adding a
 * launcher to `MainActivity`: the SDK is feature-local, unlike sign-in, which gates the whole app). So
 * [BillingViewModel] only emits *requests* ([BillingViewModel.paymentLaunchRequests]) and this `Route`
 * turns each into a launched `Intent` via [rememberLauncherForActivityResult], feeding the result straight
 * back - the exact shape `MainActivity`'s own `authorizationLauncher` + `SignInViewModel.authorizationRequests`
 * already establishes for AppAuth, restated here at the Compose layer instead of the Activity layer.
 */
@Composable
internal fun BillingRoute(
    onBack: () -> Unit,
    viewModel: BillingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val paymentTitle = stringResource(R.string.billing_payment_title)
    val paymentSubtitle = stringResource(R.string.billing_payment_subtitle)

    val tokenizeLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // A dismissed SDK sheet has no `data` and is not a failure - `onTokenized(null)`'s own doc
            // comment states the identical posture the AppAuth launcher already has for a dismissed
            // Custom Tab.
            val data = result.data
            val tokenizationResult =
                if (result.resultCode == Activity.RESULT_OK && data != null) {
                    runCatching { Checkout.createTokenizationResult(data) }.getOrNull()
                } else {
                    null
                }
            viewModel.onTokenized(tokenizationResult)
        }

    val confirmationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val outcome =
                when (result.resultCode) {
                    Activity.RESULT_OK -> BillingConfirmationOutcome.Confirmed
                    Activity.RESULT_CANCELED -> BillingConfirmationOutcome.Cancelled
                    Checkout.RESULT_ERROR -> BillingConfirmationOutcome.Failed
                    // `RESULT_OK` means "the form closed", never "paid" (`26-289` §1) - an unrecognised
                    // code is treated the same as `RESULT_OK` rather than silently dropped, since the poll
                    // that follows is the only thing that can turn this into a false "done".
                    else -> BillingConfirmationOutcome.Confirmed
                }
            viewModel.onConfirmed(outcome)
        }

    LaunchedEffect(viewModel) {
        viewModel.paymentLaunchRequests.collect { launch ->
            val config = viewModel.yooKassaConfig
            val clientApplicationKey = config.clientApplicationKey
            val shopId = config.shopId
            if (clientApplicationKey == null || shopId == null) {
                // The screen's own buy buttons are disabled unless a stored payment method exists
                // (`BillingScreen`'s own `buttonEnabled` computation), so reaching here with no SDK key
                // configured is a build-configuration gap, not a user action - there is nothing to
                // launch, so this request is simply dropped rather than crashing on a null key. A
                // [BillingPaymentLaunch.Confirm] can only ever follow a [BillingPaymentLaunch.Tokenize]
                // that already required both, so this guard covers it too.
                return@collect
            }
            when (launch) {
                is BillingPaymentLaunch.Tokenize -> {
                    val parameters =
                        PaymentParameters(
                            amount = Amount(BigDecimal.valueOf(launch.amountRub), Currency.getInstance("RUB")),
                            title = paymentTitle,
                            subtitle = paymentSubtitle,
                            clientApplicationKey = clientApplicationKey,
                            shopId = shopId,
                            savePaymentMethod = if (launch.savePaymentMethod) SavePaymentMethod.ON else SavePaymentMethod.OFF,
                        )
                    tokenizeLauncher.launch(Checkout.createTokenizeIntent(context, parameters))
                }

                is BillingPaymentLaunch.Confirm ->
                    // `8.4.0`'s own `createConfirmationIntent` takes the shop's `clientApplicationKey`/
                    // `shopId` again alongside the confirmation url - confirmed against the actual
                    // resolved artifact's bytecode (`javap`), not only the SDK's own published README,
                    // which shows an older three-argument overload that no longer exists on this version.
                    confirmationLauncher.launch(
                        Checkout.createConfirmationIntent(
                            context,
                            launch.confirmationUrl,
                            launch.paymentMethodType,
                            clientApplicationKey,
                            shopId,
                        ),
                    )
            }
        }
    }

    BillingScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::refresh,
        onSaveCardToggled = viewModel::onSaveCardToggled,
        onSeatsToBuyChanged = viewModel::onSeatsToBuyChanged,
        onAdminsToBuyChanged = viewModel::onAdminsToBuyChanged,
        onBuySeats = viewModel::buySeats,
        onBuyAdministrators = viewModel::buyAdministrators,
        onBuyChannel = viewModel::buyChannel,
        onCancelChannelRenewal = viewModel::cancelChannelRenewal,
        onNextPeriodSeatsChanged = viewModel::onNextPeriodSeatsChanged,
        onNextPeriodAdminsChanged = viewModel::onNextPeriodAdminsChanged,
        onSaveNextPeriodComposition = viewModel::saveNextPeriodComposition,
        onRequestCancelSubscription = viewModel::onRequestCancelSubscription,
        onDismissCancelSubscription = viewModel::onDismissCancelSubscription,
        onConfirmCancelSubscription = viewModel::confirmCancelSubscription,
        onDismissActionError = viewModel::onDismissActionError,
    )
}
