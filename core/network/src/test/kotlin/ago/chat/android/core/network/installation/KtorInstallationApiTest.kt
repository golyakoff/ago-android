package ago.chat.android.core.network.installation

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.installation.SiteInstallation
import ago.chat.android.core.domain.installation.SiteInstallationResult
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
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * `26-159`: the chat-widget installation read, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorConversationTagsApiTest`/`KtorSiteAnalyticsApiTest` already
 * establish for a `{siteId}`-scoped `Ago.Chat.Api` read.
 */
class KtorInstallationApiTest {
    private val baseUrl = "https://api.example.invalid"
    private val siteId = "site-123"

    @Test
    fun `the installation is read with the current site id in the path, carrying the key and origins`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """{"publicKey":"pk_abc","allowedOrigins":["https://shop.example","https://www.shop.example"]}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val loaded = api.fetchInstallation() as SiteInstallationResult.Loaded

            assertEquals(
                SiteInstallation(publicKey = "pk_abc", allowedOrigins = listOf("https://shop.example", "https://www.shop.example")),
                loaded.installation,
            )
            assertEquals("$baseUrl/api/v1/sites/$siteId/installation", requestedUrl)
        }

    @Test
    fun `an omitted origins array is an empty list, never a parse failure`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"publicKey":"pk_abc"}""", HttpStatusCode.OK, jsonHeaders) }

            val loaded = api.fetchInstallation() as SiteInstallationResult.Loaded

            assertEquals(SiteInstallation(publicKey = "pk_abc", allowedOrigins = emptyList()), loaded.installation)
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

            assertEquals(SiteInstallationResult.Failed(BookingsQueueFailure.Unexpected), api.fetchInstallation())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 5xx is Unexpected, not an empty installation`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(SiteInstallationResult.Failed(BookingsQueueFailure.Unexpected), api.fetchInstallation())
        }

    @Test
    fun `a dropped connection is Transport, not an empty installation`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(SiteInstallationResult.Failed(BookingsQueueFailure.Transport), api.fetchInstallation())
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never an empty installation`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders) }

            assertEquals(SiteInstallationResult.Failed(BookingsQueueFailure.Unexpected), api.fetchInstallation())
        }

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorInstallationApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorInstallationApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }

    private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())
}
