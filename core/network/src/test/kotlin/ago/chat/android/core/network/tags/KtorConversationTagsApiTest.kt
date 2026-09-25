package ago.chat.android.core.network.tags

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.tags.ConversationTag
import ago.chat.android.core.domain.tags.ConversationTagsResult
import ago.chat.android.core.domain.tags.Tag
import ago.chat.android.core.domain.tags.TagActionResult
import ago.chat.android.core.domain.tags.TagVocabularyResult
import ago.chat.android.core.network.InMemoryActiveSite
import ago.chat.android.core.network.MutableAccessTokenProvider
import ago.chat.android.core.network.installAgoRestDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * `26-115`: the vocabulary read, the applied-tags read, and the apply/remove writes, driven through the
 * real client configuration and a `MockEngine` — the identical shape `KtorOperatorTeamApiTest` already
 * establishes for its own `{siteId}`-scoped reads.
 */
class KtorConversationTagsApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"
    private val siteId = "site-123"

    // ------------------------------------------------------------- GET /api/v1/sites/{siteId}/tags

    @Test
    fun `the site vocabulary is read with the current site id in the path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """{"tags":[{"id":"t1","name":"Оплата","createdAt":"2026-09-01T00:00:00Z"}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetchSiteTags()

            assertEquals(
                TagVocabularyResult.Loaded(listOf(Tag(id = "t1", name = "Оплата", createdAt = "2026-09-01T00:00:00Z"))),
                result,
            )
            assertEquals("$baseUrl/api/v1/sites/$siteId/tags", requestedUrl)
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

            assertEquals(TagVocabularyResult.Failed(NetworkFailure.Unexpected), api.fetchSiteTags())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 5xx on the vocabulary read is a server error, not an empty vocabulary`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(TagVocabularyResult.Failed(NetworkFailure.ServerError(503)), api.fetchSiteTags())
        }

    @Test
    fun `a dropped connection on the vocabulary read is NoConnection`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(TagVocabularyResult.Failed(NetworkFailure.NoConnection), api.fetchSiteTags())
        }

    // ------------------------------------------------- GET /api/v1/conversations/{id}/tags

    @Test
    fun `applied tags carry their own source, unparsed`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """{"tags":[{"id":"t1","name":"Срочно","createdAt":"2026-09-01T00:00:00Z","source":"Ai"}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetchConversationTags("c1")

            assertEquals(
                ConversationTagsResult.Loaded(
                    listOf(ConversationTag(id = "t1", name = "Срочно", createdAt = "2026-09-01T00:00:00Z", source = "Ai")),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/conversations/c1/tags", requestedUrl)
        }

    @Test
    fun `an empty applied-tags list is Loaded with no rows`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"tags":[]}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(ConversationTagsResult.Loaded(emptyList()), api.fetchConversationTags("c1"))
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never an empty list`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(ConversationTagsResult.Failed(NetworkFailure.Unexpected), api.fetchConversationTags("c1"))
        }

    // -------------------------------------------- POST /api/v1/conversations/{id}/tags/{tagId}

    @Test
    fun `a 204 apply is Succeeded`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(TagActionResult.Succeeded, api.applyTag("c1", "t1"))
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/conversations/c1/tags/t1", requested)
        }

    @Test
    fun `a 403 apply refusal is rendered verbatim`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Conversation.Forbidden","detail":"Не хватает разрешения conversation:tag."}""",
                        HttpStatusCode.Forbidden,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(TagActionResult.Refused("Не хватает разрешения conversation:tag."), api.applyTag("c1", "t1"))
        }

    @Test
    fun `an apply refusal with no problem-details body classifies as a server error`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(TagActionResult.Failed(NetworkFailure.ServerError(403)), api.applyTag("c1", "t1"))
        }

    @Test
    fun `a dropped connection on apply is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(TagActionResult.Failed(NetworkFailure.NoConnection), api.applyTag("c1", "t1"))
        }

    // ----------------------------------------- DELETE /api/v1/conversations/{id}/tags/{tagId}

    @Test
    fun `a 204 remove is Succeeded, sent as a real DELETE`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(TagActionResult.Succeeded, api.removeTag("c1", "t1"))
            assertEquals(HttpMethod.Delete to "$baseUrl/api/v1/conversations/c1/tags/t1", requested)
        }

    @Test
    fun `a remove refusal is rendered verbatim`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Tag.NotApplied","detail":"Эта метка не применена к диалогу."}""",
                        HttpStatusCode.NotFound,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(TagActionResult.Refused("Эта метка не применена к диалогу."), api.removeTag("c1", "t1"))
        }

    private fun jsonHeaders() = headersOf("Content-Type", ContentType.Application.Json.toString())

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorConversationTagsApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorConversationTagsApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }
}
