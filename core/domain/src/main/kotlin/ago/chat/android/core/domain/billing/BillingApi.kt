package ago.chat.android.core.domain.billing

import ago.chat.android.core.domain.net.NetworkFailure
import java.time.Instant

/**
 * `26-301`: the «Тариф и оплата» screen's own port — one interface behind which every billing read and
 * write lives, the identical port/adapter split [ago.chat.android.core.domain.channels.ChannelConnectionApi]
 * already establishes for the Каналы screens: a view model holding an `HttpClient` directly could not be
 * tested without one, and every HTTP-shaped decision (which JSON key means what, which base URL, which
 * `{siteId}`/`{subscriptionId}`) belongs on the far side of this interface, in `KtorBillingApi`
 * (`:core:network`).
 *
 * **Every write here is instant, against the stored payment method — except [createTokenPayment].** The
 * live `Ago.Chat.Api` contract (`26-299`/`26-291`, confirmed against the deployed backend, not only the
 * design doc) only accepts a fresh SDK `paymentToken` on the checkout-sessions/token endpoint — seats,
 * administrator slots and channel add-ons are always charged to the *stored* card and answer
 * `Billing.NoStoredPaymentMethod` (402) when there is none. There is no token-based path for those three,
 * so a caller with `hasStoredPaymentMethod == false` cannot buy an administrator slot or a channel from
 * this screen today — see [BillingErrorCodes.NO_STORED_PAYMENT_METHOD] and `BillingViewModel`'s own doc
 * comment for how the screen surfaces that gap rather than pretending a fallback exists.
 *
 * **[cancelSubscription] takes a subscription id, not a kind** — the same `POST …/cancel` endpoint works
 * on the base subscription (stop renewing the whole plan) or any channel add-on's own option subscription
 * (stop renewing just that channel), because the backend models both as rows in the same table. The
 * caller picks which id to pass; this port does not need two methods for what is one endpoint.
 */
public interface BillingApi {
    /** `GET /api/v1/sites/{siteId}/billing/status`, `site:configure`-gated on the client (the row that
     * opens this screen) and on the server. The one read every section of the screen renders from. */
    public suspend fun fetchStatus(): BillingStatusResult

    /**
     * `POST /api/v1/sites/{siteId}/billing/subscriptions/{subscriptionId}/purchase-preview`. The one
     * figure the client must never compute itself (CLAUDE.md rule 8): the seat formula is banded domain
     * logic and the administrator delta depends on a stored old price version. [subscriptionId] is always
     * the **base** subscription — there is no per-channel preview since a channel add-on has one fixed
     * price. Only the field matching [kind] needs to be non-null; the backend reads the others as
     * harmless if sent anyway, but this port asks the caller to pass exactly what the chosen [kind] means.
     */
    public suspend fun previewPurchase(
        subscriptionId: String,
        kind: BillingPurchaseKind,
        requestedSeats: Int?,
        requestedExtraAdministrators: Int?,
        channelKind: BillingChannelKind?,
    ): BillingPreviewResult

    /**
     * `POST /api/v1/sites/{siteId}/billing/checkout-sessions/token` (`26-291`) — the **one** write this
     * port has that carries a fresh native-SDK `paymentToken`. [requestedSeats] is the *total* seat count
     * being requested (not a delta), matching `ChangeSubscriptionSeatsRequest`'s own shape one level up.
     * Used both for the very first checkout (free → paid) and for a seat increase when the caller has no
     * stored payment method yet ([BillingStatus.hasStoredPaymentMethod] `== false`).
     */
    public suspend fun createTokenPayment(
        requestedSeats: Int,
        paymentToken: String,
        savePaymentMethod: Boolean,
    ): TokenPaymentResult

    /** `POST /api/v1/sites/{siteId}/billing/subscriptions/{subscriptionId}/seats`, charged to the stored
     * payment method. [requestedSeats] is the new total, not a delta. Answers
     * [BillingErrorCodes.NO_STORED_PAYMENT_METHOD] when [BillingStatus.hasStoredPaymentMethod] is `false`
     * — the caller falls back to [createTokenPayment] in that case. */
    public suspend fun changeSeats(
        subscriptionId: String,
        requestedSeats: Int,
    ): SeatsChangeResult

    /** `POST /api/v1/sites/{siteId}/billing/subscriptions/{subscriptionId}/administrators`, charged to the
     * stored payment method — always instant, never a token payment (no such endpoint exists on the live
     * contract). [requestedExtraAdministrators] is the new total beyond the plan's included count. */
    public suspend fun purchaseAdministratorSlot(
        subscriptionId: String,
        requestedExtraAdministrators: Int,
    ): AdminPurchaseResult

    /** `POST /api/v1/sites/{siteId}/billing/subscriptions/{subscriptionId}/channels`, charged to the
     * stored payment method — always instant, never a token payment (no such endpoint exists on the live
     * contract). */
    public suspend fun purchaseChannelAddOn(
        subscriptionId: String,
        kind: BillingChannelKind,
    ): ChannelPurchaseResult

