package ago.chat.android.core.network.analytics

import ago.chat.android.core.domain.analytics.TagBreakdownBucketRow
import ago.chat.android.core.domain.analytics.TagBreakdownReportFailure
import ago.chat.android.core.domain.analytics.TagBreakdownReportResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `26-72`: the tag-breakdown report's read, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorConversionReportApiTest`/`KtorSiteAnalyticsApiTest` already
 * establish.
 */
class KtorTagBreakdownReportApiTest {
    private val baseUrl = "https://api.example.invalid"

    @Test
    fun `a loaded response carries the server's own range, coverage figures and tag rows`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor { request ->
                    requestedUrl = request.url.toString()
                    respond(FULL_BODY, HttpStatusCode.OK, jsonHeaders)
                }

            val result = api.fetchTagBreakdownReport(from = null, to = null)

            val loaded = result as TagBreakdownReportResult.Loaded
            assertEquals("2026-08-23T00:00:00Z", loaded.report.from)
            assertEquals("2026-09-22T00:00:00Z", loaded.report.to)
            assertEquals(19, loaded.report.totalConversationCount)
            assertEquals(12, loaded.report.taggedConversationCount)
            assertEquals(0.631578947368421, loaded.report.percentageTagged)
            assertEquals("2026-07-24T00:00:00Z", loaded.report.previousFrom)
            assertEquals("2026-08-23T00:00:00Z", loaded.report.previousTo)
            assertEquals(13, loaded.report.previousTotalConversationCount)
            assertEquals(8, loaded.report.previousTaggedConversationCount)
            assertEquals(0.6153846153846154, loaded.report.previousPercentageTagged)
            assertEquals(
                listOf(
                    TagBreakdownBucketRow("tag-pricing", "Цены", 10, 6, 3, 9, 0.6666666666666666),
                    TagBreakdownBucketRow("tag-delivery", "Доставка", 4, 1, 1, 2, 0.5),
                ),
                loaded.report.byTag,
            )
            assertEquals("$baseUrl/api/v1/conversations/tag-breakdown-report", requestedUrl)
        }

    /** `percentageTagged` is `null` when there is nothing to compute coverage from - never `0`, the
     * load-bearing distinction this report exists to keep visible. */
    @Test
    fun `a null percentage tagged is never rendered as a real zero`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {
                          "from":"2026-08-23T00:00:00Z","to":"2026-09-22T00:00:00Z",
                          "totalConversationCount":0,"taggedConversationCount":0,"percentageTagged":null,
                          "previousFrom":"2026-07-24T00:00:00Z","previousTo":"2026-08-23T00:00:00Z",
                          "previousTotalConversationCount":0,"previousTaggedConversationCount":0,"previousPercentageTagged":null
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val loaded = api.fetchTagBreakdownReport(null, null) as TagBreakdownReportResult.Loaded

            assertNull(loaded.report.percentageTagged)
            assertEquals(0, loaded.report.totalConversationCount)
        }

    @Test
    fun `an omitted tag breakdown is an empty table, never a parse failure`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {
                          "from":"2026-08-23T00:00:00Z","to":"2026-09-22T00:00:00Z",
                          "totalConversationCount":0,"taggedConversationCount":0,"percentageTagged":null,
                          "previousFrom":"2026-07-24T00:00:00Z","previousTo":"2026-08-23T00:00:00Z",
                          "previousTotalConversationCount":0,"previousTaggedConversationCount":0,"previousPercentageTagged":null
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val loaded = api.fetchTagBreakdownReport(null, null) as TagBreakdownReportResult.Loaded

            assertTrue(loaded.report.byTag.isEmpty())
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

            api.fetchTagBreakdownReport(from = null, to = null)

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

            api.fetchTagBreakdownReport(from = "2026-09-01T00:00:00Z", to = "2026-09-02T00:00:00Z")

            assertTrue(requestedUrl?.contains("from=2026-09-01T00%3A00%3A00Z") == true)
            assertTrue(requestedUrl?.contains("to=2026-09-02T00%3A00%3A00Z") == true)
        }

    @Test
    fun `Analytics_InvalidRange is its own outcome, not a generic failure`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"Analytics.InvalidRange","detail":"The report range's start must be before its end."}""",
                        HttpStatusCode.BadRequest,
                        jsonHeaders,
                    )
                }

            assertEquals(TagBreakdownReportResult.InvalidRange, api.fetchTagBreakdownReport(from = "bad", to = "range"))
        }

    /** A refusal is an ordinary failure here on purpose - this entry is only ever drawn for an operator
     * the app believes holds `site:configure`, so a `Conversation.Forbidden` is a stale answer, not a
     * message worth its own wording ([KtorTagBreakdownReportApi]'s own comment). */
    @Test
    fun `a forbidden refusal is an ordinary refusal, not InvalidRange`() =
        runTest {
            val api =
                apiFor {
                    respond("""{"type":"Conversation.Forbidden","detail":"nope"}""", HttpStatusCode.Forbidden, jsonHeaders)
                }

            assertEquals(TagBreakdownReportResult.Failed(TagBreakdownReportFailure.Unexpected), api.fetchTagBreakdownReport(null, null))
        }

    @Test
    fun `a 5xx is Unexpected, not an empty report`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(TagBreakdownReportResult.Failed(TagBreakdownReportFailure.Unexpected), api.fetchTagBreakdownReport(null, null))
        }

    @Test
    fun `a dropped connection is Transport, not an empty report`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(TagBreakdownReportResult.Failed(TagBreakdownReportFailure.Transport), api.fetchTagBreakdownReport(null, null))
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never an empty report`() =
        runTest {
            val api = apiFor { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders) }

            assertEquals(TagBreakdownReportResult.Failed(TagBreakdownReportFailure.Unexpected), api.fetchTagBreakdownReport(null, null))
        }

    private fun apiFor(handler: MockRequestHandler): KtorTagBreakdownReportApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorTagBreakdownReportApi(client, baseUrl)
    }

    private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())

    private companion object {
        /** One body every shape assertion above reads, so "what a real response looks like" is written
         * once rather than several slightly different times. */
        val FULL_BODY =
            """
            {
              "from":"2026-08-23T00:00:00Z","to":"2026-09-22T00:00:00Z",
              "totalConversationCount":19,"taggedConversationCount":12,"percentageTagged":0.631578947368421,
              "previousFrom":"2026-07-24T00:00:00Z","previousTo":"2026-08-23T00:00:00Z",
              "previousTotalConversationCount":13,"previousTaggedConversationCount":8,"previousPercentageTagged":0.6153846153846154,
              "byTag":[
                {"tagId":"tag-pricing","tagName":"Цены","conversationCount":10,"convertedCount":6,"notConvertedCount":3,"recordedCount":9,"conversionRate":0.6666666666666666},
                {"tagId":"tag-delivery","tagName":"Доставка","conversationCount":4,"convertedCount":1,"notConvertedCount":1,"recordedCount":2,"conversionRate":0.5}
              ]
            }
            """.trimIndent()
    }
}
