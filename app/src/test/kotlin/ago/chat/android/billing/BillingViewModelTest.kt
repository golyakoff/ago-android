package ago.chat.android.billing

import ago.chat.android.core.domain.billing.AdminPurchaseResult
import ago.chat.android.core.domain.billing.BillingApi
import ago.chat.android.core.domain.billing.BillingChannelKind
import ago.chat.android.core.domain.billing.BillingConnectedChannel
import ago.chat.android.core.domain.billing.BillingErrorCodes
import ago.chat.android.core.domain.billing.BillingPreviewResult
import ago.chat.android.core.domain.billing.BillingPurchaseKind
import ago.chat.android.core.domain.billing.BillingPurchasePreview
import ago.chat.android.core.domain.billing.BillingSeatPricing
import ago.chat.android.core.domain.billing.BillingStatus
import ago.chat.android.core.domain.billing.BillingStatusResult
import ago.chat.android.core.domain.billing.BillingSubscriptionSummary
import ago.chat.android.core.domain.billing.CancelResult
import ago.chat.android.core.domain.billing.ChannelPurchaseResult
import ago.chat.android.core.domain.billing.NextPeriodResult
import ago.chat.android.core.domain.billing.SeatsChangeResult
import ago.chat.android.core.domain.billing.TokenPaymentResult
import ago.chat.android.core.domain.net.NetworkFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.yoomoney.sdk.kassa.payments.TokenizationResult
import ru.yoomoney.sdk.kassa.payments.checkoutParameters.PaymentMethodType
import java.time.Instant