    /**
     * `POST /api/v1/sites/{siteId}/billing/subscriptions/{subscriptionId}/next-period`
     * (`SetNextPeriodCompositionRequest`) — **seats and administrators only**. The live contract's own
     * handler doc comment states channel renewal is deliberately out of this command's scope; a channel's
     * own renew/stop-renew is a separate [cancelSubscription] call against its option subscription id, not
     * part of this write. See `BillingViewModel`'s own doc comment for how Card B's «Сохранить состав»
     * reconciles that with the mockup's single-button mental model.
     */
    public suspend fun setNextPeriodComposition(
        subscriptionId: String,
        requestedSeats: Int,
        requestedExtraAdministrators: Int,
    ): NextPeriodResult

    /** `POST /api/v1/sites/{siteId}/billing/subscriptions/{subscriptionId}/cancel` — cancel-at-period-end
     * for the base subscription (`subscriptionId` = [BillingSubscriptionSummary.subscriptionId]) or a
     * connected channel's own renewal (`subscriptionId` = [BillingConnectedChannel.subscriptionId]). */
    public suspend fun cancelSubscription(subscriptionId: String): CancelResult
}

/** The RFC 7807 `type` slugs this screen branches on by code, never by the server's own English
 * `detail` sentence — the identical "classify, never describe" reasoning [NetworkFailure]'s own doc
 * comment gives for a transport failure, applied here to a business refusal instead. */
public object BillingErrorCodes {
    /** Raised by [BillingApi.changeSeats] (upgrade branch), [BillingApi.purchaseAdministratorSlot] and
     * [BillingApi.purchaseChannelAddOn] whenever the base subscription has no stored payment method — the
     * one refusal code every write above [BillingApi.createTokenPayment] can return, since none of them
     * carries a fresh token of its own. */
    public const val NO_STORED_PAYMENT_METHOD: String = "Billing.NoStoredPaymentMethod"
}

/** `26-299`/`26-304`: the wire's own seven-way channel vocabulary (`ChannelKind` in `Ago.Chat.Domain`),
 * serialized as its **member name string** (`"Telegram"`, `"Max"`, …) — `26-304` moved the backend off the
 * numeric ordinal this port originally shipped against (`02aa546`), the same string-name contract the
 * console already reads. **Not** [ago.chat.android.core.domain.channels.ChannelKind], which is a
 * different, three-member vocabulary for the token-connect screens (Telegram/Max/Vk) with no wire form of
 * its own. Billing's own kind has seven members because it names every channel the platform can ever bill
 * for, even though the «Докупить сейчас»/«Следующий период» sections only ever offer [Telegram] and [Max]
 * as purchasable add-ons (the design's own final decision) — the other five only ever appear inside
 * [BillingConnectedChannel] if a deployment ever connects them by another route.
 */
public enum class BillingChannelKind {
    Max,
    Sms,
    Telegram,
    WhatsApp,
    Vk,
    Avito,
    Email,
    ;

    public companion object {
        public fun fromWireName(value: String): BillingChannelKind? = entries.firstOrNull { it.name == value }
    }
}

/** `PreviewBillingPurchaseRequest.Kind` (`Ago.Chat.Domain`'s own `BillingPurchaseKind`) — `26-304`: the
 * member name string (`"Seats"`, `"Administrators"`, `"Channel"`), not the numeric ordinal this port
 * originally shipped against. */
public enum class BillingPurchaseKind {
    Seats,
    Administrators,
    Channel,
}

/** `BillingSeatPricingDto` — the banded seat price a plan is built from. Rub amounts are plain [Double]:
 * this screen never does financial arithmetic on them (CLAUDE.md rule 8 — every priced figure a write
 * decision depends on is read fresh from the server), only formats what the server already computed. */
public data class BillingSeatPricing(
    val minSeats: Int,
    val maxSeats: Int,
    val baseSeats: Int,
    val freeSeatsIncluded: Int,
    val baseSeatPriceRub: Double,
    val pricePerExtraSeatRub: Double,
    val billingPeriodDays: Double,
)

/** `BillingConnectedChannelDto` — one row of [BillingStatus.connectedChannels]. [subscriptionId] is that
 * channel's own **option** subscription id, the one [BillingApi.cancelSubscription] takes to stop renewing
 * just this channel without touching the base plan. */
public data class BillingConnectedChannel(
    val kind: BillingChannelKind,
    val subscriptionId: String,
    val cancelRequested: Boolean,
    val currentPeriodEnd: Instant?,
)

/** `BillingSubscriptionSummaryDto` — [BillingStatus.latestSubscription]. `null` means the site has never
 * checked out (still on the free tier with nothing to renew). */
public data class BillingSubscriptionSummary(
    val subscriptionId: String,
    val status: String,
    val requestedSeats: Int,
    val tier: String,
    val cancelRequested: Boolean,
    val currentPeriodEnd: Instant?,
    val pendingSeatCount: Int?,
    val pendingTier: String?,
    val pendingAdminCount: Int?,
)

