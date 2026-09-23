package ago.chat.android.core.network.analytics

import ago.chat.android.core.domain.analytics.AnalyticsBucket
import ago.chat.android.core.domain.analytics.ConversionBucket
import ago.chat.android.core.domain.analytics.OperatorLoadSummary
import ago.chat.android.core.domain.analytics.OwnAnalytics
import ago.chat.android.core.domain.analytics.OwnAnalyticsFailure
import ago.chat.android.core.domain.analytics.OwnAnalyticsResult
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `26-57`: the own-numbers read, driven through the real client configuration and a `MockEngine` — the
 * identical shape `KtorBookingsApiTest`/`KtorConversationsApiTest` already establish.
 */
class KtorOwnAnalyticsApiTest {
    private val baseUrl = "https://api.reserve-me.ru"

    @Test
    fun `a loaded response carries the server's own range and every section`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """
                        {
                          "from":"2026-08-23T00:00:00Z","to":"2026-09-22T00:00:00Z",
                          "bucket":{"conversationCount":12,"averageFirstResponseSeconds":45.0,"averageDurationSeconds":600.0,"missedCount":1},
                          "load":{"conversationsHeld":10,"intervalsHeld":11,"standardIntervals":9,"additionalIntervals":2},
                          "conversion":{"convertedCount":3,"notConvertedCount":2,"followUpNeededCount":1,"unsetCount":4,"recordedCount":6,"conversionRate":0.5}
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.fetchOwnAnalytics(from = null, to = null)

            assertEquals(
                OwnAnalyticsResult.Loaded(
                    OwnAnalytics(
                        from = "2026-08-23T00:00:00Z",
                        to = "2026-09-22T00:00:00Z",
                        bucket = AnalyticsBucket(12, 45.0, 600.0, 1),
                        load = OperatorLoadSummary(10, 11, 9, 2),
                        conversion = ConversionBucket(3, 2, 1, 4, 6, 0.5),
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/conversations/analytics/me", requestedUrl)
        }

    @Test
    fun `an absent load and conversion stay absent, never a manufactured zero`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {
                          "from":"2026-08-23T00:00:00Z","to":"2026-09-22T00:00:00Z",
                          "bucket":{"conversationCount":0,"averageFirstResponseSeconds":null,"averageDurationSeconds":null,"missedCount":0}
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.fetchOwnAnalytics(from = null, to = null)

            assertEquals(
                OwnAnalyticsResult.Loaded(
                    OwnAnalytics(
                        from = "2026-08-23T00:00:00Z",
                        to = "2026-09-22T00:00:00Z",
                        bucket = AnalyticsBucket(0, null, null, 0),
                        load = null,
                        conversion = null,
                    ),
                ),
                result,
            )
        }

    @Test
    fun `both bounds are sent only when both are given`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """{"from":"2026-09-01T00:00:00Z","to":"2026-09-02T00:00:00Z","bucket":{"conversationCount":0,"averageFirstResponseSeconds":null,"averageDurationSeconds":null,"missedCount":0}}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            api.fetchOwnAnalytics(from = "2026-09-01T00:00:00Z", to = "2026-09-02T00:00:00Z")

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
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(OwnAnalyticsResult.InvalidRange, api.fetchOwnAnalytics(from = "bad", to = "range"))
        }

    @Test
    fun `a different 400 is an ordinary refusal, not InvalidRange`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"Conversation.Forbidden","detail":"nope"}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(OwnAnalyticsResult.Failed(OwnAnalyticsFailure.Unexpected), api.fetchOwnAnalytics(null, null))
        }

    @Test
    fun `a 5xx is Unexpected, not an empty report`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(OwnAnalyticsResult.Failed(OwnAnalyticsFailure.Unexpected), api.fetchOwnAnalytics(null, null))
        }

    @Test
    fun `a dropped connection is Transport, not an empty report`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(OwnAnalyticsResult.Failed(OwnAnalyticsFailure.Transport), api.fetchOwnAnalytics(null, null))
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never an empty report`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"somethingElseEntirely":true}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(OwnAnalyticsResult.Failed(OwnAnalyticsFailure.Unexpected), api.fetchOwnAnalytics(null, null))
        }

    private fun apiFor(handler: MockRequestHandler): KtorOwnAnalyticsApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorOwnAnalyticsApi(client, baseUrl)
    }
}
