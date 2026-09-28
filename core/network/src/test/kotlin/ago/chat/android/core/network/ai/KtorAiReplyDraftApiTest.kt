package ago.chat.android.core.network.ai

import ago.chat.android.core.domain.ai.AiReplyDraftStatus
import ago.chat.android.core.domain.ai.AiReplyDraftStatusResult
import ago.chat.android.core.domain.ai.AiReplyDraftWriteResult
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
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * `26-246`: the read and the two toggle routes, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorOfflineAutoReplyApiTest` establishes for its own
 * `{siteId}`-scoped adapter.
 *
 * The load-bearing cases here are: the two legal pairs the wire carries (`acceptedVersion`, `declaredAt`)
 * collapse to the booleans this door reports, unknown wire fields are ignored rather than failing the
 * decode, and `enable`/`disable` hit distinct routes.
 *
 * `example.invalid` is used as the base URL, never a real deployment host (this repository is public).
 */
class KtorAiReplyDraftApiTest {
    private val baseUrl = "https://chat-api.example.invalid"
    private val siteId = "site-123"

    // ---------------------------------------------------------------- GET /api/v1/sites/{siteId}/ai-add-on

    @Test
    fun `the status is read with the current site id in the path, legal pairs collapsed to booleans`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """{"purchased":true,"enabled":true,"effectiveFrom":"2026-09-28T10:00:00Z",""" +
                            """"acceptedVersion":"v3","acceptedAt":"2026-09-01T00:00:00Z",""" +
                            """"declaredBy":"op-1","declaredAt":"2026-09-02T00:00:00Z"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetch()

            assertEquals(
                AiReplyDraftStatusResult.Loaded(
                    AiReplyDraftStatus(
                        purchased = true,
                        enabled = true,
                        effectiveFrom = "2026-09-28T10:00:00Z",
                        agreementAccepted = true,
                        basisDeclared = true,
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/sites/$siteId/ai-add-on", requestedUrl)
        }

    @Test
    fun `unknown wire fields are ignored, and null legal fields read as not-yet-made`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"purchased":true,"enabled":false,"effectiveFrom":null,"acceptedVersion":null,""" +
                            """"declaredAt":null,"documentKey":"ai-terms","currentVersion":"v3","currentTitle":"Terms"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetch()

            assertEquals(
                AiReplyDraftStatusResult.Loaded(
                    AiReplyDraftStatus(
                        purchased = true,
                        enabled = false,
                        effectiveFrom = null,
                        agreementAccepted = false,
                        basisDeclared = false,
                    ),
                ),
                result,
            )
        }

    @Test
    fun `a not-purchased account reads as purchased false, not a failure`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"purchased":false,"enabled":false}""", HttpStatusCode.OK, jsonHeaders()) }

            val result = api.fetch()

            assertEquals(
                AiReplyDraftStatusResult.Loaded(
                    AiReplyDraftStatus(
                        purchased = false,
                        enabled = false,
                        effectiveFrom = null,
                        agreementAccepted = false,
                        basisDeclared = false,
                    ),
                ),
                result,
            )
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

            assertEquals(AiReplyDraftStatusResult.Failed(NetworkFailure.Unexpected), api.fetch())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 5xx on the read is a server error, not a fabricated status`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(AiReplyDraftStatusResult.Failed(NetworkFailure.ServerError(503)), api.fetch())
        }

    @Test
    fun `a dropped connection on the read is NoConnection`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(AiReplyDraftStatusResult.Failed(NetworkFailure.NoConnection), api.fetch())
        }

    @Test
    fun `a 200 that dropped the shape is Failed, never a fabricated status`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(AiReplyDraftStatusResult.Failed(NetworkFailure.Unexpected), api.fetch())
        }

    // ------------------------------------------------- POST /api/v1/sites/{siteId}/ai-add-on/{enable|disable}

    @Test
    fun `setEnabled true posts to the enable route`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    respond("", HttpStatusCode.NoContent)
                }

            val result = api.setEnabled(true)

            assertEquals(AiReplyDraftWriteResult.Saved, result)
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/sites/$siteId/ai-add-on/enable", requested)
        }

    @Test
    fun `setEnabled false posts to the disable route`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond("", HttpStatusCode.NoContent)
                }

            val result = api.setEnabled(false)

            assertEquals(AiReplyDraftWriteResult.Saved, result)
            assertEquals("$baseUrl/api/v1/sites/$siteId/ai-add-on/disable", requestedUrl)
        }

    @Test
    fun `a refusal with a problem-details body is rendered as that exact refusal`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"AiAddOn.AgreementNotAccepted","detail":"Сначала примите соглашение об ИИ-функциях."}""",
                        HttpStatusCode.Conflict,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            val result = api.setEnabled(true)

            assertEquals(AiReplyDraftWriteResult.Refused("Сначала примите соглашение об ИИ-функциях."), result)
        }

    @Test
    fun `a refusal with no problem-details body classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(AiReplyDraftWriteResult.Failed(NetworkFailure.ServerError(403)), api.setEnabled(true))
        }

    @Test
    fun `a dropped connection on the toggle is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(AiReplyDraftWriteResult.Failed(NetworkFailure.NoConnection), api.setEnabled(true))
        }

    @Test
    fun `no active site selected on the toggle is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(AiReplyDraftWriteResult.Failed(NetworkFailure.Unexpected), api.setEnabled(false))
            assertEquals("no active site must never reach the network", 0, calls)
        }

    // ------------------------------------------------------------------------------------------ fixtures

    private fun jsonHeaders() = headersOf("Content-Type", "application/json")

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorAiReplyDraftApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorAiReplyDraftApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }
}
