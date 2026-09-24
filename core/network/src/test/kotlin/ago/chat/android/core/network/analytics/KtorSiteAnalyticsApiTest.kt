package ago.chat.android.core.network.analytics

import ago.chat.android.core.domain.analytics.AnalyticsBucket
import ago.chat.android.core.domain.analytics.CampaignBreakdownRow
import ago.chat.android.core.domain.analytics.ChannelBreakdownRow
import ago.chat.android.core.domain.analytics.OperatorBreakdownRow
import ago.chat.android.core.domain.analytics.OperatorLoadSummary
import ago.chat.android.core.domain.analytics.ReferrerBreakdownRow
import ago.chat.android.core.domain.analytics.SiteAnalyticsFailure
import ago.chat.android.core.domain.analytics.SiteAnalyticsResult
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
 * `26-70`: the site report's read, driven through the real client configuration and a `MockEngine` —
 * the identical shape `KtorOwnAnalyticsApiTest`/`KtorConversationsApiTest` already establish.
 */
class KtorSiteAnalyticsApiTest {
    private val baseUrl = "https://api.example.invalid"

    @Test
    fun `a loaded response carries the server's own range, the comparison and all four breakdowns`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor { request ->
                    requestedUrl = request.url.toString()
                    respond(FULL_BODY, HttpStatusCode.OK, jsonHeaders)
                }

            val result = api.fetchSiteAnalytics(from = null, to = null)

