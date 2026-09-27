package ago.chat.android.core.network.consent

import ago.chat.android.core.domain.consent.ConsentAcceptance
import ago.chat.android.core.domain.consent.ConsentAcceptancesResult
import ago.chat.android.core.domain.consent.ConsentDocumentSummary
import ago.chat.android.core.domain.consent.ConsentOverview
import ago.chat.android.core.domain.consent.ConsentPublishResult
import ago.chat.android.core.domain.consent.ConsentPurpose
import ago.chat.android.core.domain.consent.ConsentVersion
import ago.chat.android.core.domain.consent.SiteConsentDocumentsResult
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * `26-226` (`docs/design/tenant-consent-android.md` §2.3): the overview read, the whole-kind
 * acceptances read (with the client-side per-version filter left to the caller), and the publish
 * write's own `200`/`409`/`detail` mapping — driven through the real client configuration and a
 * `MockEngine`, the identical shape `KtorCannedResponsesApiTest` already establishes.
 */
class KtorSiteConsentDocumentsApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"
    private val siteId = "site-123"

    // --------------------------------------------------------- GET /api/v1/sites/{siteId}/consent-documents

    @Test
    fun `the overview is read with the current site id in the path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(overviewJson(), HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.fetchOverview()

            assertEquals(
                SiteConsentDocumentsResult.Loaded(
                    ConsentOverview(
                        contact =
                            ConsentDocumentSummary(
                                purpose = ConsentPurpose.Contact,
                                documentKey = "contact-key",
                                versions =
                                    listOf(
                                        ConsentVersion(
                                            version = "v2",
                                            sequence = 2,
                                            title = "Согласие на контакты",
                                            publishedAt = Instant.parse("2026-01-02T10:00:00Z"),
                                        ),
                                    ),
                            ),
                        contactConsentRequired = true,
                        marketing =
                            ConsentDocumentSummary(
                                purpose = ConsentPurpose.Marketing,
                                documentKey = "marketing-key",
                                versions = emptyList(),
                            ),
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/sites/$siteId/consent-documents", requestedUrl)
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

            assertEquals(SiteConsentDocumentsResult.Failed(NetworkFailure.Unexpected), api.fetchOverview())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 5xx on the overview read is a server error`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(SiteConsentDocumentsResult.Failed(NetworkFailure.ServerError(503)), api.fetchOverview())
        }

    @Test
    fun `a dropped connection on the overview read is NoConnection`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(SiteConsentDocumentsResult.Failed(NetworkFailure.NoConnection), api.fetchOverview())
        }

    @Test
    fun `a 200 that dropped the shape is Failed, never a fabricated overview`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(SiteConsentDocumentsResult.Failed(NetworkFailure.Unexpected), api.fetchOverview())
        }

    @Test
    fun `an unparseable publishedAt on an otherwise-2xx body is Failed, not an empty read`() =
        runTest {
            val malformed =
                """{"contact":{"purpose":"Contact","documentKey":"k","versions":[
                    {"version":"v1","sequence":1,"title":"t","publishedAt":"not-a-date"}
                ]},"contactConsentRequired":false,"marketing":{"purpose":"Marketing","documentKey":"m","versions":[]}}"""
            val api = apiFor(siteId) { respond(malformed, HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(SiteConsentDocumentsResult.Failed(NetworkFailure.Unexpected), api.fetchOverview())
        }

    // ------------------------------------ GET /api/v1/sites/{siteId}/consent-documents/{purpose}/acceptances

    @Test
    fun `acceptances are read with the purpose slug in the path, whole kind, unfiltered`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """[{"subjectKind":"Visitor","subjectId":"a0f3c952-0000-0000-0000-000000000000",
                            "documentVersion":"v1","acceptedAt":"2026-01-01T12:00:00Z"},
                           {"subjectKind":"Visitor","subjectId":"b1f3c952-0000-0000-0000-000000000000",
                            "documentVersion":"v2","acceptedAt":"2026-01-02T12:00:00Z"}]""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetchAcceptances(ConsentPurpose.Marketing)

            assertEquals("$baseUrl/api/v1/sites/$siteId/consent-documents/Marketing/acceptances", requestedUrl)
            assertEquals(
                ConsentAcceptancesResult.Loaded(
                    listOf(
                        ConsentAcceptance("Visitor", "a0f3c952-0000-0000-0000-000000000000", "v1", Instant.parse("2026-01-01T12:00:00Z")),
                        ConsentAcceptance("Visitor", "b1f3c952-0000-0000-0000-000000000000", "v2", Instant.parse("2026-01-02T12:00:00Z")),
                    ),
                ),
                result,
            )
        }

    @Test
    fun `an empty acceptances list is a valid loaded result, not a failure`() =
        runTest {
            val api = apiFor(siteId) { respond("[]", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(ConsentAcceptancesResult.Loaded(emptyList()), api.fetchAcceptances(ConsentPurpose.Contact))
        }

    @Test
    fun `a 5xx on the acceptances read is a server error`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(ConsentAcceptancesResult.Failed(NetworkFailure.ServerError(403)), api.fetchAcceptances(ConsentPurpose.Contact))
        }

    // ------------------------------------------- POST /api/v1/sites/{siteId}/consent-documents/{purpose}

    @Test
    fun `publish posts title and body to the purpose slug's own path`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var sentBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(publishResponseJson(), HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.publish(ConsentPurpose.Contact, "Заголовок", "Текст документа")

            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/sites/$siteId/consent-documents/Contact", requested)
            val sent = Json.parseToJsonElement(sentBody!!).jsonObject
            assertEquals("Заголовок", sent.getValue("title").jsonPrimitive.content)
            assertEquals("Текст документа", sent.getValue("body").jsonPrimitive.content)
            assertEquals(
                ConsentPublishResult.Published(
                    ConsentVersion("v3", 3, "Заголовок", Instant.parse("2026-01-03T00:00:00Z")),
                ),
                result,
            )
        }

    @Test
    fun `a 409 is a Conflict, not a generic refusal`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Conflict) }

            assertEquals(ConsentPublishResult.Conflict, api.publish(ConsentPurpose.Contact, "t", "b"))
        }

    @Test
    fun `a 400 with a problem-details body is rendered as that exact refusal`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Document.Invalid","detail":"Заголовок не может быть пустым."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            val result = api.publish(ConsentPurpose.Contact, "", "b")

            assertEquals(ConsentPublishResult.Refused("Заголовок не может быть пустым."), result)
        }

    @Test
    fun `a refusal with no problem-details body classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(ConsentPublishResult.Failed(NetworkFailure.ServerError(403)), api.publish(ConsentPurpose.Contact, "t", "b"))
        }

    @Test
    fun `a dropped connection on publish is a transport failure`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(ConsentPublishResult.Failed(NetworkFailure.NoConnection), api.publish(ConsentPurpose.Contact, "t", "b"))
        }

    @Test
    fun `no active site selected on publish is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(ConsentPublishResult.Failed(NetworkFailure.Unexpected), api.publish(ConsentPurpose.Contact, "t", "b"))
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 2xx publish that dropped the shape is Failed, never a fabricated echo`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(ConsentPublishResult.Failed(NetworkFailure.Unexpected), api.publish(ConsentPurpose.Contact, "t", "b"))
        }

    // ------------------------------------------------------------------------------------------ fixtures

    private fun overviewJson(): String =
        """{"contact":{"purpose":"Contact","documentKey":"contact-key","versions":[
            {"version":"v2","sequence":2,"title":"Согласие на контакты","publishedAt":"2026-01-02T10:00:00Z"}
        ]},"contactConsentRequired":true,"marketing":{"purpose":"Marketing","documentKey":"marketing-key","versions":[]}}"""

    private fun publishResponseJson(): String =
        """{"documentKey":"contact-key","version":"v3","sequence":3,"title":"Заголовок","body":"Текст документа",
            "publishedAt":"2026-01-03T00:00:00Z"}"""

    private fun jsonHeaders() = headersOf("Content-Type", "application/json")

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorSiteConsentDocumentsApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorSiteConsentDocumentsApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }
}
