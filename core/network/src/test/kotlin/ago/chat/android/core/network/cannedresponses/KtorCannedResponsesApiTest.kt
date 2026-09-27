package ago.chat.android.core.network.cannedresponses

import ago.chat.android.core.domain.cannedresponses.CannedResponse
import ago.chat.android.core.domain.cannedresponses.CannedResponsesResult
import ago.chat.android.core.domain.cannedresponses.CannedResponsesWriteResult
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * `26-220` (`docs/design/tenant-canned-tags-android.md` §1.3): the read, the whole-list write, and its
 * own echo round-tripping, driven through the real client configuration and a `MockEngine` — the
 * identical shape `KtorOfflineAutoReplyApiTest` already establishes for its own `{siteId}`-scoped
 * full-DTO adapter.
 *
 * The load-bearing tests here are: an empty library is `Loaded`, never a failure; `save` sends the whole
 * list in the exact order given (there is no per-item endpoint to fall back on); and a `2xx` save echoes
 * the server's own list, which is what the view model re-seeds from.
 */
class KtorCannedResponsesApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"
    private val siteId = "site-123"

    // --------------------------------------------------------- GET /api/v1/sites/{siteId}/canned-responses

    @Test
    fun `the library is read with the current site id in the path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        wireJson(listOf("Оплата" to "Оплата принимается картой и наличными.")),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetch()

            assertEquals(
                CannedResponsesResult.Loaded(
                    listOf(CannedResponse("Оплата", "Оплата принимается картой и наличными.")),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/sites/$siteId/canned-responses", requestedUrl)
        }

    @Test
    fun `an empty library is a valid loaded result, not a failure`() =
        runTest {
            val api = apiFor(siteId) { respond(wireJson(emptyList()), HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(CannedResponsesResult.Loaded(emptyList()), api.fetch())
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

            assertEquals(CannedResponsesResult.Failed(NetworkFailure.Unexpected), api.fetch())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 5xx on the read is a server error, not an empty library`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(CannedResponsesResult.Failed(NetworkFailure.ServerError(503)), api.fetch())
        }

    @Test
    fun `a dropped connection on the read is NoConnection`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(CannedResponsesResult.Failed(NetworkFailure.NoConnection), api.fetch())
        }

    @Test
    fun `a 200 that dropped the shape is Failed, never a fabricated empty library`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(CannedResponsesResult.Failed(NetworkFailure.Unexpected), api.fetch())
        }

    // --------------------------------------------------------- PUT /api/v1/sites/{siteId}/canned-responses

    @Test
    fun `save sends the whole list, even when empty`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var sentBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(wireJson(emptyList()), HttpStatusCode.OK, jsonHeaders())
                }

            api.save(emptyList())

            assertEquals(HttpMethod.Put to "$baseUrl/api/v1/sites/$siteId/canned-responses", requested)
            val sent = Json.parseToJsonElement(sentBody!!).jsonObject
            assertEquals(setOf("responses"), sent.keys)
            assertEquals(0, sent.getValue("responses").jsonArray.size)
        }

    @Test
    fun `save sends every response in the exact order given, never re-sorted`() =
        runTest {
            var sentBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        wireJson(listOf("зет" to "z", "альфа" to "a", "бета" to "b")),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            api.save(listOf(CannedResponse("зет", "z"), CannedResponse("альфа", "a"), CannedResponse("бета", "b")))

            val sentTitles =
                Json
                    .parseToJsonElement(sentBody!!)
                    .jsonObject
                    .getValue("responses")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("title")
                            .jsonPrimitive.content
                    }
            assertEquals(listOf("зет", "альфа", "бета"), sentTitles)
        }

    @Test
    fun `a 2xx save echoes the server's own list, re-seeding from that rather than the request`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(wireJson(listOf("Нормализовано" to "сервером")), HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.save(listOf(CannedResponse("исходное", "значение")))

            assertEquals(
                CannedResponsesWriteResult.Saved(listOf(CannedResponse("Нормализовано", "сервером"))),
                result,
            )
        }

    @Test
    fun `a 400 with a problem-details body is rendered as that exact refusal`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"CannedResponse.Invalid","detail":"Заголовок не может быть пустым."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            val result = api.save(listOf(CannedResponse("", "x")))

            assertEquals(CannedResponsesWriteResult.Refused("Заголовок не может быть пустым."), result)
        }

    @Test
    fun `a refusal with no problem-details body classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(CannedResponsesWriteResult.Failed(NetworkFailure.ServerError(403)), api.save(emptyList()))
        }

    @Test
    fun `a dropped connection on save is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(CannedResponsesWriteResult.Failed(NetworkFailure.NoConnection), api.save(emptyList()))
        }

    @Test
    fun `no active site selected on save is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(CannedResponsesWriteResult.Failed(NetworkFailure.Unexpected), api.save(emptyList()))
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 2xx save that dropped the shape is Failed, never a fabricated echo`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(CannedResponsesWriteResult.Failed(NetworkFailure.Unexpected), api.save(emptyList()))
        }

    // ------------------------------------------------------------------------------------------ fixtures

    private fun wireJson(responses: List<Pair<String, String>>): String {
        val responsesJson = responses.joinToString(",") { (title, body) -> """{"title":"$title","body":"$body"}""" }
        return """{"responses":[$responsesJson]}"""
    }

    private fun jsonHeaders() = headersOf("Content-Type", "application/json")

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorCannedResponsesApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorCannedResponsesApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }
}
