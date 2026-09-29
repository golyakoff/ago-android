package ago.chat.android.core.network.billing

import ago.chat.android.core.domain.billing.AdminPurchaseResult
import ago.chat.android.core.domain.billing.BillingApi
import ago.chat.android.core.domain.billing.BillingChannelKind
import ago.chat.android.core.domain.billing.BillingConnectedChannel
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
import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.net.NetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * `26-301`: the adapter behind [BillingApi] — the same "the whole status-code-to-meaning mapping lives
 * here, and only here" shape [ago.chat.android.core.network.channels.KtorChannelConnectionApi]'s own doc
 * comment states, for the billing v2 contract (`26-299`/`26-291`) instead of the channel-connect one.
 *
 * [ActiveSiteSelection.currentSiteId] is read for every method, the identical reason
 * [KtorChannelConnectionApi] already gives: every route here carries `{siteId}` in the URL itself.
 *
 * A problem-details refusal is read for its `type` (a stable machine code, e.g.
 * `"Billing.NoStoredPaymentMethod"`) alongside `detail` — never `detail` alone — because this screen
 * branches on *which* refusal happened ([ago.chat.android.core.domain.billing.BillingErrorCodes]), not
 * only on whether one did.
 */
public class KtorBillingApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : BillingApi {
    override suspend fun fetchStatus(): BillingStatusResult {
        val siteId = activeSite.currentSiteId() ?: return BillingStatusResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get(billingUrl(siteId, "status"))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return BillingStatusResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return BillingStatusResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            BillingStatusResult.Loaded(response.body<BillingStatusWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "nothing to show" - the identical
            // `KtorChannelConnectionApi`/`shapeGuard.ts` lesson read onto this endpoint.
            BillingStatusResult.Failed(NetworkFailure.from(failure))
        }
    }

