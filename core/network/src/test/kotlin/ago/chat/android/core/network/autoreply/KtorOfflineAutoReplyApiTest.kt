package ago.chat.android.core.network.autoreply

import ago.chat.android.core.domain.autoreply.AutoReplyRule
import ago.chat.android.core.domain.autoreply.OfflineAutoReply
import ago.chat.android.core.domain.autoreply.OfflineAutoReplyResult
import ago.chat.android.core.domain.autoreply.OfflineAutoReplyWriteResult
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.network.InMemoryActiveSite
import ago.chat.android.core.network.MutableAccessTokenProvider
import ago.chat.android.core.network.installAgoRestDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * `26-192`/`C5`: the read, the write, and rule-order round-tripping, driven through the real client
 * configuration and a `MockEngine` — the identical shape `KtorWidgetConfigApiTest` already establishes
 * for its own `{siteId}`-scoped full-DTO adapter.
 *
 * The load-bearing test in this file is `update sends the rules in the exact order given, never
 * re-sorted` — the server matches keyword rules first-rule-wins, so a save that silently reordered rules
 * would change behaviour nobody asked for.
 */
class KtorOfflineAutoReplyApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"
    private val siteId = "site-123"

    // ---------------------------------------------------------- GET /api/v1/sites/{siteId}/offline-auto-reply

    @Test
    fun `the settings are read with the current site id in the path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        wireJson(
                            enabled = true,
                            fallbackReply = "Мы вернёмся утром.",
                            rules =
                                listOf(
                                    "возврат" to "Возвраты занимают три рабочих дня.",
                                ),
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetch()

            assertEquals(
                OfflineAutoReplyResult.Loaded(
                    OfflineAutoReply(
                        enabled = true,
                        fallbackReply = "Мы вернёмся утром.",
                        rules = listOf(AutoReplyRule("возврат", "Возвраты занимают три рабочих дня.")),
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/sites/$siteId/offline-auto-reply", requestedUrl)
        }

    @Test
    fun `an empty rule list is a valid loaded settings, not a failure`() =
        runTest {
            val api =
                apiFor(
                    siteId,
                ) { respond(wireJson(enabled = false, fallbackReply = "", rules = emptyList()), HttpStatusCode.OK, jsonHeaders()) }

            val result = api.fetch()

            assertEquals(OfflineAutoReplyResult.Loaded(OfflineAutoReply(enabled = false, fallbackReply = "", rules = emptyList())), result)
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

            assertEquals(OfflineAutoReplyResult.Failed(NetworkFailure.Unexpected), api.fetch())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 5xx on the read is a server error, not empty settings`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(OfflineAutoReplyResult.Failed(NetworkFailure.ServerError(503)), api.fetch())
        }

    @Test
    fun `a dropped connection on the read is NoConnection`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(OfflineAutoReplyResult.Failed(NetworkFailure.NoConnection), api.fetch())
        }

    @Test
    fun `a 200 that dropped the shape is Failed, never a fabricated disabled default`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(OfflineAutoReplyResult.Failed(NetworkFailure.Unexpected), api.fetch())
        }

    // ---------------------------------------------------------- PUT /api/v1/sites/{siteId}/offline-auto-reply

    @Test
    fun `update sends enabled, fallbackReply and rules, even when disabled and empty`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var sentBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(wireJson(enabled = false, fallbackReply = "", rules = emptyList()), HttpStatusCode.OK, jsonHeaders())
                }

            api.update(OfflineAutoReply(enabled = false, fallbackReply = "", rules = emptyList()))

            assertEquals(HttpMethod.Put to "$baseUrl/api/v1/sites/$siteId/offline-auto-reply", requested)
            val sent = Json.parseToJsonElement(sentBody!!).jsonObject
            assertEquals(setOf("enabled", "fallbackReply", "rules"), sent.keys)
            assertEquals(false, sent.getValue("enabled").jsonPrimitive.boolean)
            assertEquals(0, sent.getValue("rules").jsonArray.size)
        }

    @Test
    fun `update sends the rules in the exact order given, never re-sorted`() =
        runTest {
            var sentBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        wireJson(
                            enabled = true,
                            fallbackReply = "По умолчанию",
                            rules = listOf("зет" to "z", "альфа" to "a", "бета" to "b"),
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            api.update(
                OfflineAutoReply(
                    enabled = true,
                    fallbackReply = "По умолчанию",
                    rules = listOf(AutoReplyRule("зет", "z"), AutoReplyRule("альфа", "a"), AutoReplyRule("бета", "b")),
                ),
            )

            val sentKeywords =
                Json
                    .parseToJsonElement(sentBody!!)
                    .jsonObject
                    .getValue("rules")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("keyword")
                            .jsonPrimitive.content
                    }
            assertEquals(listOf("зет", "альфа", "бета"), sentKeywords)
        }

    @Test
    fun `a 2xx save echoes the server's own settings, re-seeding from that rather than the request`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        wireJson(enabled = true, fallbackReply = "Нормализовано сервером", rules = emptyList()),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.update(OfflineAutoReply(enabled = true, fallbackReply = "исходное", rules = emptyList()))

            assertEquals(
                OfflineAutoReplyWriteResult.Saved(
                    OfflineAutoReply(enabled = true, fallbackReply = "Нормализовано сервером", rules = emptyList()),
                ),
                result,
            )
        }

    @Test
    fun `a 400 with a problem-details body is rendered as that exact refusal, draft implied to stay`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"OfflineAutoReply.Invalid","detail":"Правило нуждается в ключевом слове и ответе."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            val result = api.update(OfflineAutoReply(enabled = true, fallbackReply = "x", rules = emptyList()))

            assertEquals(OfflineAutoReplyWriteResult.Refused("Правило нуждается в ключевом слове и ответе."), result)
        }

    @Test
    fun `a refusal with no problem-details body classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(
                OfflineAutoReplyWriteResult.Failed(NetworkFailure.ServerError(403)),
                api.update(OfflineAutoReply(enabled = false, fallbackReply = "", rules = emptyList())),
            )
        }

    @Test
    fun `a dropped connection on update is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(
                OfflineAutoReplyWriteResult.Failed(NetworkFailure.NoConnection),
                api.update(OfflineAutoReply(enabled = false, fallbackReply = "", rules = emptyList())),
            )
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

            assertEquals(
                OfflineAutoReplyWriteResult.Failed(NetworkFailure.Unexpected),
                api.update(OfflineAutoReply(enabled = false, fallbackReply = "", rules = emptyList())),
            )
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 2xx update that dropped the shape is Failed, never a fabricated echo`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(
                OfflineAutoReplyWriteResult.Failed(NetworkFailure.Unexpected),
                api.update(OfflineAutoReply(enabled = false, fallbackReply = "", rules = emptyList())),
            )
        }

    // ------------------------------------------------------------------------------------------ fixtures

    private fun wireJson(
        enabled: Boolean,
        fallbackReply: String,
        rules: List<Pair<String, String>>,
    ): String {
        val rulesJson = rules.joinToString(",") { (keyword, reply) -> """{"keyword":"$keyword","reply":"$reply"}""" }
        return """{"enabled":$enabled,"fallbackReply":"$fallbackReply","rules":[$rulesJson]}"""
    }

    private fun jsonHeaders() = headersOf("Content-Type", "application/json")

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorOfflineAutoReplyApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorOfflineAutoReplyApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }
}
