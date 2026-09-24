package ago.chat.android.core.network.analytics

import ago.chat.android.core.domain.analytics.ConversionBucket
import ago.chat.android.core.domain.analytics.ConversionOperatorBreakdownRow
import ago.chat.android.core.domain.analytics.ConversionReportFailure
import ago.chat.android.core.domain.analytics.ConversionReportResult
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
 * `26-71`: the conversion report's read, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorSiteAnalyticsApiTest`/`KtorOwnAnalyticsApiTest` already
 * establish.
 */
class KtorConversionReportApiTest {
    private val baseUrl = "https://api.example.invalid"

    @Test
    fun `a loaded response carries the server's own range, the comparison and the operator breakdown`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor { request ->
                    requestedUrl = request.url.toString()
                    respond(FULL_BODY, HttpStatusCode.OK, jsonHeaders)
                }

            val result = api.fetchConversionReport(from = null, to = null)

            val loaded = result as ConversionReportResult.Loaded
            assertEquals("2026-08-23T00:00:00Z", loaded.report.from)
            assertEquals("2026-09-22T00:00:00Z", loaded.report.to)
            assertEquals(ConversionBucket(12, 5, 2, 3, 19, 0.631578947368421), loaded.report.overall)
            assertEquals("2026-07-24T00:00:00Z", loaded.report.previousFrom)
            assertEquals("2026-08-23T00:00:00Z", loaded.report.previousTo)
            assertEquals(ConversionBucket(8, 4, 1, 2, 13, 0.6153846153846154), loaded.report.previousOverall)
            assertEquals(
                listOf(
                    ConversionOperatorBreakdownRow("op-with-name", "Вера", ConversionBucket(10, 3, 1, 1, 14, 0.7142857142857143)),
                    ConversionOperatorBreakdownRow("op-without-name", null, ConversionBucket(2, 2, 1, 2, 5, 0.4)),
                ),
                loaded.report.byOperator,
            )
            assertEquals("$baseUrl/api/v1/conversations/conversion-report", requestedUrl)
        }

    /** `conversionRate` is `null` when nothing was recorded — never `0`, the load-bearing distinction
     * this report exists to keep visible. */
    @Test
    fun `a null conversion rate is never rendered as a real zero`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {
                          "from":"2026-08-23T00:00:00Z","to":"2026-09-22T00:00:00Z",
                          "overall":{"convertedCount":0,"notConvertedCount":0,"followUpNeededCount":0,"unsetCount":4,"recordedCount":0,"conversionRate":null},
                          "previousFrom":"2026-07-24T00:00:00Z","previousTo":"2026-08-23T00:00:00Z",
                          "previousOverall":{"convertedCount":0,"notConvertedCount":0,"followUpNeededCount":0,"unsetCount":0,"recordedCount":0,"conversionRate":null}
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val loaded = api.fetchConversionReport(null, null) as ConversionReportResult.Loaded

            assertNull(loaded.report.overall.conversionRate)
            assertEquals(4, loaded.report.overall.unsetCount)
        }

    @Test
    fun `an omitted operator breakdown is an empty table, never a parse failure`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {
                          "from":"2026-08-23T00:00:00Z","to":"2026-09-22T00:00:00Z",
                          "overall":{"convertedCount":0,"notConvertedCount":0,"followUpNeededCount":0,"unsetCount":0,"recordedCount":0,"conversionRate":null},
                          "previousFrom":"2026-07-24T00:00:00Z","previousTo":"2026-08-23T00:00:00Z",
                          "previousOverall":{"convertedCount":0,"notConvertedCount":0,"followUpNeededCount":0,"unsetCount":0,"recordedCount":0,"conversionRate":null}
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val loaded = api.fetchConversionReport(null, null) as ConversionReportResult.Loaded

            assertTrue(loaded.report.byOperator.isEmpty())
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

            api.fetchConversionReport(from = null, to = null)

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

            api.fetchConversionReport(from = "2026-09-01T00:00:00Z", to = "2026-09-02T00:00:00Z")

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

            assertEquals(ConversionReportResult.InvalidRange, api.fetchConversionReport(from = "bad", to = "range"))
        }

    /** A refusal is an ordinary failure here on purpose - this entry is only ever drawn for an operator
     * the app believes holds `site:configure`, so a `Conversation.Forbidden` is a stale answer, not a
     * message worth its own wording ([KtorConversionReportApi]'s own comment). */
    @Test
    fun `a forbidden refusal is an ordinary refusal, not InvalidRange`() =
        runTest {
            val api =
                apiFor {
                    respond("""{"type":"Conversation.Forbidden","detail":"nope"}""", HttpStatusCode.Forbidden, jsonHeaders)
                }

            assertEquals(ConversionReportResult.Failed(ConversionReportFailure.Unexpected), api.fetchConversionReport(null, null))
        }

    @Test
    fun `a 5xx is Unexpected, not an empty report`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(ConversionReportResult.Failed(ConversionReportFailure.Unexpected), api.fetchConversionReport(null, null))
        }

    @Test
    fun `a dropped connection is Transport, not an empty report`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(ConversionReportResult.Failed(ConversionReportFailure.Transport), api.fetchConversionReport(null, null))
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never an empty report`() =
        runTest {
            val api = apiFor { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders) }

            assertEquals(ConversionReportResult.Failed(ConversionReportFailure.Unexpected), api.fetchConversionReport(null, null))
        }

    private fun apiFor(handler: MockRequestHandler): KtorConversionReportApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorConversionReportApi(client, baseUrl)
    }

    private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())

    private companion object {
        /** One body every shape assertion above reads, so "what a real response looks like" is written
         * once rather than several slightly different times. */
        val FULL_BODY =
            """
            {
              "from":"2026-08-23T00:00:00Z","to":"2026-09-22T00:00:00Z",
              "overall":{"convertedCount":12,"notConvertedCount":5,"followUpNeededCount":2,"unsetCount":3,"recordedCount":19,"conversionRate":0.631578947368421},
              "previousFrom":"2026-07-24T00:00:00Z","previousTo":"2026-08-23T00:00:00Z",
              "previousOverall":{"convertedCount":8,"notConvertedCount":4,"followUpNeededCount":1,"unsetCount":2,"recordedCount":13,"conversionRate":0.6153846153846154},
              "byOperator":[
                {"operatorId":"op-with-name","operatorName":"Вера",
                 "bucket":{"convertedCount":10,"notConvertedCount":3,"followUpNeededCount":1,"unsetCount":1,"recordedCount":14,"conversionRate":0.7142857142857143}},
                {"operatorId":"op-without-name",
                 "bucket":{"convertedCount":2,"notConvertedCount":2,"followUpNeededCount":1,"unsetCount":2,"recordedCount":5,"conversionRate":0.4}}
              ]
            }
            """.trimIndent()
    }
}
