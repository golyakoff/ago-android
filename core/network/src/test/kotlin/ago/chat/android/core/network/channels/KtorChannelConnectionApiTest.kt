package ago.chat.android.core.network.channels

import ago.chat.android.core.domain.channels.ChannelConnectResult
import ago.chat.android.core.domain.channels.ChannelDisconnectResult
import ago.chat.android.core.domain.channels.ChannelKind
import ago.chat.android.core.domain.channels.ChannelStatus
import ago.chat.android.core.domain.channels.ChannelStatusResult
import ago.chat.android.core.domain.channels.VkReveal
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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * `26-188`: the status read and the connect/disconnect writes, driven through the real client
 * configuration and a `MockEngine` — the identical shape `KtorConversationTagsApiTest` already
 * establishes for a `{siteId}`-scoped `Ago.Chat.Api` read plus a problem-details write.
 */
class KtorChannelConnectionApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"
    private val siteId = "site-123"

    // --------------------------------------------------------- GET /channels/{slug}

    @Test
    fun `status is read with the current site id and the channel's own slug in the path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """{"connected":true,"channelCredentialId":"cc1","createdAt":"2026-09-01T00:00:00Z",
                            |"verified":true,"unreachable":false,"checkedAt":"2026-09-26T12:00:00Z"}
                        """.trimMargin(),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetchStatus(ChannelKind.Telegram)

            assertEquals(
                ChannelStatusResult.Loaded(
                    ChannelStatus(
                        connected = true,
                        channelCredentialId = "cc1",
                        createdAt = Instant.parse("2026-09-01T00:00:00Z"),
                        verified = true,
                        unreachable = false,
                        refusalReason = null,
                        checkedAt = Instant.parse("2026-09-26T12:00:00Z"),
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/sites/$siteId/channels/telegram", requestedUrl)
        }

    @Test
    fun `max and vk read their own slug, not telegram's`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(NOT_CONNECTED_BODY, HttpStatusCode.OK, jsonHeaders())
                }

            api.fetchStatus(ChannelKind.Max)
            assertEquals("$baseUrl/api/v1/sites/$siteId/channels/max", requestedUrl)

            api.fetchStatus(ChannelKind.Vk)
            assertEquals("$baseUrl/api/v1/sites/$siteId/channels/vk", requestedUrl)
        }

    @Test
    fun `a not-connected status is Loaded, never a load failure`() =
        runTest {
            val api = apiFor(siteId) { respond(NOT_CONNECTED_BODY, HttpStatusCode.OK, jsonHeaders()) }

            val result = api.fetchStatus(ChannelKind.Telegram) as ChannelStatusResult.Loaded

            assertEquals(false, result.status.connected)
            assertEquals(null, result.status.verified)
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

            assertEquals(ChannelStatusResult.Failed(NetworkFailure.Unexpected), api.fetchStatus(ChannelKind.Telegram))
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 5xx on the status read is a server error, not a not-connected status`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(ChannelStatusResult.Failed(NetworkFailure.ServerError(503)), api.fetchStatus(ChannelKind.Telegram))
        }

    @Test
    fun `a dropped connection on the status read is NoConnection`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(ChannelStatusResult.Failed(NetworkFailure.NoConnection), api.fetchStatus(ChannelKind.Telegram))
        }

    @Test
    fun `an unparseable checkedAt is Unexpected, never an empty status`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond("""{"connected":false,"checkedAt":"not-a-timestamp"}""", HttpStatusCode.OK, jsonHeaders())
                }

            assertEquals(ChannelStatusResult.Failed(NetworkFailure.Unexpected), api.fetchStatus(ChannelKind.Telegram))
        }

    // --------------------------------------------------------- POST /channels/{slug}

    @Test
    fun `connect sends the token as the whole json body, to the site's own channel path`() =
        runTest {
            var requestedUrl: String? = null
            var requestedBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    requestedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(CONNECT_BODY_NO_REVEAL, HttpStatusCode.Created, jsonHeaders())
                }

            val result = api.connect(ChannelKind.Telegram, "bot-token-1")

            assertEquals(ChannelConnectResult.Connected(reveal = null), result)
            assertEquals("$baseUrl/api/v1/sites/$siteId/channels/telegram", requestedUrl)
            assertEquals("""{"token":"bot-token-1"}""", requestedBody)
        }

    @Test
    fun `a vk connect carries the shown-once reveal`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"channelCredentialId":"cc1","createdAt":"2026-09-26T12:00:00Z",
                            |"callbackUrl":"https://api.example/vk/callback","webhookSecret":"s3cr3t"}
                        """.trimMargin(),
                        HttpStatusCode.Created,
                        jsonHeaders(),
                    )
                }

            val result = api.connect(ChannelKind.Vk, "vk-token-1")

            assertEquals(
                ChannelConnectResult.Connected(
                    reveal = VkReveal(callbackUrl = "https://api.example/vk/callback", webhookSecret = "s3cr3t"),
                ),
                result,
            )
        }

    @Test
    fun `a telegram connect never builds a reveal even if the body somehow carried the keys`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"channelCredentialId":"cc1","createdAt":"2026-09-26T12:00:00Z",
                            |"callbackUrl":"https://not-for-telegram","webhookSecret":"s3cr3t"}
                        """.trimMargin(),
                        HttpStatusCode.Created,
                        jsonHeaders(),
                    )
                }

            assertEquals(ChannelConnectResult.Connected(reveal = null), api.connect(ChannelKind.Telegram, "t"))
        }

    @Test
    fun `a bad-token connect refusal is rendered verbatim`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Channel.InvalidToken","detail":"Токен бота недействителен."}""",
                        HttpStatusCode.BadRequest,
                        problemHeaders(),
                    )
                }

            assertEquals(ChannelConnectResult.Refused("Токен бота недействителен."), api.connect(ChannelKind.Telegram, "bad"))
        }

    @Test
    fun `a connect refusal with no problem-details body classifies as a server error`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(ChannelConnectResult.Failed(NetworkFailure.ServerError(403)), api.connect(ChannelKind.Telegram, "t"))
        }

    @Test
    fun `a dropped connection on connect is a transport failure`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(ChannelConnectResult.Failed(NetworkFailure.NoConnection), api.connect(ChannelKind.Telegram, "t"))
        }

    @Test
    fun `no active site selected on connect is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(ChannelConnectResult.Failed(NetworkFailure.Unexpected), api.connect(ChannelKind.Telegram, "t"))
            assertEquals(0, calls)
        }

    // --------------------------------------------------------- DELETE /channels/{slug}/{id}

    @Test
    fun `a 204 disconnect is sent as a real DELETE to the credential's own path`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(ChannelDisconnectResult.Disconnected, api.disconnect(ChannelKind.Telegram, "cc1"))
            assertEquals(HttpMethod.Delete to "$baseUrl/api/v1/sites/$siteId/channels/telegram/cc1", requested)
        }

    @Test
    fun `a disconnect refusal is rendered verbatim`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Channel.NotConnected","detail":"Канал уже отключён."}""",
                        HttpStatusCode.Conflict,
                        problemHeaders(),
                    )
                }

            assertEquals(ChannelDisconnectResult.Refused("Канал уже отключён."), api.disconnect(ChannelKind.Telegram, "cc1"))
        }

    @Test
    fun `a disconnect refusal with no problem-details body classifies as a server error`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(ChannelDisconnectResult.Failed(NetworkFailure.ServerError(403)), api.disconnect(ChannelKind.Telegram, "cc1"))
        }

    @Test
    fun `a dropped connection on disconnect is a transport failure`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(ChannelDisconnectResult.Failed(NetworkFailure.NoConnection), api.disconnect(ChannelKind.Telegram, "cc1"))
        }

    private fun jsonHeaders() = headersOf("Content-Type", ContentType.Application.Json.toString())

    private fun problemHeaders() = headersOf("Content-Type", "application/problem+json")

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorChannelConnectionApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorChannelConnectionApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }

    private companion object {
        const val NOT_CONNECTED_BODY = """{"connected":false,"checkedAt":"2026-09-26T12:00:00Z"}"""
        const val CONNECT_BODY_NO_REVEAL = """{"channelCredentialId":"cc1","createdAt":"2026-09-26T12:00:00Z"}"""
    }
}
