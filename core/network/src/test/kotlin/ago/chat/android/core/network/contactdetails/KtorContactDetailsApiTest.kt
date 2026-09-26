package ago.chat.android.core.network.contactdetails

import ago.chat.android.core.domain.contactdetails.ContactDetail
import ago.chat.android.core.domain.contactdetails.ContactDetailWriteResult
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
import io.ktor.http.content.OutgoingContent
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
    fun `every row round-trips, Name included and never masked, assessment parsed and defaulted`() =
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
                            {"id":"cd-2","kind":"Phone","value":"+7•••••1234","masked":true,"assessment":"Confirmed"},
                            {"id":"cd-3","kind":"Email","value":"anya@example.com","masked":false,"assessment":"Invalid"}
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
                        // No `assessment` on the wire at all - the pre-`26-167` shape - still parses,
                        // defaulted to "Unset" rather than failing decode.
                        ContactDetail(id = "cd-1", kind = "Name", value = "Аня", masked = false, assessment = "Unset"),
                        ContactDetail(id = "cd-2", kind = "Phone", value = "+7•••••1234", masked = true, assessment = "Confirmed"),
                        ContactDetail(id = "cd-3", kind = "Email", value = "anya@example.com", masked = false, assessment = "Invalid"),
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

    // ---------------------------- PATCH /api/v1/conversations/{id}/contact-details/{id}

    @Test
    fun `a 2xx edit sends the new value and the updated row comes back, assessment reset included`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var sentBody: String? = null
            val api =
                apiFor { request ->
                    requested = request.method to request.url.toString()
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"id":"cd-2","kind":"Phone","value":"+79997654321","masked":false,"assessment":"Unset"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.editContactDetail("c1", "cd-2", "+79997654321")

            assertEquals(
                ContactDetailWriteResult.Updated(
                    ContactDetail(id = "cd-2", kind = "Phone", value = "+79997654321", masked = false, assessment = "Unset"),
                ),
                result,
            )
            assertEquals(HttpMethod.Patch to "$baseUrl/api/v1/conversations/c1/contact-details/cd-2", requested)
            assertEquals("""{"value":"+79997654321"}""", sentBody)
        }

    @Test
    fun `a 400 with a problem-details body on edit is rendered as that exact refusal, draft implied to stay`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"VisitorContactDetail.Invalid","detail":"A contact detail value cannot be blank."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            val result = api.editContactDetail("c1", "cd-2", "")

            assertEquals(ContactDetailWriteResult.Refused("A contact detail value cannot be blank."), result)
        }

    @Test
    fun `a 404 with no problem-details body on edit classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.NotFound) }

            assertEquals(
                ContactDetailWriteResult.Failed(NetworkFailure.ServerError(404)),
                api.editContactDetail("c1", "cd-2", "+79997654321"),
            )
        }

    @Test
    fun `a dropped connection on edit is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(
                ContactDetailWriteResult.Failed(NetworkFailure.NoConnection),
                api.editContactDetail("c1", "cd-2", "+79997654321"),
            )
        }

    @Test
    fun `a 2xx edit that dropped the shape is Failed, never a fabricated row`() =
        runTest {
            val api = apiFor { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(
                ContactDetailWriteResult.Failed(NetworkFailure.Unexpected),
                api.editContactDetail("c1", "cd-2", "+79997654321"),
            )
        }

    // ------------------ PATCH /api/v1/conversations/{id}/contact-details/{id}/assessment

    @Test
    fun `a 2xx assessment write sends the target assessment and the updated row comes back`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var sentBody: String? = null
            val api =
                apiFor { request ->
                    requested = request.method to request.url.toString()
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"id":"cd-2","kind":"Phone","value":"+79991234567","masked":false,"assessment":"Confirmed"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.setContactDetailAssessment("c1", "cd-2", "Confirmed")

            assertEquals(
                ContactDetailWriteResult.Updated(
                    ContactDetail(id = "cd-2", kind = "Phone", value = "+79991234567", masked = false, assessment = "Confirmed"),
                ),
                result,
            )
            assertEquals(HttpMethod.Patch to "$baseUrl/api/v1/conversations/c1/contact-details/cd-2/assessment", requested)
            assertEquals("""{"assessment":"Confirmed"}""", sentBody)
        }

    @Test
    fun `a 400 InvalidAssessment problem-details body is rendered as that exact refusal`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"VisitorContactDetail.InvalidAssessment","detail":"\"Unset\" is not a settable assessment."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            val result = api.setContactDetailAssessment("c1", "cd-2", "Unset")

            assertEquals(ContactDetailWriteResult.Refused("\"Unset\" is not a settable assessment."), result)
        }

    @Test
    fun `a 400 AssessmentNotApplicable problem-details body on a Name row is rendered as that exact refusal`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"VisitorContactDetail.AssessmentNotApplicable","detail":"Name rows cannot be assessed."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            val result = api.setContactDetailAssessment("c1", "cd-1", "Confirmed")

            assertEquals(ContactDetailWriteResult.Refused("Name rows cannot be assessed."), result)
        }

    @Test
    fun `a 404 with no problem-details body on the assessment write classifies as a server error`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.NotFound) }

            assertEquals(
                ContactDetailWriteResult.Failed(NetworkFailure.ServerError(404)),
                api.setContactDetailAssessment("c1", "cd-2", "Confirmed"),
            )
        }

    @Test
    fun `a dropped connection on the assessment write is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(
                ContactDetailWriteResult.Failed(NetworkFailure.NoConnection),
                api.setContactDetailAssessment("c1", "cd-2", "Confirmed"),
            )
        }

    @Test
    fun `a 2xx assessment write that dropped the shape is Failed, never a fabricated row`() =
        runTest {
            val api = apiFor { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(
                ContactDetailWriteResult.Failed(NetworkFailure.Unexpected),
                api.setContactDetailAssessment("c1", "cd-2", "Confirmed"),
            )
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
