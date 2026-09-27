package ago.chat.android.core.network.tags

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.tags.Tag
import ago.chat.android.core.domain.tags.TagDeleteResult
import ago.chat.android.core.domain.tags.TagMutationResult
import ago.chat.android.core.domain.tags.TagVocabularyResult
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

/**
 * `26-225` (`docs/design/tenant-canned-tags-android.md` §2.3): the vocabulary read and the three per-row
 * writes, driven through the real client configuration and a `MockEngine` — the identical shape
 * `KtorCannedResponsesApiTest`/`KtorConversationTagsApiTest` already establish for their own
 * `{siteId}`-scoped adapters.
 *
 * The load-bearing tests here are: [SiteTagsApi.create]/[SiteTagsApi.rename] each send exactly `{"name"}`
 * and echo the server's own saved [Tag]; a `Tag.AlreadyExists`/`Tag.NotFound` refusal is rendered
 * verbatim; and [SiteTagsApi.delete]'s `204` is [TagDeleteResult.Deleted] via a real `DELETE`.
 */
class KtorSiteTagsApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"
    private val siteId = "site-123"
    private val tagId = "tag-1"

    // ------------------------------------------------------------------- GET /api/v1/sites/{siteId}/tags

    @Test
    fun `the vocabulary is read with the current site id in the path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """{"tags":[{"id":"$tagId","name":"Оплата","createdAt":"2026-09-01T00:00:00Z"}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetch()

            assertEquals(
                TagVocabularyResult.Loaded(listOf(Tag(id = tagId, name = "Оплата", createdAt = "2026-09-01T00:00:00Z"))),
                result,
            )
            assertEquals("$baseUrl/api/v1/sites/$siteId/tags", requestedUrl)
        }

    @Test
    fun `an empty vocabulary is a valid loaded result, not a failure`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"tags":[]}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(TagVocabularyResult.Loaded(emptyList()), api.fetch())
        }

    @Test
    fun `no active site selected on the read is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(TagVocabularyResult.Failed(NetworkFailure.Unexpected), api.fetch())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 5xx on the read is a server error, not an empty vocabulary`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(TagVocabularyResult.Failed(NetworkFailure.ServerError(503)), api.fetch())
        }

    @Test
    fun `a dropped connection on the read is NoConnection`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(TagVocabularyResult.Failed(NetworkFailure.NoConnection), api.fetch())
        }

    @Test
    fun `a 200 that dropped the shape is Failed, never a fabricated empty vocabulary`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(TagVocabularyResult.Failed(NetworkFailure.Unexpected), api.fetch())
        }

    // ------------------------------------------------------------------ POST /api/v1/sites/{siteId}/tags

    @Test
    fun `create sends exactly the name and echoes the server's own saved tag`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var sentBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"id":"$tagId","name":"Срочно","createdAt":"2026-09-01T00:00:00Z"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.create("Срочно")

            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/sites/$siteId/tags", requested)
            val sent = Json.parseToJsonElement(sentBody!!).jsonObject
            assertEquals(setOf("name"), sent.keys)
            assertEquals("Срочно", sent.getValue("name").jsonPrimitive.content)
            assertEquals(TagMutationResult.Saved(Tag(id = tagId, name = "Срочно", createdAt = "2026-09-01T00:00:00Z")), result)
        }

    @Test
    fun `a duplicate name refusal is rendered verbatim`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Tag.AlreadyExists","detail":"Метка с таким названием уже существует."}""",
                        HttpStatusCode.Conflict,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(TagMutationResult.Refused("Метка с таким названием уже существует."), api.create("Срочно"))
        }

    @Test
    fun `a create refusal with no problem-details body classifies as a server error`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(TagMutationResult.Failed(NetworkFailure.ServerError(403)), api.create("Срочно"))
        }

    @Test
    fun `a dropped connection on create is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(TagMutationResult.Failed(NetworkFailure.NoConnection), api.create("Срочно"))
        }

    @Test
    fun `no active site selected on create is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(TagMutationResult.Failed(NetworkFailure.Unexpected), api.create("Срочно"))
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 2xx create that dropped the shape is Failed, never a fabricated tag`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(TagMutationResult.Failed(NetworkFailure.Unexpected), api.create("Срочно"))
        }

    // ------------------------------------------------------------- PUT /api/v1/sites/{siteId}/tags/{id}

    @Test
    fun `rename sends exactly the name to the tag's own url and echoes the server's own saved tag`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var sentBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"id":"$tagId","name":"Оплата (новое)","createdAt":"2026-09-01T00:00:00Z"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.rename(tagId, "Оплата (новое)")

            assertEquals(HttpMethod.Put to "$baseUrl/api/v1/sites/$siteId/tags/$tagId", requested)
            val sent = Json.parseToJsonElement(sentBody!!).jsonObject
            assertEquals(setOf("name"), sent.keys)
            assertEquals(
                TagMutationResult.Saved(Tag(id = tagId, name = "Оплата (новое)", createdAt = "2026-09-01T00:00:00Z")),
                result,
            )
        }

    @Test
    fun `a rename of a tag another operator already deleted is rendered verbatim`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Tag.NotFound","detail":"Метка не найдена."}""",
                        HttpStatusCode.NotFound,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(TagMutationResult.Refused("Метка не найдена."), api.rename(tagId, "Новое имя"))
        }

    @Test
    fun `a dropped connection on rename is a transport failure`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(TagMutationResult.Failed(NetworkFailure.NoConnection), api.rename(tagId, "Новое имя"))
        }

    // ---------------------------------------------------------- DELETE /api/v1/sites/{siteId}/tags/{id}

    @Test
    fun `a 204 delete is Deleted, sent as a real DELETE to the tag's own url`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(TagDeleteResult.Deleted, api.delete(tagId))
            assertEquals(HttpMethod.Delete to "$baseUrl/api/v1/sites/$siteId/tags/$tagId", requested)
        }

    @Test
    fun `a delete refusal is rendered verbatim`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Tag.NotFound","detail":"Метка не найдена."}""",
                        HttpStatusCode.NotFound,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(TagDeleteResult.Refused("Метка не найдена."), api.delete(tagId))
        }

    @Test
    fun `a delete refusal with no problem-details body classifies as a server error`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(TagDeleteResult.Failed(NetworkFailure.ServerError(403)), api.delete(tagId))
        }

    @Test
    fun `a dropped connection on delete is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(TagDeleteResult.Failed(NetworkFailure.NoConnection), api.delete(tagId))
        }

    @Test
    fun `no active site selected on delete is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(TagDeleteResult.Failed(NetworkFailure.Unexpected), api.delete(tagId))
            assertEquals("no active site must never reach the network", 0, calls)
        }

    // ------------------------------------------------------------------------------------------ fixtures

    private fun jsonHeaders() = headersOf("Content-Type", "application/json")

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorSiteTagsApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorSiteTagsApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }
}
