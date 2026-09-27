package ago.chat.android.core.network.modules

import ago.chat.android.core.domain.modules.EnabledModule
import ago.chat.android.core.domain.modules.ModulesResult
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
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * `26-199`/`M1`: the site's enabled-module read, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorSiteTagsApiTest`/`KtorInstallationApiTest` already establish
 * for a `{siteId}`-scoped `Ago.Chat.Api` read.
 */
class KtorModulesApiTest {
    private val baseUrl = "https://api.example.invalid"
    private val siteId = "site-123"

    @Test
    fun `the enabled modules are read with the current site id in the path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """
                        {"modules":[
                            {"moduleKey":"faq","triggerWords":["помощь","faq"],"entryPoint":"https://faq.example/entry",
                             "grantedByOwner":true,"expiresAt":"2027-01-01T00:00:00Z"}
                        ]}
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val loaded = api.fetch() as ModulesResult.Loaded

            assertEquals(
                listOf(
                    EnabledModule(
                        moduleKey = "faq",
                        triggerWords = listOf("помощь", "faq"),
                        entryPoint = "https://faq.example/entry",
                        grantedByOwner = true,
                        expiresAt = Instant.parse("2027-01-01T00:00:00Z"),
                    ),
                ),
                loaded.modules,
            )
            assertEquals("$baseUrl/api/v1/sites/$siteId/modules", requestedUrl)
        }

    @Test
    fun `an empty module list is a valid loaded result, not a failure`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"modules":[]}""", HttpStatusCode.OK, jsonHeaders) }

            assertEquals(ModulesResult.Loaded(emptyList()), api.fetch())
        }

    @Test
    fun `omitted grantedByOwner and expiresAt default to false and null, not a parse failure`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"modules":[{"moduleKey":"faq","triggerWords":[],"entryPoint":"https://faq.example"}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val loaded = api.fetch() as ModulesResult.Loaded

            assertEquals(
                EnabledModule(
                    moduleKey = "faq",
                    triggerWords = emptyList(),
                    entryPoint = "https://faq.example",
                    grantedByOwner = false,
                    expiresAt = null,
                ),
                loaded.modules.single(),
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

            assertEquals(ModulesResult.Failed(NetworkFailure.Unexpected), api.fetch())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 5xx is a classified ServerError, not an empty module list`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(ModulesResult.Failed(NetworkFailure.ServerError(503)), api.fetch())
        }

    @Test
    fun `a dropped connection is NoConnection, not an empty module list`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(ModulesResult.Failed(NetworkFailure.NoConnection), api.fetch())
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never an empty module list`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders) }

            assertEquals(ModulesResult.Failed(NetworkFailure.Unexpected), api.fetch())
        }

    @Test
    fun `an unparseable expiresAt fails the whole read, never a guessed non-expiring module`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"modules":[{"moduleKey":"faq","triggerWords":[],"entryPoint":"https://faq.example","expiresAt":"not-a-date"}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            assertEquals(ModulesResult.Failed(NetworkFailure.Unexpected), api.fetch())
        }

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorModulesApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorModulesApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }

    private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())
}