/**
 * `26-301`: [BillingViewModel]'s own state machine - the pricing/composition editor and the native SDK's
 * tokenize → post → confirm → poll pipeline, driven through a hand-written fake the identical way
 * [ago.chat.android.channels.ChannelConnectViewModelTest] already drives [ago.chat.android.channels.ChannelConnectViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts Loading before the first answer comes back`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeBillingApi(hangFetch = true))

            dispatcher.scheduler.runCurrent()

            assertEquals(BillingUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a loaded status defaults the steppers to one and the next-period editor to the current composition`() =
        runTest(dispatcher) {
            val api = FakeBillingApi(statusResult = BillingStatusResult.Loaded(businessStatus()))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            val state = viewModel.state.value as BillingUiState.Loaded
            assertEquals(1, state.seatsToBuy)
            assertEquals(1, state.adminsToBuy)
            assertEquals(3, state.nextPeriodSeats)
            assertEquals(0, state.nextPeriodAdmins)
            assertEquals(false, state.nextPeriodDirty)
        }

    @Test
    fun `a status load failure is shown as Failed with the classified reason`() =
        runTest(dispatcher) {
            val api = FakeBillingApi(statusResult = BillingStatusResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(BillingUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `the free tier never asks for a purchase preview`() =
        runTest(dispatcher) {
            val api = FakeBillingApi(statusResult = BillingStatusResult.Loaded(freeStatus()))
            viewModel(api)

            advanceUntilIdle()

            assertEquals(0, api.previewCalls.size)
        }

    @Test
    fun `a business tier with a subscription preloads seats, admins and offered channel previews`() =
        runTest(dispatcher) {
            val api = FakeBillingApi(statusResult = BillingStatusResult.Loaded(businessStatus()))
            viewModel(api)

            advanceUntilIdle()

            assertEquals(
                setOf(BillingPurchaseKind.Seats, BillingPurchaseKind.Administrators, BillingPurchaseKind.Channel),
                api.previewCalls.map { it.kind }.toSet(),
            )
            // Telegram is already connected - only MAX, the other offered kind, needs its own preview.
            assertEquals(
                listOf(BillingChannelKind.Max),
                api.previewCalls.filter { it.kind == BillingPurchaseKind.Channel }.map { it.channelKind },
            )
        }

    // --------------------------------------------------------------------- buySeats: stored card

    @Test
    fun `buying seats with a stored payment method charges it directly, never the SDK`() =
        runTest(dispatcher) {
            val api = FakeBillingApi(statusResult = BillingStatusResult.Loaded(businessStatus(hasStoredPaymentMethod = true)))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.buySeats()
            advanceUntilIdle()

            assertEquals(listOf(4), api.changeSeatsRequests)
            assertEquals(0, api.tokenPaymentRequests.size)
            // A successful buy reloads - the authoritative state is always the next read, never an
            // optimistic flip of the write's own result.
            assertEquals(2, api.fetchStatusCount)
        }

    @Test
    fun `a stored-card seats refusal is shown and clears the busy flag without reloading`() =
        runTest(dispatcher) {
            val api =
                FakeBillingApi(
                    statusResult = BillingStatusResult.Loaded(businessStatus(hasStoredPaymentMethod = true)),
                    seatsChangeResult = SeatsChangeResult.Refused(BillingErrorCodes.NO_STORED_PAYMENT_METHOD, "no card"),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.buySeats()
            advanceUntilIdle()

            val state = viewModel.state.value as BillingUiState.Loaded
            assertEquals(BillingActionErrorUi.NoStoredPaymentMethod, state.actionError)
            assertNull(state.busyAction)
            assertEquals(1, api.fetchStatusCount)
        }

    // --------------------------------------------------------------------- buySeats: native SDK

    @Test
    fun `buying seats with no stored payment method starts the SDK tokenize flow with the preview's own amount`() =
        runTest(dispatcher) {
            val api =
                FakeBillingApi(
                    statusResult = BillingStatusResult.Loaded(businessStatus(hasStoredPaymentMethod = false)),
                    previewResult =
                        BillingPreviewResult.Loaded(
                            BillingPurchasePreview(133.33, 200.0, Instant.parse("2026-10-29T00:00:00Z")),
                        ),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            val launches = mutableListOf<BillingPaymentLaunch>()
            val collectJob = launchCollector(viewModel, launches)

            viewModel.buySeats()
            advanceUntilIdle()

            assertEquals(listOf(BillingPaymentLaunch.Tokenize(133.33, false)), launches)
            val state = viewModel.state.value as BillingUiState.Loaded
            assertEquals(BillingBusyAction.Seats, state.busyAction)
            assertEquals(BillingPaymentStep.AwaitingTokenization, state.paymentStep)
            collectJob.cancel()
        }

    @Test
    fun `dismissing the SDK sheet returns to idle, never a failure`() =
        runTest(dispatcher) {
            val api =
                FakeBillingApi(
                    statusResult = BillingStatusResult.Loaded(businessStatus(hasStoredPaymentMethod = false)),
                    previewResult =
                        BillingPreviewResult.Loaded(
                            BillingPurchasePreview(133.33, 200.0, Instant.parse("2026-10-29T00:00:00Z")),
                        ),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()
            viewModel.buySeats()
            advanceUntilIdle()

            viewModel.onTokenized(null)
            advanceUntilIdle()

            val state = viewModel.state.value as BillingUiState.Loaded
            assertNull(state.busyAction)
            assertNull(state.paymentStep)
            assertNull(state.actionError)
            assertEquals(0, api.tokenPaymentRequests.size)
        }

    @Test
    fun `a tokenized payment with no confirmation step polls until the grant lands`() =
        runTest(dispatcher) {
            val stale = businessStatus(hasStoredPaymentMethod = false, requestedSeats = 3)
            val granted = businessStatus(hasStoredPaymentMethod = true, requestedSeats = 4)
            val api =
                FakeBillingApi(
                    statusResult = BillingStatusResult.Loaded(stale),
                    previewResult =
                        BillingPreviewResult.Loaded(
                            BillingPurchasePreview(133.33, 200.0, Instant.parse("2026-10-29T00:00:00Z")),
                        ),
                    tokenPaymentResult = TokenPaymentResult.Accepted(status = "succeeded", confirmationUrl = null),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()
            viewModel.buySeats()
            advanceUntilIdle()

            api.statusResult = BillingStatusResult.Loaded(granted)
            viewModel.onTokenized(TokenizationResult(paymentToken = "tok-1", paymentMethodType = PaymentMethodType.BANK_CARD))
            advanceUntilIdle()

            assertEquals(listOf("tok-1"), api.tokenPaymentRequests.map { it.paymentToken })
            val state = viewModel.state.value as BillingUiState.Loaded
            assertNull(state.busyAction)
            assertNull(state.paymentStep)
            assertEquals(4, state.status.seatLimit)
        }

    @Test
    fun `a tokenized payment needing 3ds confirmation emits a confirm launch, then polls once confirmed`() =
        runTest(dispatcher) {
            val stale = businessStatus(hasStoredPaymentMethod = false, requestedSeats = 3)
            val granted = businessStatus(hasStoredPaymentMethod = true, requestedSeats = 4)
            val api =
                FakeBillingApi(
                    statusResult = BillingStatusResult.Loaded(stale),
                    previewResult =
                        BillingPreviewResult.Loaded(
                            BillingPurchasePreview(133.33, 200.0, Instant.parse("2026-10-29T00:00:00Z")),
                        ),
                    tokenPaymentResult =
                        TokenPaymentResult.Accepted(
                            status = "pending",
                            confirmationUrl = "https://yookassa.ru/confirm/1",
                        ),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            val launches = mutableListOf<BillingPaymentLaunch>()
            val collectJob = launchCollector(viewModel, launches)

            viewModel.buySeats()
            advanceUntilIdle()
            viewModel.onTokenized(TokenizationResult(paymentToken = "tok-1", paymentMethodType = PaymentMethodType.BANK_CARD))
            advanceUntilIdle()

            // Both the earlier tokenize request and this one arrive on the same buffered channel - the
            // identical "an Intent is an event, buffered until the Route collects it" shape
            // `SignInViewModel.authorizationRequests`'s own doc comment already establishes.
            assertEquals(
                listOf(
                    BillingPaymentLaunch.Tokenize(133.33, false),
                    BillingPaymentLaunch.Confirm("https://yookassa.ru/confirm/1", PaymentMethodType.BANK_CARD),
                ),
                launches,
            )
            assertEquals(
                BillingPaymentStep.AwaitingConfirmation(PaymentMethodType.BANK_CARD),
                (viewModel.state.value as BillingUiState.Loaded).paymentStep,
            )

            api.statusResult = BillingStatusResult.Loaded(granted)
            viewModel.onConfirmed(BillingConfirmationOutcome.Confirmed)
            advanceUntilIdle()

            val finalState = viewModel.state.value as BillingUiState.Loaded
            assertNull(finalState.busyAction)
            assertEquals(4, finalState.status.seatLimit)
            collectJob.cancel()
        }

    @Test
    fun `cancelling the confirmation step returns to idle, never a failure`() =
        runTest(dispatcher) {
            val api =
                FakeBillingApi(
                    statusResult = BillingStatusResult.Loaded(businessStatus(hasStoredPaymentMethod = false)),
                    previewResult =
                        BillingPreviewResult.Loaded(
                            BillingPurchasePreview(133.33, 200.0, Instant.parse("2026-10-29T00:00:00Z")),
                        ),
                    tokenPaymentResult =
                        TokenPaymentResult.Accepted(
                            status = "pending",
                            confirmationUrl = "https://yookassa.ru/confirm/1",
                        ),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()
            viewModel.buySeats()
            advanceUntilIdle()
            viewModel.onTokenized(TokenizationResult(paymentToken = "tok-1", paymentMethodType = PaymentMethodType.BANK_CARD))
            advanceUntilIdle()

            viewModel.onConfirmed(BillingConfirmationOutcome.Cancelled)
            advanceUntilIdle()

            val state = viewModel.state.value as BillingUiState.Loaded
            assertNull(state.busyAction)
            assertNull(state.paymentStep)
            assertNull(state.actionError)
        }

    @Test
    fun `a poll that never sees the grant times out honestly, never a false done`() =
        runTest(dispatcher) {
            val stale = businessStatus(hasStoredPaymentMethod = false, requestedSeats = 3)
            val api =
                FakeBillingApi(
                    statusResult = BillingStatusResult.Loaded(stale),
                    previewResult =
                        BillingPreviewResult.Loaded(
                            BillingPurchasePreview(133.33, 200.0, Instant.parse("2026-10-29T00:00:00Z")),
                        ),
                    tokenPaymentResult = TokenPaymentResult.Accepted(status = "succeeded", confirmationUrl = null),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()
            viewModel.buySeats()
            advanceUntilIdle()

            // The grant never lands - every poll keeps reading the same, stale seat count.
            viewModel.onTokenized(TokenizationResult(paymentToken = "tok-1", paymentMethodType = PaymentMethodType.BANK_CARD))
            advanceUntilIdle()

            val state = viewModel.state.value as BillingUiState.Loaded
            assertNull(state.busyAction)
            assertNull(state.paymentStep)
            assertEquals(BillingActionErrorUi.PaymentTimedOut, state.actionError)
        }

    // --------------------------------------------------------------------- admins / channels

    @Test
    fun `buying an administrator slot posts the new total and reloads on success`() =
        runTest(dispatcher) {
            val api =
                FakeBillingApi(
                    statusResult = BillingStatusResult.Loaded(businessStatus(extraAdministratorsPurchased = 0)),
                    adminPurchaseResult = AdminPurchaseResult.Purchased(666.67, 1),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.buyAdministrators()
            advanceUntilIdle()

            assertEquals(listOf(1), api.adminPurchaseRequests)
            assertEquals(2, api.fetchStatusCount)
        }

    @Test
    fun `an administrator purchase with no stored card surfaces the dedicated error, not the raw sentence`() =
        runTest(dispatcher) {
            val api =
                FakeBillingApi(
                    statusResult = BillingStatusResult.Loaded(businessStatus()),
                    adminPurchaseResult =
                        AdminPurchaseResult.Refused(
                            BillingErrorCodes.NO_STORED_PAYMENT_METHOD,
                            "no stored payment method",
                        ),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.buyAdministrators()
            advanceUntilIdle()

            assertEquals(BillingActionErrorUi.NoStoredPaymentMethod, (viewModel.state.value as BillingUiState.Loaded).actionError)
        }

    @Test
    fun `a non-billing refusal is shown verbatim`() =
        runTest(dispatcher) {
            val api =
                FakeBillingApi(
                    statusResult = BillingStatusResult.Loaded(businessStatus()),
                    adminPurchaseResult = AdminPurchaseResult.Refused("Billing.InvalidAdministratorCount", "Count too high."),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.buyAdministrators()
            advanceUntilIdle()

            assertEquals(
                BillingActionErrorUi.ServerRefusal("Count too high."),
                (viewModel.state.value as BillingUiState.Loaded).actionError,
            )
        }

    @Test
    fun `buying a channel add-on targets the base subscription id, not a channel's own option id`() =
        runTest(dispatcher) {
            val api =
                FakeBillingApi(
                    statusResult = BillingStatusResult.Loaded(businessStatus()),
                    channelPurchaseResult = ChannelPurchaseResult.Purchased(66.67, BillingChannelKind.Max, "opt-2"),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.buyChannel(BillingChannelKind.Max)
            advanceUntilIdle()

            assertEquals(listOf("sub-1" to BillingChannelKind.Max), api.channelPurchaseRequests)
        }

    @Test
    fun `a second write while one is already in flight is a no-op`() =
        runTest(dispatcher) {
            val api = FakeBillingApi(statusResult = BillingStatusResult.Loaded(businessStatus()), hangAdminPurchase = true)
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.buyAdministrators()
            dispatcher.scheduler.runCurrent()
            viewModel.buyAdministrators()
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.adminPurchaseRequests.size)
        }

    @Test
    fun `cancelling a connected channel's renewal targets that channel's own option subscription id`() =
        runTest(dispatcher) {
            val api = FakeBillingApi(statusResult = BillingStatusResult.Loaded(businessStatus()))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.cancelChannelRenewal(BillingChannelKind.Telegram)
            advanceUntilIdle()

            assertEquals(listOf("opt-1"), api.cancelRequests)
        }

    @Test
    fun `a channel already cancelled is not cancelled again`() =
        runTest(dispatcher) {
            val status =
                businessStatus().copy(
                    connectedChannels =
                        listOf(
                            BillingConnectedChannel(BillingChannelKind.Telegram, "opt-1", cancelRequested = true, currentPeriodEnd = null),
                        ),
                )
            val api = FakeBillingApi(statusResult = BillingStatusResult.Loaded(status))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.cancelChannelRenewal(BillingChannelKind.Telegram)
            advanceUntilIdle()

            assertEquals(0, api.cancelRequests.size)
        }

    // --------------------------------------------------------------------- next period

    @Test
    fun `next-period edits are dirty only when they differ from the loaded subscription`() =
        runTest(dispatcher) {
            val api = FakeBillingApi(statusResult = BillingStatusResult.Loaded(businessStatus()))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.onNextPeriodSeatsChanged(4)
            assertTrue((viewModel.state.value as BillingUiState.Loaded).nextPeriodDirty)

            viewModel.onNextPeriodSeatsChanged(3)
            assertEquals(false, (viewModel.state.value as BillingUiState.Loaded).nextPeriodDirty)
        }

    @Test
    fun `saving the next-period composition sends seats and administrators only`() =
        runTest(dispatcher) {
            val api = FakeBillingApi(statusResult = BillingStatusResult.Loaded(businessStatus()))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.onNextPeriodSeatsChanged(4)
            viewModel.onNextPeriodAdminsChanged(1)
            viewModel.saveNextPeriodComposition()
            advanceUntilIdle()

            assertEquals(listOf(4 to 1), api.nextPeriodRequests)
        }

    @Test
    fun `saving with nothing changed is a no-op`() =
        runTest(dispatcher) {
            val api = FakeBillingApi(statusResult = BillingStatusResult.Loaded(businessStatus()))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.saveNextPeriodComposition()
            advanceUntilIdle()

            assertEquals(0, api.nextPeriodRequests.size)
        }

    // --------------------------------------------------------------------- cancel subscription

    @Test
    fun `confirming cancel targets the base subscription and reloads on success`() =
        runTest(dispatcher) {
            val api =
                FakeBillingApi(statusResult = BillingStatusResult.Loaded(businessStatus()), cancelResult = CancelResult.Cancelled(null))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.onRequestCancelSubscription()
            assertTrue((viewModel.state.value as BillingUiState.Loaded).confirmingCancelSubscription)

            viewModel.confirmCancelSubscription()
            advanceUntilIdle()

            assertEquals(listOf("sub-1"), api.cancelRequests)
        }

    private fun viewModel(api: BillingApi) =
        BillingViewModel(api, dispatcher, YooKassaConfig(clientApplicationKey = "test-key", shopId = "shop-1"))

    /** Collects [BillingViewModel.paymentLaunchRequests] into [into] - the identical "launch a collector,
     * cancel it when done" shape a `Route`'s own `LaunchedEffect` uses in production, restated here since a
     * plain `Channel` has no snapshot to assert against directly. */
    private fun CoroutineScope.launchCollector(
        viewModel: BillingViewModel,
        into: MutableList<BillingPaymentLaunch>,
    ): Job = launch(dispatcher) { viewModel.paymentLaunchRequests.collect { into += it } }
}

