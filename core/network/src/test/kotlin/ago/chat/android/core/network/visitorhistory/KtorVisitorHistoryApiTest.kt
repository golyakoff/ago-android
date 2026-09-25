package ago.chat.android.core.network.visitorhistory

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryConversation
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryPage
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryResult
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
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.time.OffsetDateTime

/**
 * `26-144`: the keyset-paged visitor-history list read, driven through the real client configuration and
 * a `MockEngine` — the identical shape `KtorVisitorSummaryApiTest` already establishes for the same panel.
 * The hub half (opening one past dialog read-only) cannot be exercised without a real socket; see
 * `OperatorHubConnectionTest` for what is provable about it without one.
 */
class KtorVisitorHistoryApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"

    @Test
    fun `a 200 maps rows and the cursor, hitting the visitor-history route with the page params`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """
                        {
                          "conversations": [
                            {
                              "conversationId": "hist-1",
                              "state": "Closed",
                              "startedAt": "2026-03-14T09:30:00+03:00",
                              "closedAt": "2026-03-14T10:00:00+03:00",
                              "previewBody": "спасибо!",
                              "previewAuthorKind": "Visitor",
                              "previewCreatedAt": "2026-03-14T09:59:00+03:00"
                            }
                          ],
                          "nextBeforeId": "hist-1"
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetchVisitorHistory("c1", beforeId = null, pageSize = 20)

            assertEquals(
                VisitorHistoryResult.Loaded(
                    VisitorHistoryPage(
                        conversations =
                            listOf(
                                VisitorHistoryConversation(
                                    conversationId = "hist-1",
                                    state = "Closed",
                                    startedAt = OffsetDateTime.parse("2026-03-14T09:30:00+03:00").toInstant(),
                                    closedAt = OffsetDateTime.parse("2026-03-14T10:00:00+03:00").toInstant(),
                                    previewBody = "спасибо!",
                                    previewAuthorKind = "Visitor",
                                    previewCreatedAt = OffsetDateTime.parse("2026-03-14T09:59:00+03:00").toInstant(),
                                ),
                            ),
                        nextBeforeId = "hist-1",
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/conversations/c1/visitor-history?pageSize=20", requestedUrl)
        }

    @Test
    fun `beforeId rides the query string when the caller pages past the first page`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor { request ->
                    requestedUrl = request.url.toString()
                    respond("""{"conversations":[],"nextBeforeId":null}""", HttpStatusCode.OK, jsonHeaders())
                }

            api.fetchVisitorHistory("c1", beforeId = "hist-9", pageSize = 20)

            assertEquals("$baseUrl/api/v1/conversations/c1/visitor-history?pageSize=20&beforeId=hist-9", requestedUrl)
        }

    @Test
    fun `the last page carries a null cursor, an empty list is a real empty answer`() =
        runTest {
            val api = apiFor { respond("""{"conversations":[],"nextBeforeId":null}""", HttpStatusCode.OK, jsonHeaders()) }

            val result = api.fetchVisitorHistory("c1", beforeId = null, pageSize = 20)

            assertEquals(VisitorHistoryResult.Loaded(VisitorHistoryPage(emptyList(), nextBeforeId = null)), result)
        }

    @Test
    fun `a still-open row with no messages has null closedAt and null preview, and still loads`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"conversations":[{"conversationId":"h2","state":"Assigned","startedAt":"2026-03-14T09:30:00Z"}],"nextBeforeId":null}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            assertEquals(
                VisitorHistoryResult.Loaded(
                    VisitorHistoryPage(
                        conversations =
                            listOf(
                                VisitorHistoryConversation(
                                    conversationId = "h2",
                                    state = "Assigned",
                                    startedAt = OffsetDateTime.parse("2026-03-14T09:30:00Z").toInstant(),
                                    closedAt = null,
                                    previewBody = null,
                                    previewAuthorKind = null,
                                    previewCreatedAt = null,
                                ),
                            ),
                        nextBeforeId = null,
                    ),
                ),
                api.fetchVisitorHistory("c1", beforeId = null, pageSize = 20),
            )
        }

    @Test
    fun `a row whose startedAt will not parse loads with a null date rather than blanking the page`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"conversations":[{"conversationId":"h3","state":"Closed","startedAt":"not-a-date"}],"nextBeforeId":null}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetchVisitorHistory("c1", beforeId = null, pageSize = 20)

            val row = (result as VisitorHistoryResult.Loaded).page.conversations.single()
            assertEquals("h3", row.conversationId)
            assertNull("an unparseable startedAt must degrade to null, not fail the whole read", row.startedAt)
        }

    @Test
    fun `a 5xx is a server error, not a fabricated empty history`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(
                VisitorHistoryResult.Failed(NetworkFailure.ServerError(503)),
                api.fetchVisitorHistory("c1", beforeId = null, pageSize = 20),
            )
        }

    @Test
    fun `a dropped connection is NoConnection`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(
                VisitorHistoryResult.Failed(NetworkFailure.NoConnection),
                api.fetchVisitorHistory("c1", beforeId = null, pageSize = 20),
            )
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never a fabricated empty history`() =
        runTest {
            val api = apiFor { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(
                VisitorHistoryResult.Failed(NetworkFailure.Unexpected),
                api.fetchVisitorHistory("c1", beforeId = null, pageSize = 20),
            )
        }

    private fun jsonHeaders() = headersOf("Content-Type", ContentType.Application.Json.toString())

    private fun apiFor(handler: MockRequestHandler): KtorVisitorHistoryApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorVisitorHistoryApi(client, baseUrl)
    }
}
