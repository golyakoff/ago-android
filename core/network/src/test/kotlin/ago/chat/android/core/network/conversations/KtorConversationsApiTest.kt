package ago.chat.android.core.network.conversations

import ago.chat.android.core.domain.conversations.AllConversationsResult
import ago.chat.android.core.domain.conversations.ClaimResult
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.ConversationSearchHit
import ago.chat.android.core.domain.conversations.ConversationSummary
import ago.chat.android.core.domain.conversations.ErasureResult
import ago.chat.android.core.domain.conversations.QueueResult
import ago.chat.android.core.domain.conversations.SearchConversationsResult
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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `26-14`: the queue read and the claim write, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorIdentityApiTest` already establishes for the pre-session flow.
 */
class KtorConversationsApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"

    // -------------------------------------------------------------- GET /api/v1/conversations/queue

    @Test
    fun `both halves of the queue are read in the order the server sent them`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {
                          "waiting": [
                            {"conversationId":"c1","visitorId":"v1","createdAt":"2026-09-22T09:00:00Z","operatorUnreadCount":0}
                          ],
                          "assignedToMe": [
                            {
                              "conversationId":"c2","visitorId":"v2","createdAt":"2026-09-22T08:00:00Z",
                              "operatorUnreadCount":3,"emojiCreature":"🦊","emojiFood":"🍕","visitorName":"Аня"
                            }
                          ]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.fetchQueue()

            assertEquals(
                QueueResult.Loaded(
                    ConversationQueue(
                        waiting =
                            listOf(
                                ConversationSummary(
                                    "c1",
                                    "v1",
                                    emojiCreature = null,
                                    emojiFood = null,
                                    visitorName = null,
                                    createdAt = "2026-09-22T09:00:00Z",
                                    operatorUnreadCount = 0,
                                ),
                            ),
                        assignedToMe =
                            listOf(
                                ConversationSummary(
                                    "c2",
                                    "v2",
                                    emojiCreature = "🦊",
                                    emojiFood = "🍕",
                                    visitorName = "Аня",
                                    createdAt = "2026-09-22T08:00:00Z",
                                    operatorUnreadCount = 3,
                                ),
                            ),
                    ),
                ),
                result,
            )
        }

    @Test
    fun `hasAttachmentUploadGrant maps through, and defaults false for a row that predates it`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {
                          "waiting": [],
                          "assignedToMe": [
                            {"conversationId":"c1","visitorId":"v1","createdAt":"2026-09-22T09:00:00Z","operatorUnreadCount":0,"hasAttachmentUploadGrant":true},
                            {"conversationId":"c2","visitorId":"v2","createdAt":"2026-09-22T09:00:00Z","operatorUnreadCount":0}
                          ]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val loaded = api.fetchQueue() as QueueResult.Loaded

            assertTrue(
                loaded.queue.assignedToMe
                    .single { it.conversationId == "c1" }
                    .hasAttachmentUploadGrant,
            )
            assertEquals(
                false,
                loaded.queue.assignedToMe
                    .single { it.conversationId == "c2" }
                    .hasAttachmentUploadGrant,
            )
        }

    @Test
    fun `26-29's lastMessagePreview and lastMessageAt map through, and default null for a row that predates them`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {
                          "waiting": [],
                          "assignedToMe": [
                            {
                              "conversationId":"c1","visitorId":"v1","createdAt":"2026-09-22T09:00:00Z","operatorUnreadCount":1,
                              "lastMessagePreview":"how can I help?","lastMessageAt":"2026-09-22T09:05:00Z"
                            },
                            {"conversationId":"c2","visitorId":"v2","createdAt":"2026-09-22T09:00:00Z","operatorUnreadCount":0}
                          ]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val loaded = api.fetchQueue() as QueueResult.Loaded

            val withPreview = loaded.queue.assignedToMe.single { it.conversationId == "c1" }
            assertEquals("how can I help?", withPreview.lastMessagePreview)
            assertEquals("2026-09-22T09:05:00Z", withPreview.lastMessageAt)

            val predatesTheField = loaded.queue.assignedToMe.single { it.conversationId == "c2" }
            assertEquals(null, predatesTheField.lastMessagePreview)
            assertEquals(null, predatesTheField.lastMessageAt)
        }

    @Test
    fun `26-76's lastMessageContentKind maps through, and defaults null for a row that predates it`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {
                          "waiting": [],
                          "assignedToMe": [
                            {
                              "conversationId":"c1","visitorId":"v1","createdAt":"2026-09-22T09:00:00Z","operatorUnreadCount":1,
                              "lastMessagePreview":"Выберите дату","lastMessageAt":"2026-09-22T09:05:00Z",
                              "lastMessageContentKind":"choice_list"
                            },
                            {"conversationId":"c2","visitorId":"v2","createdAt":"2026-09-22T09:00:00Z","operatorUnreadCount":0}
                          ]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val loaded = api.fetchQueue() as QueueResult.Loaded

            val moduleStepRow = loaded.queue.assignedToMe.single { it.conversationId == "c1" }
            assertEquals("choice_list", moduleStepRow.lastMessageContentKind)

            val predatesTheField = loaded.queue.assignedToMe.single { it.conversationId == "c2" }
            assertEquals(null, predatesTheField.lastMessageContentKind)
        }

    @Test
    fun `an unknown field on the wire does not break the read`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"waiting":[],"assignedToMe":[],"tierAddedByALaterItem":"free"}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(QueueResult.Loaded(ConversationQueue(emptyList(), emptyList())), api.fetchQueue())
        }

    @Test
    fun `a 5xx on the queue read is not an empty queue`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.ServiceUnavailable) }

            val result = api.fetchQueue()

            assertTrue(result is QueueResult.Failed)
        }

    @Test
    fun `a dropped connection on the queue read is not an empty queue either`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertTrue(api.fetchQueue() is QueueResult.Failed)
        }

    @Test
    fun `a 200 that dropped the shape is a failed read, never an empty queue`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"somethingElseEntirely":true}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertTrue(api.fetchQueue() is QueueResult.Failed)
        }

    // ------------------------------------------------------------- POST /api/v1/conversations/{id}/claim

    @Test
    fun `a 204 is a claim`() =
        runTest {
            val requested = mutableListOf<Pair<HttpMethod, String>>()
            val api =
                apiFor(recordTo = requested) {
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(ClaimResult.Claimed, api.claim("c1"))
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/conversations/c1/claim", requested.single())
        }

    @Test
    fun `a 409 with a problem-details body is rendered as that exact refusal, not retried`() =
        runTest {
            var calls = 0
            val api =
                apiFor {
                    calls++
                    respond(
                        """{"type":"Conversation.InvalidState","detail":"Этот диалог уже забрал другой оператор."}""",
                        HttpStatusCode.Conflict,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            val result = api.claim("c1")

            assertEquals(ClaimResult.Refused("Этот диалог уже забрал другой оператор."), result)
            assertEquals("the client makes exactly one attempt - a refusal is never retried into a success", 1, calls)
        }

    @Test
    fun `a refusal with no problem-details body classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.Forbidden) }

            assertEquals(ClaimResult.Failed(NetworkFailure.ServerError(403)), api.claim("c1"))
        }

    @Test
    fun `a dropped connection on claim is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(ClaimResult.Failed(NetworkFailure.NoConnection), api.claim("c1"))
        }

    // -------------------------------------------------------------- POST /api/v1/conversations/{id}/read

    @Test
    fun `a 200 is true, and the body sent carries the exact watermark asked for`() =
        runTest {
            val requested = mutableListOf<Pair<HttpMethod, String>>()
            var sentBody = ""
            val api =
                apiFor(recordTo = requested) { request ->
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"operatorUnreadCount":0,"operatorLastReadSequence":42}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(true, api.markRead("c1", 42))
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/conversations/c1/read", requested.single())
            assertTrue("the exact watermark asked for, not a rounded or re-derived one", sentBody.contains("\"upToSequence\":42"))
        }

    @Test
    fun `a 403 for a conversation that is not this operator's is false, not thrown`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"Conversation.NotYours","detail":"Not the assigned operator."}""",
                        HttpStatusCode.Forbidden,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(
                "a real server refusal collapses to false the same as a transport failure - " +
                    "ConversationsApi.markRead's own doc comment on why this never earns a sealed result",
                false,
                api.markRead("c1", 42),
            )
        }

    @Test
    fun `a 409 from a doubly-raced write is false, and is never retried by this method itself`() =
        runTest {
            var calls = 0
            val api =
                apiFor {
                    calls++
                    respondError(HttpStatusCode.Conflict)
                }

            assertEquals(false, api.markRead("c1", 42))
            assertEquals("exactly one attempt - retrying is the next debounced call's job, not this method's", 1, calls)
        }

    @Test
    fun `a dropped connection on mark-read is false, not a thrown exception`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(false, api.markRead("c1", 42))
        }

    // ---------------------------------------------------------------- `26-90`: GET /conversations/all

    @Test
    fun `the state filter is sent as a repeated query key, which is what the server binds`() =
        runTest {
            val requested = mutableListOf<Pair<HttpMethod, String>>()
            val api =
                apiFor(recordTo = requested) {
                    respond(
                        """{"conversations":[],"nextBeforeId":null}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            api.fetchAllConversations(beforeId = "c9", pageSize = 50, states = listOf("Waiting", "Assigned"))

            val url = requested.single().second
            // One `state=` per value, not a comma-joined one - `HandleGetAllForSiteAsync`'s own
            // `string[]? state` binds a repeated key, and a joined value would arrive as a single
            // unparseable state name the handler refuses outright.
            assertTrue(url, url.contains("state=Waiting"))
            assertTrue(url, url.contains("state=Assigned"))
            assertTrue(url, url.contains("beforeId=c9"))
            assertTrue(url, url.contains("pageSize=50"))
        }

    @Test
    fun `a page maps its rows and its keyset cursor, including 26-90's own two new fields`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {
                          "conversations": [
                            {
                              "conversationId":"c1","visitorId":"v1","createdAt":"2026-09-22T09:00:00Z",
                              "operatorUnreadCount":0,"state":"Assigned","operatorName":"Мария П.",
                              "messageCount":9,"lastMessagePreview":"Спасибо!","lastMessageAt":"2026-09-22T09:06:00Z"
                            },
                            {
                              "conversationId":"c2","visitorId":"v2","createdAt":"2026-09-22T08:00:00Z",
                              "operatorUnreadCount":0,"state":"Waiting"
                            }
                          ],
                          "nextBeforeId":"c2"
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val page = (api.fetchAllConversations(null, 50, listOf("Waiting")) as AllConversationsResult.Loaded).page

            assertEquals("c2", page.nextBeforeId)
            val first = page.conversations.first()
            assertEquals(9, first.messageCount)
            assertEquals("Мария П.", first.operatorName)
            assertEquals("Спасибо!", first.lastMessagePreview)
            // A row with neither field on the wire defaults rather than failing the whole read - the
            // additive-contract rule every other field on this DTO already follows.
            assertEquals(0, page.conversations[1].messageCount)
            assertEquals(null, page.conversations[1].operatorName)
        }

    @Test
    fun `a 403 on the site-wide list is a failed read, never an empty site`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.Forbidden) }

            assertEquals(
                AllConversationsResult.Failed(NetworkFailure.ServerError(403)),
                api.fetchAllConversations(null, 50, listOf("Waiting")),
            )
        }

    // --------------------------------------------------------------- `26-90`: POST /{id}/erase

    @Test
    fun `a 202 is an accepted request, not a completed deletion`() =
        runTest {
            val requested = mutableListOf<Pair<HttpMethod, String>>()
            val api = apiFor(recordTo = requested) { respond("", HttpStatusCode.Accepted) }

            assertEquals(ErasureResult.Accepted, api.requestErasure("c1"))
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/conversations/c1/erase", requested.single())
        }

    @Test
    fun `a refused erasure carries the server's own words, never a fabricated sentence`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"Conversation.Forbidden","detail":"Operator does not have permission to erase."}""",
                        HttpStatusCode.Forbidden,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(
                ErasureResult.Refused("Operator does not have permission to erase."),
                api.requestErasure("c1"),
            )
        }

    @Test
    fun `a dropped connection on erase is a transport failure, not a silently repeated write`() =
        runTest {
            var calls = 0
            val api =
                apiFor {
                    calls++
                    throw IOException("unexpected end of stream")
                }

            assertEquals(ErasureResult.Failed(NetworkFailure.NoConnection), api.requestErasure("c1"))
            assertEquals("exactly one attempt - an irreversible write is never retried by this adapter", 1, calls)
        }

    // --------------------------------------------------------------- `26-245`: GET /conversations/search

    @Test
    fun `every search parameter is sent on the query string`() =
        runTest {
            val requested = mutableListOf<Pair<HttpMethod, String>>()
            val api =
                apiFor(recordTo = requested) {
                    respond(
                        """{"results":[],"nextBeforeMessageId":null,"searchedFrom":"2026-06-28T00:00:00Z","searchedTo":"2026-09-28T00:00:00Z"}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            api.searchConversations(
                phrase = "refund please",
                from = "2026-08-01T00:00:00Z",
                to = "2026-09-01T00:00:00Z",
                beforeMessageId = "m9",
                pageSize = 50,
            )

            val url = requested.single().second
            assertEquals(HttpMethod.Get, requested.single().first)
            // Ktor URL-encodes the phrase's space; assert on the encoded form the wire actually carries.
            assertTrue(url, url.contains("phrase=refund"))
            assertTrue(url, url.contains("from=2026-08-01"))
            assertTrue(url, url.contains("to=2026-09-01"))
            assertTrue(url, url.contains("beforeMessageId=m9"))
            assertTrue(url, url.contains("pageSize=50"))
        }

    @Test
    fun `optional bounds are omitted from the query string when null`() =
        runTest {
            val requested = mutableListOf<Pair<HttpMethod, String>>()
            val api =
                apiFor(recordTo = requested) {
                    respond(
                        """{"results":[],"nextBeforeMessageId":null,"searchedFrom":"2026-06-28T00:00:00Z","searchedTo":"2026-09-28T00:00:00Z"}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            api.searchConversations(phrase = "hi", from = null, to = null, beforeMessageId = null, pageSize = null)

            val url = requested.single().second
            assertTrue(url, url.contains("phrase=hi"))
            assertTrue(url, !url.contains("from="))
            assertTrue(url, !url.contains("to="))
            assertTrue(url, !url.contains("beforeMessageId="))
            assertTrue(url, !url.contains("pageSize="))
        }

    @Test
    fun `a page maps its hits, its keyset cursor and the range the server actually searched`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {
                          "results": [
                            {
                              "conversationId":"c1","messageId":"m1","sequence":7,"matchedBody":"I need a refund",
                              "authorKind":"Visitor","createdAt":"2026-09-22T09:00:00Z","conversationState":"Assigned"
                            },
                            {
                              "conversationId":"c2","messageId":"m2","sequence":3,"matchedBody":"refund issued",
                              "authorKind":"Operator","createdAt":"2026-09-20T08:00:00Z","conversationState":"Closed"
                            }
                          ],
                          "nextBeforeMessageId":"m2",
                          "searchedFrom":"2026-06-28T00:00:00Z",
                          "searchedTo":"2026-09-28T00:00:00Z"
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val page = (api.searchConversations("refund", null, null, null, 50) as SearchConversationsResult.Loaded).page

            assertEquals("m2", page.nextBeforeMessageId)
            assertEquals("2026-06-28T00:00:00Z", page.searchedFrom)
            assertEquals("2026-09-28T00:00:00Z", page.searchedTo)
            assertEquals(
                ConversationSearchHit(
                    conversationId = "c1",
                    messageId = "m1",
                    sequence = 7,
                    matchedBody = "I need a refund",
                    authorKind = "Visitor",
                    createdAt = "2026-09-22T09:00:00Z",
                    conversationState = "Assigned",
                ),
                page.results.first(),
            )
            assertEquals("Operator", page.results[1].authorKind)
        }

    @Test
    fun `a 400 invalid-query with a problem-details body is a refusal shown verbatim`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"Conversation.SearchInvalidQuery","detail":"Начало периода должно быть раньше конца."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(
                SearchConversationsResult.Refused("Начало периода должно быть раньше конца."),
                api.searchConversations("refund", "2026-09-01T00:00:00Z", "2026-08-01T00:00:00Z", null, 50),
            )
        }

    @Test
    fun `a search refusal with no problem-details body classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.Forbidden) }

            assertEquals(
                SearchConversationsResult.Failed(NetworkFailure.ServerError(403)),
                api.searchConversations("refund", null, null, null, 50),
            )
        }

    @Test
    fun `a dropped connection on search is a failed read, never an empty result set`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(
                SearchConversationsResult.Failed(NetworkFailure.NoConnection),
                api.searchConversations("refund", null, null, null, 50),
            )
        }

    @Test
    fun `a 200 that dropped the shape is a failed read, never an empty result set`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"somethingElseEntirely":true}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertTrue(api.searchConversations("refund", null, null, null, 50) is SearchConversationsResult.Failed)
        }

    private fun apiFor(
        recordTo: MutableList<Pair<HttpMethod, String>>? = null,
        handler: MockRequestHandler,
    ): KtorConversationsApi {
        val client =
            HttpClient(
                MockEngine { request ->
                    recordTo?.add(request.method to request.url.toString())
                    handler(request)
                },
            ) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorConversationsApi(client, baseUrl)
    }
}
