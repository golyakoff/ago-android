package ago.chat.android.core.network.billing

import ago.chat.android.core.domain.billing.AdminPurchaseResult
import ago.chat.android.core.domain.billing.BillingChannelKind
import ago.chat.android.core.domain.billing.BillingErrorCodes
import ago.chat.android.core.domain.billing.BillingPurchaseKind
import ago.chat.android.core.domain.billing.BillingStatusResult
import ago.chat.android.core.domain.billing.CancelResult
import ago.chat.android.core.domain.billing.ChannelPurchaseResult
import ago.chat.android.core.domain.billing.NextPeriodResult
import ago.chat.android.core.domain.billing.SeatsChangeResult
import ago.chat.android.core.domain.billing.TokenPaymentResult
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.network.InMemoryActiveSite
import ago.chat.android.core.network.MutableAccessTokenProvider
import ago.chat.android.core.network.installAgoRestDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * `26-301`: the billing v2 read + every write, driven through the real client configuration and a
 * `MockEngine` — the identical shape
 * [ago.chat.android.core.network.channels.KtorChannelConnectionApiTest] already establishes.
 *
 * `26-304`: `ChannelKind`/the purchase `kind` serialize as the enum's own member-name string
 * (`"Telegram"`, `"Seats"`, …), not the numeric ordinal this port originally shipped against when it was
 * written against the deployed `02aa546` contract.
 */
class KtorBillingApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"
    private val siteId = "site-123"
    private val subscriptionId = "sub-1"

    @Test
    fun `status is read from the site's own billing status path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(STATUS_BODY, HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.fetchStatus() as BillingStatusResult.Loaded

            assertEquals("$baseUrl/api/v1/sites/$siteId/billing/status", requestedUrl)
            assertEquals("starter", result.status.tier)
            assertEquals("Business", result.status.tierDisplayName)
            assertEquals(false, result.status.isFreeTier)
            assertEquals(true, result.status.hasStoredPaymentMethod)
            assertEquals(1, result.status.connectedChannels.size)
            assertEquals(
                BillingChannelKind.Telegram,
                result.status.connectedChannels
                    .first()
                    .kind,
            )
            assertEquals("sub-1", result.status.latestSubscription?.subscriptionId)
            assertEquals(990.0, result.status.nextChargeRub)
        }

    @Test
    fun `an unrecognised connected-channel kind is dropped, not a shape failure`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        STATUS_BODY.replace(""""kind":"Telegram"""", """"kind":"Bogus""""),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetchStatus() as BillingStatusResult.Loaded

            assertEquals(emptyList<Any>(), result.status.connectedChannels)
        }

    @Test
    fun `no active site selected is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(BillingStatusResult.Failed(NetworkFailure.Unexpected), api.fetchStatus())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 5xx on the status read is a server error`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(BillingStatusResult.Failed(NetworkFailure.ServerError(503)), api.fetchStatus())
        }

    @Test
    fun `a dropped connection on the status read is NoConnection`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(BillingStatusResult.Failed(NetworkFailure.NoConnection), api.fetchStatus())
        }

    @Test
    fun `preview posts the discriminated request to the base subscription's own preview path`() =
        runTest {
            var requestedUrl: String? = null
            var requestedBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    requestedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"chargedNowRub":133.33,"thenRecurringRub":200.0,"includedUntil":"2026-10-29T00:00:00Z"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result =
                api.previewPurchase(
                    subscriptionId = subscriptionId,
                    kind = BillingPurchaseKind.Seats,
                    requestedSeats = 4,
                    requestedExtraAdministrators = null,
                    channelKind = null,
                )

            assertEquals("$baseUrl/api/v1/sites/$siteId/billing/subscriptions/$subscriptionId/purchase-preview", requestedUrl)
            // `encodeDefaults` is off (the project's own `agoJson` default): a null field that matches
            // its own declared default is omitted from the wire body entirely, not sent as `"…":null`.
            // `26-304`: `kind` is the member-name string, not the numeric ordinal this port originally
            // shipped against.
            assertEquals("""{"kind":"Seats","requestedSeats":4}""", requestedBody)
            check(result is ago.chat.android.core.domain.billing.BillingPreviewResult.Loaded)
            assertEquals(133.33, result.preview.chargedNowRub, 0.0)
            assertEquals(Instant.parse("2026-10-29T00:00:00Z"), result.preview.includedUntil)
        }

    @Test
    fun `a preview refusal carries the machine code, not only the sentence`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Billing.PreviewRequestInvalid","detail":"requestedSeats is required."}""",
                        HttpStatusCode.BadRequest,
                        problemHeaders(),
                    )
                }

            val result =
                api.previewPurchase(subscriptionId, BillingPurchaseKind.Seats, null, null, null)

            assertEquals(
                ago.chat.android.core.domain.billing.BillingPreviewResult.Refused(
                    "Billing.PreviewRequestInvalid",
                    "requestedSeats is required.",
                ),
                result,
            )
        }

    @Test
    fun `token payment posts to the checkout-sessions token path and reads a pending confirmation url`() =
        runTest {
            var requestedUrl: String? = null
            var requestedBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    requestedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"status":"pending","confirmationUrl":"https://yookassa.ru/confirm/1"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.createTokenPayment(requestedSeats = 4, paymentToken = "tok-1", savePaymentMethod = true)

            assertEquals("$baseUrl/api/v1/sites/$siteId/billing/checkout-sessions/token", requestedUrl)
            assertEquals("""{"requestedSeats":4,"paymentToken":"tok-1","savePaymentMethod":true}""", requestedBody)
            assertEquals(TokenPaymentResult.Accepted("pending", "https://yookassa.ru/confirm/1"), result)
        }

    @Test
    fun `changeSeats with no proratedAmountRub in the body is a scheduled downgrade`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"newTier":"free","newSeatCount":2}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(SeatsChangeResult.DowngradeScheduled("free", 2), api.changeSeats(subscriptionId, 2))
        }

    @Test
    fun `changeSeats with a proratedAmountRub in the body is an instant upgrade`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond("""{"proratedAmountRub":133.33,"newTier":"starter","newSeatCount":4}""", HttpStatusCode.OK, jsonHeaders())
                }

            assertEquals(SeatsChangeResult.Upgraded(133.33, "starter", 4), api.changeSeats(subscriptionId, 4))
        }

    @Test
    fun `changeSeats surfaces the no-stored-payment-method code, not just a sentence`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Billing.NoStoredPaymentMethod","detail":"no stored payment method"}""",
                        HttpStatusCode.PaymentRequired,
                        problemHeaders(),
                    )
                }

            val result = api.changeSeats(subscriptionId, 4) as SeatsChangeResult.Refused

            assertEquals(BillingErrorCodes.NO_STORED_PAYMENT_METHOD, result.code)
        }

    @Test
    fun `administrator slot purchase posts the requested total and reads the prorated amount`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond("""{"proratedAmountRub":666.67,"newExtraAdministratorCount":1}""", HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.purchaseAdministratorSlot(subscriptionId, 1)

            assertEquals("$baseUrl/api/v1/sites/$siteId/billing/subscriptions/$subscriptionId/administrators", requestedUrl)
            assertEquals(AdminPurchaseResult.Purchased(666.67, 1), result)
        }

    @Test
    fun `a channel add-on sends the member-name string and accepts a bare-string option subscription id`() =
        runTest {
            var requestedBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"proratedAmountRub":66.67,"channelKind":"Telegram","optionSubscriptionId":"opt-1"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.purchaseChannelAddOn(subscriptionId, BillingChannelKind.Telegram) as ChannelPurchaseResult.Purchased

            assertEquals("""{"channelKind":"Telegram"}""", requestedBody)
            assertEquals("opt-1", result.optionSubscriptionId)
            assertEquals(BillingChannelKind.Telegram, result.kind)
        }

    @Test
    fun `a channel add-on also accepts a wrapped value-object option subscription id`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"proratedAmountRub":66.67,"channelKind":"Telegram","optionSubscriptionId":{"value":"opt-2"}}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.purchaseChannelAddOn(subscriptionId, BillingChannelKind.Telegram) as ChannelPurchaseResult.Purchased

            assertEquals("opt-2", result.optionSubscriptionId)
        }

    @Test
    fun `next-period composition posts seats and administrators only, no channels field`() =
        runTest {
            var requestedBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond("""{"tier":"starter","requestedSeats":4,"requestedExtraAdministrators":1}""", HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.setNextPeriodComposition(subscriptionId, 4, 1)

            assertEquals("""{"requestedSeats":4,"requestedExtraAdministrators":1}""", requestedBody)
            assertEquals(NextPeriodResult.Saved("starter", 4, 1), result)
        }

    @Test
    fun `cancel reads an optional paid-through date`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond("""{"paidThroughUntil":"2026-10-29T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.cancelSubscription(subscriptionId)

            assertEquals("$baseUrl/api/v1/sites/$siteId/billing/subscriptions/$subscriptionId/cancel", requestedUrl)
            assertEquals(CancelResult.Cancelled(Instant.parse("2026-10-29T00:00:00Z")), result)
        }

    @Test
    fun `cancel works identically for a channel's own option subscription id`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond("""{}""", HttpStatusCode.OK, jsonHeaders())
                }

            api.cancelSubscription("opt-1")

            assertEquals("$baseUrl/api/v1/sites/$siteId/billing/subscriptions/opt-1/cancel", requestedUrl)
        }

    private fun jsonHeaders() = headersOf("Content-Type", ContentType.Application.Json.toString())

    private fun problemHeaders() = headersOf("Content-Type", "application/problem+json")

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorBillingApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorBillingApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }

    private companion object {
        val STATUS_BODY =
            """{"tier":"starter","seatLimit":5,"seatsUsed":3,
               |"latestSubscription":{"subscriptionId":"sub-1","status":"Succeeded","requestedSeats":5,"tier":"starter",
               |"cancelRequested":false,"currentPeriodEnd":"2026-10-29T00:00:00Z","pendingSeatCount":null,"pendingTier":null,
               |"pendingAdminCount":null},
               |"tierDisplayName":"Business","adminLimit":2,"adminsUsed":1,"extraAdministratorsPurchased":0,
               |"seatPricing":{"minSeats":2,"maxSeats":5,"baseSeats":3,"freeSeatsIncluded":2,"baseSeatPriceRub":990.0,
               |"pricePerExtraSeatRub":190.0,"billingPeriodDays":30.0},
               |"adminExtraPriceRub":490.0,"channelCount":1,"channelAddOnPriceRub":390.0,"nextChargeRub":990.0,
               |"hasStoredPaymentMethod":true,
               |"connectedChannels":[{"kind":"Telegram","subscriptionId":"opt-1","cancelRequested":false,"currentPeriodEnd":"2026-10-29T00:00:00Z"}]}
            """.trimMargin()
    }
}
