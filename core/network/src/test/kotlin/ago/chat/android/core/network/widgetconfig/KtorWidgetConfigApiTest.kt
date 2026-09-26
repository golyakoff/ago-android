package ago.chat.android.core.network.widgetconfig

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.widgetconfig.ChannelSwitcherIconSize
import ago.chat.android.core.domain.widgetconfig.ChannelSwitcherPlacement
import ago.chat.android.core.domain.widgetconfig.WidgetAutoOpenDelay
import ago.chat.android.core.domain.widgetconfig.WidgetConfig
import ago.chat.android.core.domain.widgetconfig.WidgetConfigResult
import ago.chat.android.core.domain.widgetconfig.WidgetConfigWriteResult
import ago.chat.android.core.domain.widgetconfig.WidgetLocale
import ago.chat.android.core.domain.widgetconfig.WidgetPosition
import ago.chat.android.core.network.InMemoryActiveSite
import ago.chat.android.core.network.MutableAccessTokenProvider
import ago.chat.android.core.network.installAgoRestDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `26-193`: the read, the write and the enum/`autoOpenDelaySeconds` mapping both directions, driven
 * through the real client configuration and a `MockEngine` — the identical shape
 * `KtorConversationTagsApiTest` already establishes for its own `{siteId}`-scoped adapter.
 *
 * The load-bearing test in this file is `update sends all 16 fields, including every boolean at its own
 * false, never an omission` — a regression test for the absent-boolean trap
 * `docs/design/tenant-widget-android.md` §3 exists to make structurally impossible.
 */
class KtorWidgetConfigApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"
    private val siteId = "site-123"

    // ------------------------------------------------------- GET /api/v1/sites/{siteId}/widget-config

    @Test
    fun `the config is read with the current site id in the path, enums and the delay mapped`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(wireJson(fullConfigWire()), HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.fetch()

            assertEquals(WidgetConfigResult.Loaded(fullConfig()), result)
            assertEquals("$baseUrl/api/v1/sites/$siteId/widget-config", requestedUrl)
        }

    @Test
    fun `an unrecognised enum spelling and delay fall back to the server's own defaults, never a decode failure`() =
        runTest {
            val wire =
                fullConfigWire().copy(
                    position = "Somewhere",
                    locale = "Fr",
                    channelSwitcherPlacement = "Somewhere",
                    channelSwitcherIconSize = "Huge",
                    autoOpenDelaySeconds = 999,
                )
            val api = apiFor(siteId) { respond(wireJson(wire), HttpStatusCode.OK, jsonHeaders()) }

            val result = api.fetch() as WidgetConfigResult.Loaded

            assertEquals(WidgetPosition.BottomRight, result.config.position)
            assertEquals(WidgetLocale.En, result.config.locale)
            assertEquals(ChannelSwitcherPlacement.AboveComposer, result.config.channelSwitcherPlacement)
            assertEquals(ChannelSwitcherIconSize.Medium, result.config.channelSwitcherIconSize)
            assertEquals(WidgetAutoOpenDelay.Seconds30, result.config.autoOpenDelay)
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

            assertEquals(WidgetConfigResult.Failed(NetworkFailure.Unexpected), api.fetch())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 5xx on the read is a server error, not the widget's own built-in defaults`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(WidgetConfigResult.Failed(NetworkFailure.ServerError(503)), api.fetch())
        }

    @Test
    fun `a dropped connection on the read is NoConnection`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(WidgetConfigResult.Failed(NetworkFailure.NoConnection), api.fetch())
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never a fabricated config`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(WidgetConfigResult.Failed(NetworkFailure.Unexpected), api.fetch())
        }

    // ------------------------------------------------------- PUT /api/v1/sites/{siteId}/widget-config

    @Test
    fun `update sends all 16 fields, including every boolean at its own false, never an omission`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var sentBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(wireJson(fullConfigWire()), HttpStatusCode.OK, jsonHeaders())
                }

            // Every boolean false, every nullable string null - the shape most likely to go missing if
            // the adapter (or a future `agoJson` change) ever started omitting an at-default field.
            val allFalseAndBlank =
                WidgetConfig(
                    primaryColorHex = null,
                    position = WidgetPosition.BottomRight,
                    locale = WidgetLocale.En,
                    panelTitle = null,
                    attractAttention = false,
                    autoOpenEnabled = false,
                    autoOpenDelay = WidgetAutoOpenDelay.Seconds30,
                    autoOpenGreetingText = null,
                    channelSwitcherPlacement = ChannelSwitcherPlacement.AboveComposer,
                    channelSwitcherIconSize = ChannelSwitcherIconSize.Medium,
                    noticeText = null,
                    noticeUrl = null,
                    requireContactConsent = false,
                    contactCaptureConfirmationText = null,
                    acceptUnverifiedPhone = false,
                    allowAttachmentUploadsByDefault = false,
                )

            api.update(allFalseAndBlank)

            assertEquals(HttpMethod.Put to "$baseUrl/api/v1/sites/$siteId/widget-config", requested)
            val sentKeys = Json.parseToJsonElement(sentBody!!).jsonObject.keys
            val expectedKeys =
                setOf(
                    "primaryColorHex",
                    "position",
                    "locale",
                    "panelTitle",
                    "attractAttention",
                    "autoOpenEnabled",
                    "autoOpenDelaySeconds",
                    "autoOpenGreetingText",
                    "channelSwitcherPlacement",
                    "channelSwitcherIconSize",
                    "noticeText",
                    "noticeUrl",
                    "requireContactConsent",
                    "contactCaptureConfirmationText",
                    "acceptUnverifiedPhone",
                    "allowAttachmentUploadsByDefault",
                )
            assertEquals("all 16 fields must be present, none omitted at their own default/false/null", expectedKeys, sentKeys)
            assertTrue(
                "requireContactConsent must be sent explicitly, never omitted - the server's [JsonRequired] gate",
                sentBody.contains("\"requireContactConsent\":false"),
            )
        }

    @Test
    fun `update sends the full config with enums as member names and the delay as seconds`() =
        runTest {
            var sentBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(wireJson(fullConfigWire()), HttpStatusCode.OK, jsonHeaders())
                }

            api.update(fullConfig())

            val sent = Json.parseToJsonElement(sentBody!!).jsonObject
            assertEquals("BottomLeft", sent.getValue("position").jsonPrimitive.content)
            assertEquals("Ru", sent.getValue("locale").jsonPrimitive.content)
            assertEquals("BelowLauncher", sent.getValue("channelSwitcherPlacement").jsonPrimitive.content)
            assertEquals("Large", sent.getValue("channelSwitcherIconSize").jsonPrimitive.content)
            assertEquals(
                60,
                sent
                    .getValue("autoOpenDelaySeconds")
                    .jsonPrimitive.content
                    .toInt(),
            )
        }

    @Test
    fun `a 2xx save echoes the server's own config, re-seeding from that rather than the request`() =
        runTest {
            val echoed = fullConfigWire().copy(panelTitle = "Нормализовано сервером")
            val api = apiFor(siteId) { respond(wireJson(echoed), HttpStatusCode.OK, jsonHeaders()) }

            val result = api.update(fullConfig())

            assertEquals(WidgetConfigWriteResult.Saved(fullConfig().copy(panelTitle = "Нормализовано сервером")), result)
        }

    @Test
    fun `a 400 with a problem-details body is rendered as that exact refusal, draft implied to stay`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"WidgetConfig.InvalidColor","detail":"primaryColorHex must be a 6-digit hex colour."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            val result = api.update(fullConfig())

            assertEquals(WidgetConfigWriteResult.Refused("primaryColorHex must be a 6-digit hex colour."), result)
        }

    @Test
    fun `a refusal with no problem-details body classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(WidgetConfigWriteResult.Failed(NetworkFailure.ServerError(403)), api.update(fullConfig()))
        }

    @Test
    fun `a dropped connection on update is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(WidgetConfigWriteResult.Failed(NetworkFailure.NoConnection), api.update(fullConfig()))
        }

    @Test
    fun `no active site selected on update is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(WidgetConfigWriteResult.Failed(NetworkFailure.Unexpected), api.update(fullConfig()))
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 2xx update that dropped the shape is Failed, never a fabricated echo`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(WidgetConfigWriteResult.Failed(NetworkFailure.Unexpected), api.update(fullConfig()))
        }

    // ------------------------------------------------------------------------------------------ fixtures

    private fun fullConfig() =
        WidgetConfig(
            primaryColorHex = "#2F6FED",
            position = WidgetPosition.BottomLeft,
            locale = WidgetLocale.Ru,
            panelTitle = "Есть вопрос?",
            attractAttention = true,
            autoOpenEnabled = true,
            autoOpenDelay = WidgetAutoOpenDelay.Seconds60,
            autoOpenGreetingText = "Здравствуйте! Чем помочь?",
            channelSwitcherPlacement = ChannelSwitcherPlacement.BelowLauncher,
            channelSwitcherIconSize = ChannelSwitcherIconSize.Large,
            noticeText = "Мы обрабатываем ваши данные согласно политике конфиденциальности.",
            noticeUrl = "https://example.com/privacy",
            requireContactConsent = true,
            contactCaptureConfirmationText = "Спасибо, {name}! Мы скоро свяжемся.",
            acceptUnverifiedPhone = true,
            allowAttachmentUploadsByDefault = true,
        )

    private fun fullConfigWire() =
        WidgetConfigWireDtoFixture(
            primaryColorHex = "#2F6FED",
            position = "BottomLeft",
            locale = "Ru",
            noticeText = "Мы обрабатываем ваши данные согласно политике конфиденциальности.",
            noticeUrl = "https://example.com/privacy",
            requireContactConsent = true,
            attractAttention = true,
            autoOpenEnabled = true,
            autoOpenDelaySeconds = 60,
            autoOpenGreetingText = "Здравствуйте! Чем помочь?",
            acceptUnverifiedPhone = true,
            allowAttachmentUploadsByDefault = true,
            contactCaptureConfirmationText = "Спасибо, {name}! Мы скоро свяжемся.",
            channelSwitcherPlacement = "BelowLauncher",
            channelSwitcherIconSize = "Large",
            panelTitle = "Есть вопрос?",
        )

    /** A test-local mirror of the adapter's own private `WidgetConfigWireDto` - kept as plain data here
     * (never reusing the `private` production type across the module boundary) purely to build response
     * fixtures fluently with named fields, then serialised through [wireJson]. */
    private data class WidgetConfigWireDtoFixture(
        val primaryColorHex: String?,
        val position: String,
        val locale: String,
        val noticeText: String?,
        val noticeUrl: String?,
        val requireContactConsent: Boolean,
        val attractAttention: Boolean,
        val autoOpenEnabled: Boolean,
        val autoOpenDelaySeconds: Int,
        val autoOpenGreetingText: String?,
        val acceptUnverifiedPhone: Boolean,
        val allowAttachmentUploadsByDefault: Boolean,
        val contactCaptureConfirmationText: String?,
        val channelSwitcherPlacement: String,
        val channelSwitcherIconSize: String,
        val panelTitle: String?,
    )

    private fun wireJson(wire: WidgetConfigWireDtoFixture): String =
        """
        {
          "primaryColorHex": ${wire.primaryColorHex?.let { "\"$it\"" }},
          "position": "${wire.position}",
          "locale": "${wire.locale}",
          "noticeText": ${wire.noticeText?.let { "\"$it\"" }},
          "noticeUrl": ${wire.noticeUrl?.let { "\"$it\"" }},
          "requireContactConsent": ${wire.requireContactConsent},
          "attractAttention": ${wire.attractAttention},
          "autoOpenEnabled": ${wire.autoOpenEnabled},
          "autoOpenDelaySeconds": ${wire.autoOpenDelaySeconds},
          "autoOpenGreetingText": ${wire.autoOpenGreetingText?.let { "\"$it\"" }},
          "acceptUnverifiedPhone": ${wire.acceptUnverifiedPhone},
          "allowAttachmentUploadsByDefault": ${wire.allowAttachmentUploadsByDefault},
          "contactCaptureConfirmationText": ${wire.contactCaptureConfirmationText?.let { "\"$it\"" }},
          "channelSwitcherPlacement": "${wire.channelSwitcherPlacement}",
          "channelSwitcherIconSize": "${wire.channelSwitcherIconSize}",
          "panelTitle": ${wire.panelTitle?.let { "\"$it\"" }}
        }
        """.trimIndent()

    private fun jsonHeaders() = headersOf("Content-Type", ContentType.Application.Json.toString())

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorWidgetConfigApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorWidgetConfigApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }
}
