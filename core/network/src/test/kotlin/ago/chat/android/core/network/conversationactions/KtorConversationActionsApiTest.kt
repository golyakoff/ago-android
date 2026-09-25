package ago.chat.android.core.network.conversationactions

import ago.chat.android.core.domain.conversationactions.ConversationActionResult
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
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * `26-146`: the close, grant and revoke writes, each driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorConversationNotesApiTest` already establishes for the sibling
 * `26-115` panel client.
 */
class KtorConversationActionsApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"

    // ----------------------------------------------- POST /api/v1/conversations/{id}/close

    @Test
    fun `a 204 close is Succeeded, and hits the close sub-resource`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            val api =
                apiFor { request ->
                    requested = request.method to request.url.toString()
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(ConversationActionResult.Succeeded, api.close("c1"))
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/conversations/c1/close", requested)
        }

    @Test
    fun `a 403 close refusal with a problem-details body is rendered verbatim`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"Conversation.NotAssignedToOperator","detail":"Этот диалог закреплён за другим оператором."}""",
                        HttpStatusCode.Forbidden,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(
                ConversationActionResult.Refused("Этот диалог закреплён за другим оператором."),
                api.close("c1"),
            )
        }

    @Test
    fun `a close refusal with no problem-details body classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.Conflict) }

            assertEquals(ConversationActionResult.Failed(NetworkFailure.ServerError(409)), api.close("c1"))
        }

    @Test
    fun `a dropped connection on close is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(ConversationActionResult.Failed(NetworkFailure.NoConnection), api.close("c1"))
        }

    // --------------------------------- POST /api/v1/conversations/{id}/grant-attachment-upload

    @Test
    fun `a 200 grant is Succeeded, its status body ignored, and hits the grant sub-resource`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            val api =
                apiFor { request ->
                    requested = request.method to request.url.toString()
                    respond(
                        """{"conversationId":"c1","occurredAt":"2026-09-25T10:00:00Z","operatorId":"op-1"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            assertEquals(ConversationActionResult.Succeeded, api.grantAttachmentUpload("c1"))
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/conversations/c1/grant-attachment-upload", requested)
        }

    @Test
    fun `a grant refusal with a problem-details body is rendered verbatim`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"Conversation.AttachmentUploadAlreadyGranted","detail":"Приём файлов уже включён."}""",
                        HttpStatusCode.Conflict,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(ConversationActionResult.Refused("Приём файлов уже включён."), api.grantAttachmentUpload("c1"))
        }

    @Test
    fun `a dropped connection on grant is a transport failure`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(ConversationActionResult.Failed(NetworkFailure.NoConnection), api.grantAttachmentUpload("c1"))
        }

    // -------------------------------- POST /api/v1/conversations/{id}/revoke-attachment-upload

    @Test
    fun `a 200 revoke is Succeeded and hits the revoke sub-resource`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            val api =
                apiFor { request ->
                    requested = request.method to request.url.toString()
                    respond(
                        """{"conversationId":"c1","occurredAt":"2026-09-25T10:05:00Z","operatorId":"op-1"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            assertEquals(ConversationActionResult.Succeeded, api.revokeAttachmentUpload("c1"))
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/conversations/c1/revoke-attachment-upload", requested)
        }

    @Test
    fun `a revoke refusal with no problem-details body classifies as a server error`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.Conflict) }

            assertEquals(ConversationActionResult.Failed(NetworkFailure.ServerError(409)), api.revokeAttachmentUpload("c1"))
        }

    @Test
    fun `a dropped connection on revoke is a transport failure`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(ConversationActionResult.Failed(NetworkFailure.NoConnection), api.revokeAttachmentUpload("c1"))
        }

    private fun jsonHeaders() = headersOf("Content-Type", ContentType.Application.Json.toString())

    private fun apiFor(handler: MockRequestHandler): KtorConversationActionsApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorConversationActionsApi(client, baseUrl)
    }
}
