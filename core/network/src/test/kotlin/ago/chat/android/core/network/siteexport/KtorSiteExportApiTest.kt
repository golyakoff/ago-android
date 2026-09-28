package ago.chat.android.core.network.siteexport

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.siteexport.RequestSiteExportResult
import ago.chat.android.core.domain.siteexport.SiteExportHistoryResult
import ago.chat.android.core.domain.siteexport.SiteExportStatus
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
import java.time.Instant

/**
 * `26-251` (`ago-console`'s own `siteExportsApi.ts`): the history read (happy/empty/error/no-site/parse)
 * and the request-export write's own `202`/`detail` mapping — driven through the real client configuration
 * and a `MockEngine`, the identical shape `KtorSiteConsentDocumentsApiTest` establishes.
 *
 * `baseUrl` is the RFC 2606 reserved `.invalid` TLD, never a routable host — CLAUDE.md forbids a real
 * endpoint in any repository, test fixtures included.
 */
class KtorSiteExportApiTest {
    private val baseUrl = "https://chat-api.example.invalid"
    private val siteId = "site-123"

    // ------------------------------------------------- GET /api/v1/sites/{siteId}/exports

    @Test
    fun `the history is read with the current site id in the path, newest first`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    respond(historyJson(), HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.fetchHistory()

            assertEquals(HttpMethod.Get to "$baseUrl/api/v1/sites/$siteId/exports", requested)
            val loaded = result as SiteExportHistoryResult.Loaded
            assertEquals(listOf("exp-2", "exp-1"), loaded.items.map { it.exportId })

            val ready = loaded.items[0]
            assertEquals(SiteExportStatus.Ready, ready.status)
            assertEquals(Instant.parse("2026-01-02T10:00:00Z"), ready.requestedAt)
            assertEquals(Instant.parse("2026-01-02T10:05:00Z"), ready.completedAt)
            assertEquals("https://files.example.invalid/exports/exp-2.zip", ready.downloadUrl)
            assertEquals(Instant.parse("2026-01-09T10:05:00Z"), ready.expiresAt)

            val pending = loaded.items[1]
            assertEquals(SiteExportStatus.Pending, pending.status)
            assertEquals(null, pending.completedAt)
            assertEquals(null, pending.downloadUrl)
            assertEquals(null, pending.expiresAt)
        }

    @Test
    fun `an empty history is a valid loaded result, not a failure`() =
        runTest {
            val api = apiFor(siteId) { respond("[]", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(SiteExportHistoryResult.Loaded(emptyList()), api.fetchHistory())
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

            assertEquals(SiteExportHistoryResult.Failed(NetworkFailure.Unexpected), api.fetchHistory())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 5xx on the history read is a server error`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(SiteExportHistoryResult.Failed(NetworkFailure.ServerError(503)), api.fetchHistory())
        }

    @Test
    fun `a dropped connection on the history read is NoConnection`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(SiteExportHistoryResult.Failed(NetworkFailure.NoConnection), api.fetchHistory())
        }

    @Test
    fun `a 200 whose row carries an unknown status is Failed, never a fabricated history`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """[{"exportId":"e","status":"Frobnicated","requestedAt":"2026-01-01T00:00:00Z"}]""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            assertEquals(SiteExportHistoryResult.Failed(NetworkFailure.Unexpected), api.fetchHistory())
        }

    @Test
    fun `an unparseable timestamp on an otherwise-2xx row is Failed, not an empty read`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """[{"exportId":"e","status":"Pending","requestedAt":"not-a-date"}]""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            assertEquals(SiteExportHistoryResult.Failed(NetworkFailure.Unexpected), api.fetchHistory())
        }

    // ------------------------------------------------- POST /api/v1/sites/{siteId}/exports

    @Test
    fun `requesting an export posts to the exports path and returns the new id`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    respond("""{"exportId":"exp-9"}""", HttpStatusCode.Accepted, jsonHeaders())
                }

            val result = api.requestExport()

            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/sites/$siteId/exports", requested)
            assertEquals(RequestSiteExportResult.Requested("exp-9"), result)
        }

    @Test
    fun `a refusal with a problem-details body is rendered as that exact refusal`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Site.NotFound","detail":"Сайт не найден."}""",
                        HttpStatusCode.NotFound,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(RequestSiteExportResult.Refused("Сайт не найден."), api.requestExport())
        }

    @Test
    fun `a refusal with no problem-details body classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(RequestSiteExportResult.Failed(NetworkFailure.ServerError(403)), api.requestExport())
        }

    @Test
    fun `a dropped connection on request is a transport failure`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(RequestSiteExportResult.Failed(NetworkFailure.NoConnection), api.requestExport())
        }

    @Test
    fun `no active site selected on request is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(RequestSiteExportResult.Failed(NetworkFailure.Unexpected), api.requestExport())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 2xx request that dropped the id is Failed, never a fabricated echo`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElse":true}""", HttpStatusCode.Accepted, jsonHeaders()) }

            assertEquals(RequestSiteExportResult.Failed(NetworkFailure.Unexpected), api.requestExport())
        }

    // ------------------------------------------------------------------------------------ fixtures

    private fun historyJson(): String =
        """[
            {"exportId":"exp-2","status":"Ready","requestedAt":"2026-01-02T10:00:00Z",
             "completedAt":"2026-01-02T10:05:00Z","downloadUrl":"https://files.example.invalid/exports/exp-2.zip",
             "expiresAt":"2026-01-09T10:05:00Z"},
            {"exportId":"exp-1","status":"Pending","requestedAt":"2026-01-01T09:00:00Z"}
        ]"""

    private fun jsonHeaders() = headersOf("Content-Type", "application/json")

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorSiteExportApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorSiteExportApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }
}