private fun businessStatus(
    hasStoredPaymentMethod: Boolean = true,
    requestedSeats: Int = 3,
    extraAdministratorsPurchased: Int = 0,
): BillingStatus =
    BillingStatus(
        tier = "starter",
        tierDisplayName = "Business",
        seatLimit = requestedSeats,
        seatsUsed = requestedSeats,
        adminLimit = 2 + extraAdministratorsPurchased,
        adminsUsed = 1,
        extraAdministratorsPurchased = extraAdministratorsPurchased,
        seatPricing =
            BillingSeatPricing(
                minSeats = 2,
                maxSeats = 5,
                baseSeats = 3,
                freeSeatsIncluded = 2,
                baseSeatPriceRub = 490.0,
                pricePerExtraSeatRub = 200.0,
                billingPeriodDays = 30.0,
            ),
        adminExtraPriceRub = 1000.0,
        channelCount = 1,
        channelAddOnPriceRub = 100.0,
        nextChargeRub = 490.0,
        hasStoredPaymentMethod = hasStoredPaymentMethod,
        connectedChannels =
            listOf(
                BillingConnectedChannel(BillingChannelKind.Telegram, "opt-1", cancelRequested = false, currentPeriodEnd = null),
            ),
        latestSubscription =
            BillingSubscriptionSummary(
                subscriptionId = "sub-1",
                status = "Succeeded",
                requestedSeats = requestedSeats,
                tier = "starter",
                cancelRequested = false,
                currentPeriodEnd = Instant.parse("2026-10-29T00:00:00Z"),
                pendingSeatCount = null,
                pendingTier = null,
                pendingAdminCount = null,
            ),
    )

