package ago.chat.android.core.network.visitorsummary

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.visitorsummary.VisitorSummary
import ago.chat.android.core.domain.visitorsummary.VisitorSummaryResult
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
import org.junit.Test
import java.io.IOException
import java.time.OffsetDateTime

/**
 * `26-143`: the single visitor-summary read, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorContactDetailsApiTest` already establishes for the same panel.
 */
class KtorVisitorSummaryApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"

    @Test
    fun `a 200 maps first-seen date and count, hitting the visitor-summary route`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """{"visitorFirstSeenAt":"2026-03-14T09:30:00+03:00","conversationCount":7}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetchVisitorSummary("c1")

            assertEquals(
                VisitorSummaryResult.Loaded(
                    VisitorSummary(
                        firstSeenAt = OffsetDateTime.parse("2026-03-14T09:30:00+03:00").toInstant(),
                        conversationCount = 7,
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/conversations/c1/visitor-summary", requestedUrl)
        }

    @Test
    fun `a 200 with a Z-offset date parses to the same instant`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"visitorFirstSeenAt":"2026-03-14T06:30:00Z","conversationCount":1}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            assertEquals(
                VisitorSummaryResult.Loaded(
                    VisitorSummary(firstSeenAt = OffsetDateTime.parse("2026-03-14T06:30:00Z").toInstant(), conversationCount = 1),
                ),
                api.fetchVisitorSummary("c1"),
            )
        }

    @Test
    fun `a 200 whose date will not parse is Loaded with a null first-seen, count still shown`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"visitorFirstSeenAt":"not-a-date","conversationCount":3}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            assertEquals(
                VisitorSummaryResult.Loaded(VisitorSummary(firstSeenAt = null, conversationCount = 3)),
                api.fetchVisitorSummary("c1"),
            )
        }

    @Test
    fun `a 5xx is a server error, not a fabricated summary`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(VisitorSummaryResult.Failed(NetworkFailure.ServerError(503)), api.fetchVisitorSummary("c1"))
        }

    @Test
    fun `a dropped connection is NoConnection`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(VisitorSummaryResult.Failed(NetworkFailure.NoConnection), api.fetchVisitorSummary("c1"))
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never a fabricated summary`() =
        runTest {
            val api = apiFor { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(VisitorSummaryResult.Failed(NetworkFailure.Unexpected), api.fetchVisitorSummary("c1"))
        }

    private fun jsonHeaders() = headersOf("Content-Type", ContentType.Application.Json.toString())

    private fun apiFor(handler: MockRequestHandler): KtorVisitorSummaryApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorVisitorSummaryApi(client, baseUrl)
    }
}