/**
 * `BillingStatusDto` — the one read every section of the screen renders from.
 *
 * [tier] is the wire's own raw slug (`"free"`/`"starter"`), never shown to a person; [tierDisplayName] is
 * the human name (`"Solo"`/`"Business"`) Card C's title and the Соло→Бизнес transition logic both read.
 * [isFreeTier] exists because the *decision* ("collapse Card A", "hide the cancel row") is a `tier == "free"`
 * check on the server's own raw slug, not a string compare against the display name a translator could
 * one day localise out from under this screen's own logic.
 */
public data class BillingStatus(
    val tier: String,
    val tierDisplayName: String,
    val seatLimit: Int,
    val seatsUsed: Int,
    val adminLimit: Int,
    val adminsUsed: Int,
    val extraAdministratorsPurchased: Int,
    val seatPricing: BillingSeatPricing,
    val adminExtraPriceRub: Double?,
    val channelCount: Int,
    val channelAddOnPriceRub: Double?,
    val nextChargeRub: Double?,
    val hasStoredPaymentMethod: Boolean,
    val connectedChannels: List<BillingConnectedChannel>,
    val latestSubscription: BillingSubscriptionSummary?,
) {
    public val isFreeTier: Boolean get() = tier.equals("free", ignoreCase = true)
}

/** What reading billing status came back with — the identical two-arm shape
 * [ago.chat.android.core.domain.channels.ChannelStatusResult] already establishes. */
public sealed interface BillingStatusResult {
    public data class Loaded(
        val status: BillingStatus,
    ) : BillingStatusResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : BillingStatusResult
}

/** `BillingPurchasePreviewResult` — the prorated figure a «Докупить за ₽X» button shows and the exact
 * [TokenPaymentResult] `amount` a seats-without-stored-card purchase passes into the SDK's own
 * `PaymentParameters`. */
public data class BillingPurchasePreview(
    val chargedNowRub: Double,
    val thenRecurringRub: Double,
    val includedUntil: Instant,
)

/** A preview read's own three outcomes. [Refused] carries the server's `type`/`detail` pair — a missing
 * required field for the chosen [BillingPurchaseKind] is `400 Billing.PreviewRequestInvalid`, not a
 * transport failure. */
public sealed interface BillingPreviewResult {
    public data class Loaded(
        val preview: BillingPurchasePreview,
    ) : BillingPreviewResult

    public data class Refused(
        val code: String,
        val detail: String,
    ) : BillingPreviewResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : BillingPreviewResult
}

/** `CreateTokenPaymentResult` — `status` is ЮKassa's own raw payment status string, read verbatim (never
 * remapped), and `confirmationUrl` is present only when a 3DS/SberPay step is still needed. */
public sealed interface TokenPaymentResult {
    public data class Accepted(
        val status: String,
        val confirmationUrl: String?,
    ) : TokenPaymentResult

    public data class Refused(
        val code: String,
        val detail: String,
    ) : TokenPaymentResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : TokenPaymentResult
}

/**
 * `ChangeSubscriptionSeatsResult` — a discriminated union with no wire-level type tag (`Ago.Chat.Api`
 * returns the runtime type flat; [ago.chat.android.core.network.billing.KtorBillingApi] tells the two
 * apart by whether `proratedAmountRub` is present in the JSON object, the same "look at which fields
 * exist" reading its own doc comment states).
 */
public sealed interface SeatsChangeResult {
    public data class Upgraded(
        val proratedAmountRub: Double,
        val newTier: String,
        val newSeatCount: Int,
    ) : SeatsChangeResult

    public data class DowngradeScheduled(
        val newTier: String,
        val newSeatCount: Int,
    ) : SeatsChangeResult

    public data class Refused(
        val code: String,
        val detail: String,
    ) : SeatsChangeResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : SeatsChangeResult
}

/** `PurchaseAdministratorSlotResult`. */
public sealed interface AdminPurchaseResult {
    public data class Purchased(
        val proratedAmountRub: Double,
        val newExtraAdministratorCount: Int,
    ) : AdminPurchaseResult

    public data class Refused(
        val code: String,
        val detail: String,
    ) : AdminPurchaseResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : AdminPurchaseResult
}

/** `PurchaseChannelAddOnResult`. [optionSubscriptionId] is that channel's own new option subscription id
 * — the one a later [BillingApi.cancelSubscription] call would take to stop renewing it. */
public sealed interface ChannelPurchaseResult {
    public data class Purchased(
        val proratedAmountRub: Double,
        val kind: BillingChannelKind,
        val optionSubscriptionId: String,
    ) : ChannelPurchaseResult

    public data class Refused(
        val code: String,
        val detail: String,
    ) : ChannelPurchaseResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : ChannelPurchaseResult
}

/** `SetNextPeriodCompositionResult`. */
public sealed interface NextPeriodResult {
    public data class Saved(
        val tier: String,
        val requestedSeats: Int,
        val requestedExtraAdministrators: Int,
    ) : NextPeriodResult

    public data class Refused(
        val code: String,
        val detail: String,
    ) : NextPeriodResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : NextPeriodResult
}

/** `CancelSubscriptionResult`. */
public sealed interface CancelResult {
    public data class Cancelled(
        val paidThroughUntil: Instant?,
    ) : CancelResult

    public data class Refused(
        val code: String,
        val detail: String,
    ) : CancelResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : CancelResult
}