            val loaded = result as SiteAnalyticsResult.Loaded
            assertEquals("2026-08-23T00:00:00Z", loaded.analytics.from)
            assertEquals("2026-09-22T00:00:00Z", loaded.analytics.to)
            assertEquals(AnalyticsBucket(40, 45.0, 600.0, 3), loaded.analytics.overall)
            assertEquals("2026-07-24T00:00:00Z", loaded.analytics.previousFrom)
            assertEquals("2026-08-23T00:00:00Z", loaded.analytics.previousTo)
            assertEquals(AnalyticsBucket(32, 50.0, 700.0, 5), loaded.analytics.previousOverall)
            assertEquals(
                listOf(ChannelBreakdownRow("Widget", AnalyticsBucket(30, 45.0, 600.0, 2))),
                loaded.analytics.byChannel,
            )
            assertEquals(
                listOf(ReferrerBreakdownRow("Direct", AnalyticsBucket(20, 40.0, null, 1))),
                loaded.analytics.byReferrer,
            )
            assertEquals(
                listOf(CampaignBreakdownRow("spring-sale", AnalyticsBucket(4, 30.0, 300.0, 0))),
                loaded.analytics.byCampaign,
            )
            assertEquals("$baseUrl/api/v1/conversations/analytics", requestedUrl)
        }

    /** The three-way distinction this item exists to keep: a real `0`, a `null` average ("nothing to
     * average") and an absent `load` ("no assignment data at all") must arrive as three different
     * values, not as one. */
    @Test
    fun `an operator row keeps a real zero, a null average and an absent load apart`() =
        runTest {
            val api = apiFor { respond(FULL_BODY, HttpStatusCode.OK, jsonHeaders) }

            val loaded = api.fetchSiteAnalytics(null, null) as SiteAnalyticsResult.Loaded

            val withLoad = loaded.analytics.byOperator.first { it.operatorId == "op-with-load" }
            assertEquals(OperatorLoadSummary(10, 11, 11, 0), withLoad.load)
            assertEquals(0, withLoad.load!!.additionalIntervals)
            assertEquals("Вера", withLoad.operatorName)

            val withoutLoad = loaded.analytics.byOperator.first { it.operatorId == "op-without-load" }
            assertNull(withoutLoad.load)
            assertNull(withoutLoad.operatorName)
            assertEquals(0, withoutLoad.bucket.conversationCount)
            assertNull(withoutLoad.bucket.averageFirstResponseSeconds)
        }

    @Test
    fun `an omitted breakdown array is an empty table, never a parse failure`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {
                          "from":"2026-08-23T00:00:00Z","to":"2026-09-22T00:00:00Z",
                          "overall":{"conversationCount":0,"averageFirstResponseSeconds":null,"averageDurationSeconds":null,"missedCount":0},
                          "previousFrom":"2026-07-24T00:00:00Z","previousTo":"2026-08-23T00:00:00Z",
                          "previousOverall":{"conversationCount":0,"averageFirstResponseSeconds":null,"averageDurationSeconds":null,"missedCount":0}
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val loaded = api.fetchSiteAnalytics(null, null) as SiteAnalyticsResult.Loaded

            assertTrue(loaded.analytics.byChannel.isEmpty())
            assertTrue(loaded.analytics.byOperator.isEmpty())
            assertTrue(loaded.analytics.byReferrer.isEmpty())
            assertTrue(loaded.analytics.byCampaign.isEmpty())
        }

    /** `byLoad` is out of this item's scope and has no field on this app's own model — an extra wire key
     * must be dropped, not rejected (`agoJson`'s own `ignoreUnknownKeys`). */
    @Test
    fun `a load carrying the byLoad cross-tab this wave does not render still parses`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {
                          "from":"2026-08-23T00:00:00Z","to":"2026-09-22T00:00:00Z",
                          "overall":{"conversationCount":1,"averageFirstResponseSeconds":null,"averageDurationSeconds":null,"missedCount":0},
                          "previousFrom":"2026-07-24T00:00:00Z","previousTo":"2026-08-23T00:00:00Z",
                          "previousOverall":{"conversationCount":0,"averageFirstResponseSeconds":null,"averageDurationSeconds":null,"missedCount":0},
                          "byOperator":[{"operatorId":"op-1","bucket":{"conversationCount":1,"averageFirstResponseSeconds":null,"averageDurationSeconds":null,"missedCount":0},
                            "load":{"conversationsHeld":1,"intervalsHeld":1,"standardIntervals":1,"additionalIntervals":0,
                              "byLoad":[{"bucketLabel":"1","intervalCount":1,"replyCount":1,"averageFirstReplySeconds":12.0}]}}]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val loaded = api.fetchSiteAnalytics(null, null) as SiteAnalyticsResult.Loaded

            assertEquals(
                OperatorBreakdownRow(
                    operatorId = "op-1",
                    operatorName = null,
                    bucket = AnalyticsBucket(1, null, null, 0),
                    load = OperatorLoadSummary(1, 1, 1, 0),
                ),
                loaded.analytics.byOperator.single(),
            )
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

            api.fetchSiteAnalytics(from = null, to = null)

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

            api.fetchSiteAnalytics(from = "2026-09-01T00:00:00Z", to = "2026-09-02T00:00:00Z")

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

            assertEquals(SiteAnalyticsResult.InvalidRange, api.fetchSiteAnalytics(from = "bad", to = "range"))
        }

    /** A refusal is an ordinary failure here on purpose - this entry is only ever drawn for an operator
     * the app believes holds `site:configure`, so a `Conversation.Forbidden` is a stale answer, not a
     * message worth its own wording ([KtorSiteAnalyticsApi]'s own comment). */
    @Test
    fun `a forbidden refusal is an ordinary refusal, not InvalidRange`() =
        runTest {
            val api =
                apiFor {
                    respond("""{"type":"Conversation.Forbidden","detail":"nope"}""", HttpStatusCode.Forbidden, jsonHeaders)
                }

            assertEquals(SiteAnalyticsResult.Failed(SiteAnalyticsFailure.Unexpected), api.fetchSiteAnalytics(null, null))
        }

    @Test
    fun `a 5xx is Unexpected, not an empty report`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(SiteAnalyticsResult.Failed(SiteAnalyticsFailure.Unexpected), api.fetchSiteAnalytics(null, null))
        }

    @Test
    fun `a dropped connection is Transport, not an empty report`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(SiteAnalyticsResult.Failed(SiteAnalyticsFailure.Transport), api.fetchSiteAnalytics(null, null))
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never an empty report`() =
        runTest {
            val api = apiFor { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders) }

            assertEquals(SiteAnalyticsResult.Failed(SiteAnalyticsFailure.Unexpected), api.fetchSiteAnalytics(null, null))
        }

    private fun apiFor(handler: MockRequestHandler): KtorSiteAnalyticsApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorSiteAnalyticsApi(client, baseUrl)
    }

    private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())

    private companion object {
        /** One body every shape assertion above reads, so "what a real response looks like" is written
         * once rather than five slightly different times. */
        val FULL_BODY =
            """
            {
              "from":"2026-08-23T00:00:00Z","to":"2026-09-22T00:00:00Z",
              "overall":{"conversationCount":40,"averageFirstResponseSeconds":45.0,"averageDurationSeconds":600.0,"missedCount":3},
              "previousFrom":"2026-07-24T00:00:00Z","previousTo":"2026-08-23T00:00:00Z",
              "previousOverall":{"conversationCount":32,"averageFirstResponseSeconds":50.0,"averageDurationSeconds":700.0,"missedCount":5},
              "byChannel":[{"channel":"Widget","bucket":{"conversationCount":30,"averageFirstResponseSeconds":45.0,"averageDurationSeconds":600.0,"missedCount":2}}],
              "byOperator":[
                {"operatorId":"op-with-load","operatorName":"Вера",
                 "bucket":{"conversationCount":25,"averageFirstResponseSeconds":42.0,"averageDurationSeconds":580.0,"missedCount":1},
                 "load":{"conversationsHeld":10,"intervalsHeld":11,"standardIntervals":11,"additionalIntervals":0}},
                {"operatorId":"op-without-load",
                 "bucket":{"conversationCount":0,"averageFirstResponseSeconds":null,"averageDurationSeconds":null,"missedCount":0}}
              ],
              "byReferrer":[{"referrerHost":"Direct","bucket":{"conversationCount":20,"averageFirstResponseSeconds":40.0,"averageDurationSeconds":null,"missedCount":1}}],
              "byCampaign":[{"utmCampaign":"spring-sale","bucket":{"conversationCount":4,"averageFirstResponseSeconds":30.0,"averageDurationSeconds":300.0,"missedCount":0}}]
            }
            """.trimIndent()
    }
}