    override suspend fun previewPurchase(
        subscriptionId: String,
        kind: BillingPurchaseKind,
        requestedSeats: Int?,
        requestedExtraAdministrators: Int?,
        channelKind: BillingChannelKind?,
    ): BillingPreviewResult {
        val siteId = activeSite.currentSiteId() ?: return BillingPreviewResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post(subscriptionUrl(siteId, subscriptionId, "purchase-preview")) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        PreviewRequestWireDto(
                            kind = kind.name,
                            requestedSeats = requestedSeats,
                            requestedExtraAdministrators = requestedExtraAdministrators,
                            channelKind = channelKind?.name,
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return BillingPreviewResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return problemDetail(response)?.let { BillingPreviewResult.Refused(it.first, it.second) }
                ?: BillingPreviewResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            val body = response.body<PreviewResponseWireDto>()
            BillingPreviewResult.Loaded(
                BillingPurchasePreview(
                    chargedNowRub = body.chargedNowRub,
                    thenRecurringRub = body.thenRecurringRub,
                    includedUntil = Instant.parse(body.includedUntil),
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            BillingPreviewResult.Failed(NetworkFailure.from(failure))
        }
    }

    override suspend fun createTokenPayment(
        requestedSeats: Int,
        paymentToken: String,
        savePaymentMethod: Boolean,
    ): TokenPaymentResult {
        val siteId = activeSite.currentSiteId() ?: return TokenPaymentResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post(billingUrl(siteId, "checkout-sessions/token")) {
                    contentType(ContentType.Application.Json)
                    setBody(TokenPaymentRequestWireDto(requestedSeats, paymentToken, savePaymentMethod))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return TokenPaymentResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return problemDetail(response)?.let { TokenPaymentResult.Refused(it.first, it.second) }
                ?: TokenPaymentResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            val body = response.body<TokenPaymentResponseWireDto>()
            TokenPaymentResult.Accepted(status = body.status, confirmationUrl = body.confirmationUrl)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            TokenPaymentResult.Failed(NetworkFailure.from(failure))
        }
    }

    override suspend fun changeSeats(
        subscriptionId: String,
        requestedSeats: Int,
    ): SeatsChangeResult {
        val siteId = activeSite.currentSiteId() ?: return SeatsChangeResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post(subscriptionUrl(siteId, subscriptionId, "seats")) {
                    contentType(ContentType.Application.Json)
                    setBody(SeatsChangeRequestWireDto(requestedSeats))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return SeatsChangeResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return problemDetail(response)?.let { SeatsChangeResult.Refused(it.first, it.second) }
                ?: SeatsChangeResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            // `ChangeSubscriptionSeatsResult` reaches the wire flat, with no discriminator field
            // (`BillingApi`'s own doc comment) - `Upgraded` is the arm that carries `proratedAmountRub`.
            val body = response.body<JsonObject>()
            val newTier = body.getValue("newTier").jsonPrimitive.content
            val newSeatCount = body.getValue("newSeatCount").jsonPrimitive.int
            val proratedAmountRub = body["proratedAmountRub"]?.jsonPrimitive?.doubleOrNull
            if (proratedAmountRub != null) {
                SeatsChangeResult.Upgraded(proratedAmountRub, newTier, newSeatCount)
            } else {
                SeatsChangeResult.DowngradeScheduled(newTier, newSeatCount)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            SeatsChangeResult.Failed(NetworkFailure.from(failure))
        }
    }

    override suspend fun purchaseAdministratorSlot(
        subscriptionId: String,
        requestedExtraAdministrators: Int,
    ): AdminPurchaseResult {
        val siteId = activeSite.currentSiteId() ?: return AdminPurchaseResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post(subscriptionUrl(siteId, subscriptionId, "administrators")) {
                    contentType(ContentType.Application.Json)
                    setBody(AdminPurchaseRequestWireDto(requestedExtraAdministrators))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return AdminPurchaseResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return problemDetail(response)?.let { AdminPurchaseResult.Refused(it.first, it.second) }
                ?: AdminPurchaseResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            val body = response.body<AdminPurchaseResponseWireDto>()
            AdminPurchaseResult.Purchased(body.proratedAmountRub, body.newExtraAdministratorCount)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            AdminPurchaseResult.Failed(NetworkFailure.from(failure))
        }
    }

    override suspend fun purchaseChannelAddOn(
        subscriptionId: String,
        kind: BillingChannelKind,
    ): ChannelPurchaseResult {
        val siteId = activeSite.currentSiteId() ?: return ChannelPurchaseResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post(subscriptionUrl(siteId, subscriptionId, "channels")) {
                    contentType(ContentType.Application.Json)
                    setBody(ChannelAddOnRequestWireDto(kind.name))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ChannelPurchaseResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return problemDetail(response)?.let { ChannelPurchaseResult.Refused(it.first, it.second) }
                ?: ChannelPurchaseResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            val body = response.body<ChannelAddOnResponseWireDto>()
            val purchasedKind = BillingChannelKind.fromWireName(body.channelKind) ?: kind
            ChannelPurchaseResult.Purchased(
                proratedAmountRub = body.proratedAmountRub,
                kind = purchasedKind,
                optionSubscriptionId = body.optionSubscriptionId.asSubscriptionId(),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ChannelPurchaseResult.Failed(NetworkFailure.from(failure))
        }
    }

    override suspend fun setNextPeriodComposition(
        subscriptionId: String,
        requestedSeats: Int,
        requestedExtraAdministrators: Int,
    ): NextPeriodResult {
        val siteId = activeSite.currentSiteId() ?: return NextPeriodResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post(subscriptionUrl(siteId, subscriptionId, "next-period")) {
                    contentType(ContentType.Application.Json)
                    setBody(NextPeriodRequestWireDto(requestedSeats, requestedExtraAdministrators))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return NextPeriodResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return problemDetail(response)?.let { NextPeriodResult.Refused(it.first, it.second) }
                ?: NextPeriodResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            val body = response.body<NextPeriodResponseWireDto>()
            NextPeriodResult.Saved(body.tier, body.requestedSeats, body.requestedExtraAdministrators)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            NextPeriodResult.Failed(NetworkFailure.from(failure))
        }
    }

    override suspend fun cancelSubscription(subscriptionId: String): CancelResult {
        val siteId = activeSite.currentSiteId() ?: return CancelResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post(subscriptionUrl(siteId, subscriptionId, "cancel"))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return CancelResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return problemDetail(response)?.let { CancelResult.Refused(it.first, it.second) }
                ?: CancelResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            val body = response.body<CancelResponseWireDto>()
            CancelResult.Cancelled(body.paidThroughUntil?.let(Instant::parse))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            CancelResult.Failed(NetworkFailure.from(failure))
        }
    }

    private fun billingUrl(
        siteId: String,
        suffix: String,
    ): String = "$apiBaseUrl/api/v1/sites/$siteId/billing/$suffix"

    private fun subscriptionUrl(
        siteId: String,
        subscriptionId: String,
        suffix: String,
    ): String = billingUrl(siteId, "subscriptions/$subscriptionId/$suffix")

    /** A non-2xx response read for its RFC 7807 `type` (a stable machine code) and `detail` together —
     * unlike [ago.chat.android.core.network.channels.KtorChannelConnectionApi.problemDetail], which reads
     * `detail` alone, because this screen must tell [ago.chat.android.core.domain.billing.BillingErrorCodes.NO_STORED_PAYMENT_METHOD]
     * apart from every other refusal rather than only show whichever sentence the server sent. Returns
     * `null` when no genuine problem-details body could be read, leaving the caller to fall back to
     * [NetworkFailure.ServerError]. */
    private suspend fun problemDetail(response: HttpResponse): Pair<String, String>? =
        try {
            val body = response.body<ProblemDetailsWireDto>()
            val detail = body.detail ?: return null
            (body.type ?: "Unknown") to detail
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        }
}

@Serializable
private data class ProblemDetailsWireDto(
    val type: String? = null,
    val detail: String? = null,
)

@Serializable
private data class BillingStatusWireDto(
    val tier: String,
    val seatLimit: Int,
    val seatsUsed: Int,
    val latestSubscription: BillingSubscriptionSummaryWireDto? = null,
    val tierDisplayName: String,
    val adminLimit: Int,
    val adminsUsed: Int,
    val extraAdministratorsPurchased: Int,
    val seatPricing: BillingSeatPricingWireDto,
    val adminExtraPriceRub: Double? = null,
    val channelCount: Int,
    val channelAddOnPriceRub: Double? = null,
    val nextChargeRub: Double? = null,
    val hasStoredPaymentMethod: Boolean,
    val connectedChannels: List<BillingConnectedChannelWireDto> = emptyList(),
)

@Serializable
private data class BillingConnectedChannelWireDto(
    val kind: String,
    val subscriptionId: String,
    val cancelRequested: Boolean,
    val currentPeriodEnd: String? = null,
)

@Serializable
private data class BillingSeatPricingWireDto(
    val minSeats: Int,
    val maxSeats: Int,
    val baseSeats: Int,
    val freeSeatsIncluded: Int,
    val baseSeatPriceRub: Double,
    val pricePerExtraSeatRub: Double,
    val billingPeriodDays: Double,
)

@Serializable
private data class BillingSubscriptionSummaryWireDto(
    val subscriptionId: String,
    val status: String,
    val requestedSeats: Int,
    val tier: String,
    val cancelRequested: Boolean,
    val currentPeriodEnd: String? = null,
    val pendingSeatCount: Int? = null,
    val pendingTier: String? = null,
    val pendingAdminCount: Int? = null,
)

private fun BillingStatusWireDto.toDomain(): BillingStatus =
    BillingStatus(
        tier = tier,
        tierDisplayName = tierDisplayName,
        seatLimit = seatLimit,
        seatsUsed = seatsUsed,
        adminLimit = adminLimit,
        adminsUsed = adminsUsed,
        extraAdministratorsPurchased = extraAdministratorsPurchased,
        seatPricing =
            BillingSeatPricing(
                minSeats = seatPricing.minSeats,
                maxSeats = seatPricing.maxSeats,
                baseSeats = seatPricing.baseSeats,
                freeSeatsIncluded = seatPricing.freeSeatsIncluded,
                baseSeatPriceRub = seatPricing.baseSeatPriceRub,
                pricePerExtraSeatRub = seatPricing.pricePerExtraSeatRub,
                billingPeriodDays = seatPricing.billingPeriodDays,
            ),
        adminExtraPriceRub = adminExtraPriceRub,
        channelCount = channelCount,
        channelAddOnPriceRub = channelAddOnPriceRub,
        nextChargeRub = nextChargeRub,
        hasStoredPaymentMethod = hasStoredPaymentMethod,
        // An unrecognised wire kind is dropped, never a shape failure for the whole read - a future
        // channel kind this build does not know about yet must not blank the entire billing screen.
        connectedChannels =
            connectedChannels.mapNotNull { wire ->
                BillingChannelKind.fromWireName(wire.kind)?.let { kind ->
                    BillingConnectedChannel(
                        kind = kind,
                        subscriptionId = wire.subscriptionId,
                        cancelRequested = wire.cancelRequested,
                        currentPeriodEnd = wire.currentPeriodEnd?.let(Instant::parse),
                    )
                }
            },
        latestSubscription =
            latestSubscription?.let { wire ->
                BillingSubscriptionSummary(
                    subscriptionId = wire.subscriptionId,
                    status = wire.status,
                    requestedSeats = wire.requestedSeats,
                    tier = wire.tier,
                    cancelRequested = wire.cancelRequested,
                    currentPeriodEnd = wire.currentPeriodEnd?.let(Instant::parse),
                    pendingSeatCount = wire.pendingSeatCount,
                    pendingTier = wire.pendingTier,
                    pendingAdminCount = wire.pendingAdminCount,
                )
            },
    )

@Serializable
private data class PreviewRequestWireDto(
    val kind: String,
    val requestedSeats: Int? = null,
    val requestedExtraAdministrators: Int? = null,
    val channelKind: String? = null,
)

@Serializable
private data class PreviewResponseWireDto(
    val chargedNowRub: Double,
    val thenRecurringRub: Double,
    val includedUntil: String,
)

@Serializable
private data class TokenPaymentRequestWireDto(
    val requestedSeats: Int,
    val paymentToken: String,
    val savePaymentMethod: Boolean,
)

@Serializable
private data class TokenPaymentResponseWireDto(
    val status: String,
    val confirmationUrl: String? = null,
)

@Serializable
private data class SeatsChangeRequestWireDto(
    val requestedSeats: Int,
)

@Serializable
private data class AdminPurchaseRequestWireDto(
    val requestedExtraAdministrators: Int,
)

@Serializable
private data class AdminPurchaseResponseWireDto(
    val proratedAmountRub: Double,
    val newExtraAdministratorCount: Int,
)

@Serializable
private data class ChannelAddOnRequestWireDto(
    val channelKind: String,
)

/**
 * `PurchaseChannelAddOnResult.OptionSubscriptionId` is a `BillingSubscriptionId` value object placed
 * directly into the response record (unlike every other subscription id on this contract, which the
 * backend unwraps to a bare `Guid` first) - unverified against a live response whether that serializes as
 * a bare string or `{"value": "<guid>"}`. [optionSubscriptionId] is read as [JsonElement] and
 * [asSubscriptionId] below accepts either shape, rather than this adapter guessing wrong and turning a
 * successful purchase into a shape failure. **Flagged for the author**: confirm the actual shape against
 * the deployed stand and simplify this back to a bare `String` field once it is known.
 */
@Serializable
private data class ChannelAddOnResponseWireDto(
    val proratedAmountRub: Double,
    val channelKind: String,
    val optionSubscriptionId: JsonElement,
)

private fun JsonElement.asSubscriptionId(): String =
    when (this) {
        is JsonPrimitive -> content
        is JsonObject -> (this["value"] as? JsonPrimitive)?.content ?: toString()
        else -> toString()
    }

@Serializable
private data class NextPeriodRequestWireDto(
    val requestedSeats: Int,
    val requestedExtraAdministrators: Int,
)

@Serializable
private data class NextPeriodResponseWireDto(
    val tier: String,
    val requestedSeats: Int,
    val requestedExtraAdministrators: Int,
)

@Serializable
private data class CancelResponseWireDto(
    val paidThroughUntil: String? = null,
)
