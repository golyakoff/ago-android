package ago.chat.android.core.network.restrictions

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.restrictions.VisitorRestrictionActionResult
import ago.chat.android.core.domain.restrictions.VisitorRestrictionStatusResult
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
 * `26-145`: block, lift and the is-restricted membership read, driven through the real client
 * configuration and a `MockEngine` — the identical shape `KtorContactDetailsApiTest` already establishes
 * for the sibling slice.
 */
class KtorVisitorRestrictionApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"

    // ------------------------------- POST /api/v1/conversations/{id}/block-visitor

    @Test
    fun `a 2xx block carries no request body and succeeds, its response discarded`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var hadBody = false
            val api =
                apiFor { request ->
                    requested = request.method to request.url.toString()
                    hadBody = (request.body.contentLength ?: 0L) != 0L
                    respond(
                        """{"visitorId":"v1","occurredAt":"2026-09-25T10:00:00+00:00","operatorId":"op1"}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            assertEquals(VisitorRestrictionActionResult.Succeeded, api.block("c1"))
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/conversations/c1/block-visitor", requested)
            assertEquals("a block sends no body - the route already names the conversation", false, hadBody)
        }

    @Test
    fun `a 403 block with a problem-details body is rendered as that exact refusal`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"Conversation.Forbidden","detail":"Operator does not have permission to block visitors for this site."}""",
                        HttpStatusCode.Forbidden,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(
                VisitorRestrictionActionResult.Refused("Operator does not have permission to block visitors for this site."),
                api.block("c1"),
            )
        }

    @Test
    fun `a block refusal with no problem-details body classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.NotFound) }

            assertEquals(VisitorRestrictionActionResult.Failed(NetworkFailure.ServerError(404)), api.block("c1"))
        }

    @Test
    fun `a dropped connection on block is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(VisitorRestrictionActionResult.Failed(NetworkFailure.NoConnection), api.block("c1"))
        }

    // ---------------------------- POST /api/v1/visitor-restrictions/{visitorId}/lift

    @Test
    fun `a 204 lift succeeds and is addressed by visitor id`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            val api =
                apiFor { request ->
                    requested = request.method to request.url.toString()
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(VisitorRestrictionActionResult.Succeeded, api.lift("v1"))
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/visitor-restrictions/v1/lift", requested)
        }

    @Test
    fun `a 403 lift with a problem-details body is rendered as that exact refusal`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"detail":"Only an admin may lift a block."}""",
                        HttpStatusCode.Forbidden,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(VisitorRestrictionActionResult.Refused("Only an admin may lift a block."), api.lift("v1"))
        }

    @Test
    fun `a dropped connection on lift is a transport failure`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(VisitorRestrictionActionResult.Failed(NetworkFailure.NoConnection), api.lift("v1"))
        }

    // ------------------------------------ GET /api/v1/visitor-restrictions (membership)

    @Test
    fun `an unlifted row for this visitor is restricted, and the read is site-scoped by header`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """
                        {
                          "items": [
                            {"id":"r1","visitorId":"v2","kind":"Block","liftedAt":null},
                            {"id":"r2","visitorId":"v1","kind":"Block","liftedAt":null}
                          ],
                          "nextBeforeId": null
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            assertEquals(VisitorRestrictionStatusResult.Loaded(restricted = true), api.isRestricted("v1"))
            assertEquals("$baseUrl/api/v1/visitor-restrictions?limit=200", requestedUrl)
        }

    @Test
    fun `a row that exists only lifted means the visitor is not currently restricted`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {
                          "items": [
                            {"id":"r2","visitorId":"v1","kind":"Block","liftedAt":"2026-09-25T09:00:00+00:00"}
                          ],
                          "nextBeforeId": null
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            assertEquals(VisitorRestrictionStatusResult.Loaded(restricted = false), api.isRestricted("v1"))
        }

    @Test
    fun `no row for this visitor at all means not restricted`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"items":[{"id":"r1","visitorId":"v2","kind":"Block","liftedAt":null}],"nextBeforeId":null}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            assertEquals(VisitorRestrictionStatusResult.Loaded(restricted = false), api.isRestricted("v1"))
        }

    @Test
    fun `an active row on a later page is still found by following the keyset cursor`() =
        runTest {
            val requestedUrls = mutableListOf<String>()
            val api =
                apiFor { request ->
                    requestedUrls += request.url.toString()
                    if (request.url.parameters["before"] == null) {
                        respond(
                            """{"items":[{"id":"r9","visitorId":"v2","kind":"Block","liftedAt":null}],"nextBeforeId":"r9"}""",
                            HttpStatusCode.OK,
                            jsonHeaders(),
                        )
                    } else {
                        respond(
                            """{"items":[{"id":"r2","visitorId":"v1","kind":"Block","liftedAt":null}],"nextBeforeId":null}""",
                            HttpStatusCode.OK,
                            jsonHeaders(),
                        )
                    }
                }

            assertEquals(VisitorRestrictionStatusResult.Loaded(restricted = true), api.isRestricted("v1"))
            assertEquals(
                listOf(
                    "$baseUrl/api/v1/visitor-restrictions?limit=200",
                    "$baseUrl/api/v1/visitor-restrictions?limit=200&before=r9",
                ),
                requestedUrls,
            )
        }

    @Test
    fun `a 403 on the status read is a server error, never a claim the visitor is unrestricted`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.Forbidden) }

            assertEquals(VisitorRestrictionStatusResult.Failed(NetworkFailure.ServerError(403)), api.isRestricted("v1"))
        }

    @Test
    fun `a dropped connection on the status read is NoConnection`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(VisitorRestrictionStatusResult.Failed(NetworkFailure.NoConnection), api.isRestricted("v1"))
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never a fabricated answer`() =
        runTest {
            val api = apiFor { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(VisitorRestrictionStatusResult.Failed(NetworkFailure.Unexpected), api.isRestricted("v1"))
        }

    private fun jsonHeaders() = headersOf("Content-Type", ContentType.Application.Json.toString())

    private fun apiFor(handler: MockRequestHandler): KtorVisitorRestrictionApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorVisitorRestrictionApi(client, baseUrl)
    }
}
