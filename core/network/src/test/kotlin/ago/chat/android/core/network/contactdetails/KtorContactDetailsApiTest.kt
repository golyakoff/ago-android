package ago.chat.android.core.network.contactdetails

import ago.chat.android.core.domain.contactdetails.ContactDetail
import ago.chat.android.core.domain.contactdetails.ContactDetailsResult
import ago.chat.android.core.domain.contactdetails.RevealContactDetailResult
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
 * `26-115`: the list read and the reveal write, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorOperatorTeamApiTest` already establishes.
 */
class KtorContactDetailsApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"

    // --------------------------------------- GET /api/v1/conversations/{id}/contact-details

    @Test
    fun `every row round-trips, Name included and never masked`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """
                        {
                          "contactDetails": [
                            {"id":"cd-1","kind":"Name","value":"Аня","masked":false},
                            {"id":"cd-2","kind":"Phone","value":"+7•••••1234","masked":true},
                            {"id":"cd-3","kind":"Email","value":"anya@example.com","masked":false}
                          ]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.fetchContactDetails("c1")

            assertEquals(
                ContactDetailsResult.Loaded(
                    listOf(
                        ContactDetail(id = "cd-1", kind = "Name", value = "Аня", masked = false),
                        ContactDetail(id = "cd-2", kind = "Phone", value = "+7•••••1234", masked = true),
                        ContactDetail(id = "cd-3", kind = "Email", value = "anya@example.com", masked = false),
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/conversations/c1/contact-details", requestedUrl)
        }

    @Test
    fun `an empty list is Loaded with no rows, not a failure`() =
        runTest {
            val api = apiFor { respond("""{"contactDetails":[]}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(ContactDetailsResult.Loaded(emptyList()), api.fetchContactDetails("c1"))
        }

    @Test
    fun `a 5xx on the list read is a server error, not an empty list`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(ContactDetailsResult.Failed(NetworkFailure.ServerError(503)), api.fetchContactDetails("c1"))
        }

    @Test
    fun `a dropped connection on the list read is NoConnection`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(ContactDetailsResult.Failed(NetworkFailure.NoConnection), api.fetchContactDetails("c1"))
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never an empty list`() =
        runTest {
            val api = apiFor { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(ContactDetailsResult.Failed(NetworkFailure.Unexpected), api.fetchContactDetails("c1"))
        }

    // ------------------------- POST /api/v1/conversations/{id}/contact-details/{id}/reveal

    @Test
    fun `a 2xx reveal carries no request body, and the row comes back unmasked`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var hadBody = false
            val api =
                apiFor { request ->
                    requested = request.method to request.url.toString()
                    hadBody = (request.body.contentLength ?: 0L) != 0L
                    respond(
                        """{"id":"cd-2","kind":"Phone","value":"+79991234567","masked":false}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.revealContactDetail("c1", "cd-2")

            assertEquals(
                RevealContactDetailResult.Revealed(ContactDetail(id = "cd-2", kind = "Phone", value = "+79991234567", masked = false)),
                result,
            )
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/conversations/c1/contact-details/cd-2/reveal", requested)
            assertEquals("a reveal sends no body - the route already names both resources", false, hadBody)
        }

    @Test
    fun `a 403 with a problem-details body is rendered as that exact refusal, masked value implied to stay`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"Conversation.Forbidden","detail":"Operator does not have permission to read conversations for this site."}""",
                        HttpStatusCode.Forbidden,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            val result = api.revealContactDetail("c1", "cd-2")

            assertEquals(
                RevealContactDetailResult.Refused("Operator does not have permission to read conversations for this site."),
                result,
            )
        }

    @Test
    fun `a refusal with no problem-details body classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.NotFound) }

            assertEquals(RevealContactDetailResult.Failed(NetworkFailure.ServerError(404)), api.revealContactDetail("c1", "cd-2"))
        }

    @Test
    fun `a dropped connection on reveal is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(RevealContactDetailResult.Failed(NetworkFailure.NoConnection), api.revealContactDetail("c1", "cd-2"))
        }

    @Test
    fun `a 2xx that dropped the shape is Failed, never a fabricated unmasked value`() =
        runTest {
            val api = apiFor { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(RevealContactDetailResult.Failed(NetworkFailure.Unexpected), api.revealContactDetail("c1", "cd-2"))
        }

    private fun jsonHeaders() = headersOf("Content-Type", ContentType.Application.Json.toString())

    private fun apiFor(handler: MockRequestHandler): KtorContactDetailsApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorContactDetailsApi(client, baseUrl)
    }
}
