package ago.chat.android.core.network.branding

import ago.chat.android.core.domain.branding.BrandingWriteResult
import ago.chat.android.core.domain.branding.LogoStatus
import ago.chat.android.core.domain.branding.LogoUploadResult
import ago.chat.android.core.domain.branding.SiteBranding
import ago.chat.android.core.domain.branding.SiteBrandingResult
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

/**
 * `26-191`/`C4`: the branding read plus the two independent writes (company name, logo), driven
 * through the real client configuration and a `MockEngine` — the identical shape
 * `KtorChannelConnectionApiTest` already establishes for a `{siteId}`-scoped `Ago.Chat.Api` read plus a
 * problem-details write.
 */
class KtorSiteBrandingApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"
    private val siteId = "site-123"

    // --------------------------------------------------------- GET /branding

    @Test
    fun `branding is read from the current site's own path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """{"brandCompanyName":"Cool Shop","logoUrl":"https://cdn.example/logo.png",
                            |"logoStatus":"Ready","logoRejectionReason":null}
                        """.trimMargin(),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetch()

            assertEquals(
                SiteBrandingResult.Loaded(
                    SiteBranding(
                        brandCompanyName = "Cool Shop",
                        logoUrl = "https://cdn.example/logo.png",
                        logoStatus = LogoStatus.Ready,
                        logoRejectionReason = null,
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/sites/$siteId/branding", requestedUrl)
        }

    @Test
    fun `an unset branding decodes with none of the optional fields present`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"logoStatus":"None"}""", HttpStatusCode.OK, jsonHeaders()) }

            val result = api.fetch() as SiteBrandingResult.Loaded

            assertEquals(null, result.branding.brandCompanyName)
            assertEquals(null, result.branding.logoUrl)
            assertEquals(LogoStatus.None, result.branding.logoStatus)
        }

    @Test
    fun `an unrecognised logoStatus falls back to None rather than failing decode`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"logoStatus":"SomeFutureStatus"}""", HttpStatusCode.OK, jsonHeaders()) }

            val result = api.fetch() as SiteBrandingResult.Loaded

            assertEquals(LogoStatus.None, result.branding.logoStatus)
        }

    @Test
    fun `a rejected logo carries its own reason`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"logoStatus":"Rejected","logoRejectionReason":"Анимированные изображения не поддерживаются."}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetch() as SiteBrandingResult.Loaded

            assertEquals(LogoStatus.Rejected, result.branding.logoStatus)
            assertEquals("Анимированные изображения не поддерживаются.", result.branding.logoRejectionReason)
        }

    @Test
    fun `no active site selected on fetch is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(SiteBrandingResult.Failed(NetworkFailure.Unexpected), api.fetch())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 5xx on fetch is a server error`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(SiteBrandingResult.Failed(NetworkFailure.ServerError(503)), api.fetch())
        }

    @Test
    fun `a dropped connection on fetch is NoConnection`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(SiteBrandingResult.Failed(NetworkFailure.NoConnection), api.fetch())
        }

    // --------------------------------------------------------- PUT /branding

    @Test
    fun `updateCompanyName sends the name as the whole json body`() =
        runTest {
            var requestedUrl: String? = null
            var requestedBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    requestedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond("""{"brandCompanyName":"Cool Shop"}""", HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.updateCompanyName("Cool Shop")

            assertEquals(BrandingWriteResult.Saved("Cool Shop"), result)
            assertEquals("$baseUrl/api/v1/sites/$siteId/branding", requestedUrl)
            assertEquals("""{"brandCompanyName":"Cool Shop"}""", requestedBody)
        }

    @Test
    fun `updateCompanyName sends a null name as a real json null, not omitted`() =
        runTest {
            var requestedBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond("""{"brandCompanyName":null}""", HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.updateCompanyName(null)

            assertEquals(BrandingWriteResult.Saved(null), result)
            assertEquals("""{"brandCompanyName":null}""", requestedBody)
        }

    @Test
    fun `an invalid company name refusal is rendered verbatim`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Branding.InvalidCompanyName","detail":"Название компании слишком длинное."}""",
                        HttpStatusCode.BadRequest,
                        problemHeaders(),
                    )
                }

            assertEquals(BrandingWriteResult.Refused("Название компании слишком длинное."), api.updateCompanyName("x".repeat(500)))
        }

    @Test
    fun `a company name refusal with no problem-details body classifies as a server error`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(BrandingWriteResult.Failed(NetworkFailure.ServerError(403)), api.updateCompanyName("x"))
        }

    @Test
    fun `no active site selected on updateCompanyName is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(BrandingWriteResult.Failed(NetworkFailure.Unexpected), api.updateCompanyName("x"))
            assertEquals(0, calls)
        }

    // --------------------------------------------------------- POST /branding/logo

    @Test
    fun `uploadLogo posts the raw bytes with the given content type, to the logo sub-path`() =
        runTest {
            var requestedUrl: String? = null
            var requestedContentType: String? = null
            var requestedBytes: ByteArray? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    requestedContentType = request.body.contentType.toString()
                    requestedBytes = (request.body as OutgoingContent.ByteArrayContent).bytes()
                    respond("""{"logoStatus":"Pending"}""", HttpStatusCode.OK, jsonHeaders())
                }

            val bytes = byteArrayOf(1, 2, 3, 4)
            val result = api.uploadLogo(bytes, "image/png")

            assertEquals(LogoUploadResult.Accepted(LogoStatus.Pending), result)
            assertEquals("$baseUrl/api/v1/sites/$siteId/branding/logo", requestedUrl)
            assertEquals(ContentType.Image.PNG.toString(), requestedContentType)
            assertEquals(listOf<Byte>(1, 2, 3, 4), requestedBytes?.toList())
        }

    @Test
    fun `a rate-limited upload refusal is rendered verbatim`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Branding.LogoUploadRateLimited","detail":"Слишком много загрузок логотипа сегодня."}""",
                        HttpStatusCode.TooManyRequests,
                        problemHeaders(),
                    )
                }

            assertEquals(
                LogoUploadResult.Refused("Слишком много загрузок логотипа сегодня."),
                api.uploadLogo(byteArrayOf(1), "image/png"),
            )
        }

    @Test
    fun `an upload refusal with no problem-details body classifies as a server error`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(LogoUploadResult.Failed(NetworkFailure.ServerError(403)), api.uploadLogo(byteArrayOf(1), "image/png"))
        }

    @Test
    fun `a dropped connection on upload is a transport failure`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(LogoUploadResult.Failed(NetworkFailure.NoConnection), api.uploadLogo(byteArrayOf(1), "image/png"))
        }

    @Test
    fun `no active site selected on uploadLogo is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(LogoUploadResult.Failed(NetworkFailure.Unexpected), api.uploadLogo(byteArrayOf(1), "image/png"))
            assertEquals(0, calls)
        }

    private fun jsonHeaders() = headersOf("Content-Type", ContentType.Application.Json.toString())

    private fun problemHeaders() = headersOf("Content-Type", "application/problem+json")

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorSiteBrandingApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorSiteBrandingApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }
}
