package ago.chat.android.core.network.conversations

import ago.chat.android.core.domain.conversations.ClaimResult
import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.ConversationSummary
import ago.chat.android.core.domain.conversations.QueueResult
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
