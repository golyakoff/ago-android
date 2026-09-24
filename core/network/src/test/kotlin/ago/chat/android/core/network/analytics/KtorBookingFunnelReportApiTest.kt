package ago.chat.android.core.network.analytics

import ago.chat.android.core.domain.analytics.BookingFunnelReportFailure
import ago.chat.android.core.domain.analytics.BookingFunnelReportResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `26-73`: the booking-funnel report's read, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorTagBreakdownReportApiTest`/`KtorConversionReportApiTest`
 * already establish.
 */
class KtorBookingFunnelReportApiTest {
    private val baseUrl = "https://api.example.invalid"

    @Test
    fun `a loaded response carries the server's own range, counts and the comparison`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor { request ->
                    requestedUrl = request.url.toString()
                    respond(FULL_BODY, HttpStatusCode.OK, jsonHeaders)
                }

            val result = api.fetchBookingFunnelReport(from = null, to = null)

            val loaded = result as BookingFunnelReportResult.Loaded
            assertEquals("2026-08-23T00:00:00Z", loaded.report.from)
            assertEquals("2026-09-22T00:00:00Z", loaded.report.to)
            assertEquals(19, loaded.report.flowsStarted)
            assertEquals(12, loaded.report.flowsClosed)
            assertEquals("2026-07-24T00:00:00Z", loaded.report.previousFrom)
            assertEquals("2026-08-23T00:00:00Z", loaded.report.previousTo)
            assertEquals(13, loaded.report.previousFlowsStarted)
            assertEquals(8, loaded.report.previousFlowsClosed)
            assertEquals("$baseUrl/api/v1/conversations/module-flow-report", requestedUrl)
        }

    @Test
    fun `neither bound is sent when neither is given`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor { request ->
                    requestedUrl = request.url.toString()
                    respond(FULL_BODY, HttpStatusCode.OK, jsonHeaders)
                }

            api.fetchBookingFunnelReport(from = null, to = null)

            assertFalse(requestedUrl?.contains("from=") == true)
            assertFalse(requestedUrl?.contains("to=") == true)
        }

    @Test
    fun `both bounds are sent when both are given`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor { request ->
                    requestedUrl = request.url.toString()
                    respond(FULL_BODY, HttpStatusCode.OK, jsonHeaders)
                }

            api.fetchBookingFunnelReport(from = "2026-09-01T00:00:00Z", to = "2026-09-02T00:00:00Z")

            assertTrue(requestedUrl?.contains("from=2026-09-01T00%3A00%3A00Z") == true)
            assertTrue(requestedUrl?.contains("to=2026-09-02T00%3A00%3A00Z") == true)
        }

    /** This endpoint's own invalid-range code — `ModuleFlow.InvalidRange`, not `Analytics.InvalidRange`
     * — is the one failure this report tells apart from the rest (`docs/backlog/26-73-*.md`'s own Found
     * section). */
    @Test
    fun `ModuleFlow_InvalidRange is its own outcome, not a generic failure`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"ModuleFlow.InvalidRange","detail":"The report range's start must be before its end."}""",
                        HttpStatusCode.BadRequest,
                        jsonHeaders,
                    )
                }

            assertEquals(BookingFunnelReportResult.InvalidRange, api.fetchBookingFunnelReport(from = "bad", to = "range"))
        }

    /** `Analytics.InvalidRange` is a different report's own code - reusing it here would swallow this
     * endpoint's actual `ModuleFlow.InvalidRange` branch, the exact confusion
     * `docs/backlog/26-73-*.md`'s own Found section warns against. */
    @Test
    fun `Analytics_InvalidRange from a different report is an ordinary refusal here, not InvalidRange`() =
        runTest {
            val api =
                apiFor {
                    respond("""{"type":"Analytics.InvalidRange","detail":"nope"}""", HttpStatusCode.BadRequest, jsonHeaders)
                }

            assertEquals(
                BookingFunnelReportResult.Failed(BookingFunnelReportFailure.Unexpected),
                api.fetchBookingFunnelReport(null, null),
            )
        }

    /** A refusal is an ordinary failure here on purpose - this entry is only ever drawn for an operator
     * the app believes holds `site:configure`, so a `Conversation.Forbidden` is a stale answer, not a
     * message worth its own wording ([KtorBookingFunnelReportApi]'s own comment). */
    @Test
    fun `a forbidden refusal is an ordinary refusal, not InvalidRange`() =
        runTest {
            val api =
                apiFor {
                    respond("""{"type":"Conversation.Forbidden","detail":"nope"}""", HttpStatusCode.Forbidden, jsonHeaders)
                }

            assertEquals(
                BookingFunnelReportResult.Failed(BookingFunnelReportFailure.Unexpected),
                api.fetchBookingFunnelReport(null, null),
            )
        }

    @Test
    fun `a 5xx is Unexpected, not an empty report`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(BookingFunnelReportResult.Failed(BookingFunnelReportFailure.Unexpected), api.fetchBookingFunnelReport(null, null))
        }

    @Test
    fun `a dropped connection is Transport, not an empty report`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(BookingFunnelReportResult.Failed(BookingFunnelReportFailure.Transport), api.fetchBookingFunnelReport(null, null))
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never an empty report`() =
        runTest {
            val api = apiFor { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders) }

            assertEquals(BookingFunnelReportResult.Failed(BookingFunnelReportFailure.Unexpected), api.fetchBookingFunnelReport(null, null))
        }

    private fun apiFor(handler: MockRequestHandler): KtorBookingFunnelReportApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorBookingFunnelReportApi(client, baseUrl)
    }

    private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())

    private companion object {
        /** One body every shape assertion above reads, so "what a real response looks like" is written
         * once rather than several slightly different times. */
        val FULL_BODY =
            """
            {
              "from":"2026-08-23T00:00:00Z","to":"2026-09-22T00:00:00Z",
              "flowsStarted":19,"flowsClosed":12,
              "previousFrom":"2026-07-24T00:00:00Z","previousTo":"2026-08-23T00:00:00Z",
              "previousFlowsStarted":13,"previousFlowsClosed":8
            }
            """.trimIndent()
    }
}
