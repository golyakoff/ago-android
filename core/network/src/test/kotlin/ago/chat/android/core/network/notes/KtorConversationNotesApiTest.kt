package ago.chat.android.core.network.notes

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.notes.AddNoteResult
import ago.chat.android.core.domain.notes.ConversationNote
import ago.chat.android.core.domain.notes.ConversationNotesResult
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
 * `26-115`: the list read and the add write, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorConversationsApiTest` already establishes for its own first
 * request body.
 */
class KtorConversationNotesApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"

    // ------------------------------------------------ GET /api/v1/conversations/{id}/notes

    @Test
    fun `notes round-trip in the order the server sent them`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """
                        {
                          "notes": [
                            {"id":"n1","authorId":"op-1","body":"Просил перезвонить после обеда","createdAt":"2026-09-24T10:00:00Z"}
                          ]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetchNotes("c1")

            assertEquals(
                ConversationNotesResult.Loaded(
                    listOf(
                        ConversationNote(
                            id = "n1",
                            authorId = "op-1",
                            body = "Просил перезвонить после обеда",
                            createdAt = "2026-09-24T10:00:00Z",
                        ),
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/conversations/c1/notes", requestedUrl)
        }

    @Test
    fun `an empty notes list is Loaded with no rows, not a failure`() =
        runTest {
            val api = apiFor { respond("""{"notes":[]}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(ConversationNotesResult.Loaded(emptyList()), api.fetchNotes("c1"))
        }

    @Test
    fun `a 5xx on the notes read is a server error, not an empty list`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(ConversationNotesResult.Failed(NetworkFailure.ServerError(503)), api.fetchNotes("c1"))
        }

    @Test
    fun `a dropped connection on the notes read is NoConnection`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(ConversationNotesResult.Failed(NetworkFailure.NoConnection), api.fetchNotes("c1"))
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never an empty list`() =
        runTest {
            val api = apiFor { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(ConversationNotesResult.Failed(NetworkFailure.Unexpected), api.fetchNotes("c1"))
        }

    // ----------------------------------------------- POST /api/v1/conversations/{id}/notes

    @Test
    fun `a 200 add carries the exact body asked for, and the server's own created row comes back`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var sentBody = ""
            val api =
                apiFor { request ->
                    requested = request.method to request.url.toString()
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"id":"n2","authorId":"op-1","body":"Уточнить адрес","createdAt":"2026-09-24T11:00:00Z"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.addNote("c1", "Уточнить адрес")

            assertEquals(
                AddNoteResult.Added(
                    ConversationNote(id = "n2", authorId = "op-1", body = "Уточнить адрес", createdAt = "2026-09-24T11:00:00Z"),
                ),
                result,
            )
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/conversations/c1/notes", requested)
            assertTrue(sentBody.contains("\"body\":\"Уточнить адрес\""))
        }

    @Test
    fun `a 400 refusal with a problem-details body is rendered verbatim`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"Note.EmptyBody","detail":"Текст заметки не может быть пустым."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(AddNoteResult.Refused("Текст заметки не может быть пустым."), api.addNote("c1", ""))
        }

    @Test
    fun `a refusal with no problem-details body classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.Forbidden) }

            assertEquals(AddNoteResult.Failed(NetworkFailure.ServerError(403)), api.addNote("c1", "note"))
        }

    @Test
    fun `a dropped connection on add is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(AddNoteResult.Failed(NetworkFailure.NoConnection), api.addNote("c1", "note"))
        }

    @Test
    fun `a 200 that dropped the shape is Failed, never a fabricated row`() =
        runTest {
            val api = apiFor { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(AddNoteResult.Failed(NetworkFailure.Unexpected), api.addNote("c1", "note"))
        }

    private fun jsonHeaders() = headersOf("Content-Type", ContentType.Application.Json.toString())

    private fun apiFor(handler: MockRequestHandler): KtorConversationNotesApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorConversationNotesApi(client, baseUrl)
    }
}
