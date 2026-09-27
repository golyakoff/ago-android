package ago.chat.android.core.network.documents

import ago.chat.android.core.domain.documents.PublishedDocument
import ago.chat.android.core.domain.documents.PublishedDocumentResult
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.network.InMemoryActiveSite
import ago.chat.android.core.network.MutableAccessTokenProvider
import ago.chat.android.core.network.installAgoRestDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * `26-228` (`docs/design/tenant-consent-android.md` §2.3/§5.2's own Done-when for this slice): the
 * anonymous body-read adapter — current-vs-version route selection, `404 → NotFound`, and the same
 * transport/shape-failure classification every other adapter in this app gets, driven through the real
 * client configuration and a `MockEngine`, the identical shape `KtorSiteConsentDocumentsApiTest` already
 * establishes for its own sibling port.
 */
class KtorPublishedDocumentApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"
    private val documentKey = "site-123-contact"

    @Test
    fun `no version reads the current-version route`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                api { request ->
                    requestedUrl = request.url.toString()
                    respond(documentJson(version = "v2"), HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.fetchDocument(documentKey, version = null)

            assertEquals("$baseUrl/api/v1/documents/$documentKey", requestedUrl)
            assertEquals(
                PublishedDocumentResult.Loaded(
                    PublishedDocument(
                        documentKey = documentKey,
                        version = "v2",
                        title = "Согласие на контакты",
                        body = "Полный текст документа.",
                        publishedAt = Instant.parse("2026-01-02T10:00:00Z"),
                    ),
                ),
                result,
            )
        }

    @Test
    fun `a version reads the specific-version route`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                api { request ->
                    requestedUrl = request.url.toString()
                    respond(documentJson(version = "v1"), HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.fetchDocument(documentKey, version = "v1")

            assertEquals("$baseUrl/api/v1/documents/$documentKey/versions/v1", requestedUrl)
            assertEquals("v1", (result as PublishedDocumentResult.Loaded).document.version)
        }

    @Test
    fun `a 404 is NotFound, not a generic server error`() =
        runTest {
            val api = api { respondError(HttpStatusCode.NotFound) }

            assertEquals(PublishedDocumentResult.NotFound, api.fetchDocument(documentKey, version = "v9"))
        }

    @Test
    fun `a 429 rate limit is a server error, no Retry-After timer honoured`() =
        runTest {
            val api = api { respondError(HttpStatusCode.TooManyRequests) }

            assertEquals(PublishedDocumentResult.Failed(NetworkFailure.ServerError(429)), api.fetchDocument(documentKey, version = null))
        }

    @Test
    fun `a 5xx is a server error`() =
        runTest {
            val api = api { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(PublishedDocumentResult.Failed(NetworkFailure.ServerError(503)), api.fetchDocument(documentKey, version = null))
        }

    @Test
    fun `a dropped connection is NoConnection`() =
        runTest {
            val api = api { throw IOException("unexpected end of stream") }

            assertEquals(PublishedDocumentResult.Failed(NetworkFailure.NoConnection), api.fetchDocument(documentKey, version = null))
        }

    @Test
    fun `a 200 that dropped the shape is Failed, never a fabricated document`() =
        runTest {
            val api = api { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(PublishedDocumentResult.Failed(NetworkFailure.Unexpected), api.fetchDocument(documentKey, version = null))
        }

    @Test
    fun `an unparseable publishedAt on an otherwise-2xx body is Failed, not an empty read`() =
        runTest {
            val malformed =
                """{"documentKey":"$documentKey","version":"v1","sequence":1,"title":"t","body":"b","publishedAt":"not-a-date"}"""
            val api = api { respond(malformed, HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(PublishedDocumentResult.Failed(NetworkFailure.Unexpected), api.fetchDocument(documentKey, version = null))
        }

    // ------------------------------------------------------------------------------------------ fixtures

    private fun documentJson(version: String): String =
        """{"documentKey":"$documentKey","version":"$version","sequence":2,"title":"Согласие на контакты",
            "body":"Полный текст документа.","publishedAt":"2026-01-02T10:00:00Z"}"""

    private fun jsonHeaders() = headersOf("Content-Type", "application/json")

    private fun api(handler: MockRequestHandler): KtorPublishedDocumentApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(null),
                )
            }
        return KtorPublishedDocumentApi(client, baseUrl)
    }
}