private fun freeStatus(): BillingStatus =
    BillingStatus(
        tier = "free",
        tierDisplayName = "Solo",
        seatLimit = 2,
        seatsUsed = 1,
        adminLimit = 0,
        adminsUsed = 0,
        extraAdministratorsPurchased = 0,
        seatPricing =
            BillingSeatPricing(
                minSeats = 1,
                maxSeats = 2,
                baseSeats = 2,
                freeSeatsIncluded = 2,
                baseSeatPriceRub = 0.0,
                pricePerExtraSeatRub = 0.0,
                billingPeriodDays = 30.0,
            ),
        adminExtraPriceRub = null,
        channelCount = 0,
        channelAddOnPriceRub = null,
        nextChargeRub = null,
        hasStoredPaymentMethod = false,
        connectedChannels = emptyList(),
        latestSubscription = null,
    )

private class PreviewCall(
    val kind: BillingPurchaseKind,
    val channelKind: BillingChannelKind?,
)

/** `26-301`: the identical hand-written fake shape `FakeChannelConnectionApi` establishes - no mocking
 * framework, so "what was actually sent" assertions read off plain fields. */
private class FakeBillingApi(
    var statusResult: BillingStatusResult = BillingStatusResult.Loaded(businessStatus()),
    var previewResult: BillingPreviewResult =
        BillingPreviewResult.Loaded(
            BillingPurchasePreview(100.0, 200.0, Instant.parse("2026-10-29T00:00:00Z")),
        ),
    var tokenPaymentResult: TokenPaymentResult = TokenPaymentResult.Accepted("succeeded", null),
    var seatsChangeResult: SeatsChangeResult = SeatsChangeResult.Upgraded(133.33, "starter", 4),
    var adminPurchaseResult: AdminPurchaseResult = AdminPurchaseResult.Purchased(666.67, 1),
    var channelPurchaseResult: ChannelPurchaseResult = ChannelPurchaseResult.Purchased(66.67, BillingChannelKind.Max, "opt-2"),
    var nextPeriodResult: NextPeriodResult = NextPeriodResult.Saved("starter", 4, 1),
    var cancelResult: CancelResult = CancelResult.Cancelled(null),
    private val hangFetch: Boolean = false,
    private val hangAdminPurchase: Boolean = false,
) : BillingApi {
    var fetchStatusCount = 0
    val previewCalls = mutableListOf<PreviewCall>()
    val tokenPaymentRequests = mutableListOf<TokenPaymentRequestRecord>()
    val changeSeatsRequests = mutableListOf<Int>()
    val adminPurchaseRequests = mutableListOf<Int>()
    val channelPurchaseRequests = mutableListOf<Pair<String, BillingChannelKind>>()
    val nextPeriodRequests = mutableListOf<Pair<Int, Int>>()
    val cancelRequests = mutableListOf<String>()

    override suspend fun fetchStatus(): BillingStatusResult {
        fetchStatusCount++
        if (hangFetch) awaitCancellation()
        return statusResult
    }

    override suspend fun previewPurchase(
        subscriptionId: String,
        kind: BillingPurchaseKind,
        requestedSeats: Int?,
        requestedExtraAdministrators: Int?,
        channelKind: BillingChannelKind?,
    ): BillingPreviewResult {
        previewCalls += PreviewCall(kind, channelKind)
        return previewResult
    }

    override suspend fun createTokenPayment(
        requestedSeats: Int,
        paymentToken: String,
        savePaymentMethod: Boolean,
    ): TokenPaymentResult {
        tokenPaymentRequests += TokenPaymentRequestRecord(requestedSeats, paymentToken, savePaymentMethod)
        return tokenPaymentResult
    }

    override suspend fun changeSeats(
        subscriptionId: String,
        requestedSeats: Int,
    ): SeatsChangeResult {
        changeSeatsRequests += requestedSeats
        return seatsChangeResult
    }

    override suspend fun purchaseAdministratorSlot(
        subscriptionId: String,
        requestedExtraAdministrators: Int,
    ): AdminPurchaseResult {
        adminPurchaseRequests += requestedExtraAdministrators
        if (hangAdminPurchase) awaitCancellation()
        return adminPurchaseResult
    }

    override suspend fun purchaseChannelAddOn(
        subscriptionId: String,
        kind: BillingChannelKind,
    ): ChannelPurchaseResult {
        channelPurchaseRequests += subscriptionId to kind
        return channelPurchaseResult
    }

    override suspend fun setNextPeriodComposition(
        subscriptionId: String,
        requestedSeats: Int,
        requestedExtraAdministrators: Int,
    ): NextPeriodResult {
        nextPeriodRequests += requestedSeats to requestedExtraAdministrators
        return nextPeriodResult
    }

    override suspend fun cancelSubscription(subscriptionId: String): CancelResult {
        cancelRequests += subscriptionId
        return cancelResult
    }
}

private data class TokenPaymentRequestRecord(
    val requestedSeats: Int,
    val paymentToken: String,
    val savePaymentMethod: Boolean,
)
