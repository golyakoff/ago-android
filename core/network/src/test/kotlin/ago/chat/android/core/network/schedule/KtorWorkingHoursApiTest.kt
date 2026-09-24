package ago.chat.android.core.network.schedule

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.schedule.WorkingHoursChangeResult
import ago.chat.android.core.domain.schedule.WorkingHoursReconciliation
import ago.chat.android.core.domain.schedule.WorkingHoursResult
import ago.chat.android.core.domain.schedule.WorkingHoursRule
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `26-97`: the working-hours read and its two writes, driven through the real client configuration and
 * a `MockEngine` — the identical shape `KtorBookingsApiTest` already establishes.
 */
class KtorWorkingHoursApiTest {
    private val baseUrl = "https://calendar-api.example.invalid"

    @Test
    fun `rules are flattened across calendars, with the worker's own display name resolved`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    requestedUrl = request.url.toString()
                    respondJson(
                        """
                        {
                          "tenantName":"Barbershop","publicKey":"demo","allowedOrigins":[],
                          "calendars":[
                            {"calendarId":"cal1","name":"Main","timeZone":"Europe/Moscow","isPublished":true,
                             "workerIds":["w1"],
                             "workingHours":[
                               {"ruleId":"r1","workerId":"w1","dayOfWeek":1,"startsAt":"09:00","endsAt":"18:00"}
                             ]}
                          ],
                          "workers":[{"workerId":"w1","displayName":"Alex","isActive":true,"serviceIds":[]}],
                          "services":[],"workerQuota":2
                        }
                        """.trimIndent(),
                    )
                }

            val result = api.fetchWorkingHours()

            assertEquals(
                WorkingHoursResult.Loaded(
                    listOf(
                        WorkingHoursRule(
                            ruleId = "r1",
                            workerId = "w1",
                            workerName = "Alex",
                            calendarName = "Main",
                            dayOfWeek = 1,
                            startsAt = "09:00",
                            endsAt = "18:00",
                        ),
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/console/configuration", requestedUrl)
        }

    @Test
    fun `a rule naming a worker the roster no longer lists falls back to the id, never disappears`() =
        runTest {
            // A row nobody can see is a row nobody can correct - which is the defect this item fixes.
            val api =
                apiFor(baseUrl) {
                    respondJson(
                        """
                        {"calendars":[{"calendarId":"cal1","name":"Main",
                          "workingHours":[{"ruleId":"r1","workerId":"ghost","dayOfWeek":0,"startsAt":"10:00","endsAt":"11:00"}]}],
                         "workers":[]}
                        """.trimIndent(),
                    )
                }

            val loaded = api.fetchWorkingHours() as WorkingHoursResult.Loaded

            assertEquals("ghost", loaded.rules.single().workerName)
        }

    @Test
    fun `a deployment with no calendar base URL answers NotConfigured without making a request`() =
        runTest {
            var called = false
            val api =
                apiFor(null) {
                    called = true
                    respondJson("{}")
                }

            assertEquals(WorkingHoursResult.NotConfigured, api.fetchWorkingHours())
            assertTrue("The adapter must not reach the network at all.", !called)
        }

    @Test
    fun `a dropped connection is Transport, never a hostname on screen`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("Unable to resolve host \"calendar-api.example.invalid\"") }

            assertEquals(WorkingHoursResult.Failed(BookingsQueueFailure.Transport), api.fetchWorkingHours())
        }

    @Test
    fun `a 200 whose body is not the promised shape is a failure, not an empty list`() =
        runTest {
            val api = apiFor(baseUrl) { respondJson("""{"calendars":"nope"}""") }

            assertEquals(WorkingHoursResult.Failed(BookingsQueueFailure.Unexpected), api.fetchWorkingHours())
        }

    @Test
    fun `an update sends PUT with the three fields and nothing else`() =
        runTest {
            var method: HttpMethod? = null
            var url: String? = null
            var body: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    method = request.method
                    url = request.url.toString()
                    body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respondJson("""{"rule":null,"reconciliation":{"recutFrom":null,"alreadyCutDays":[],"liveBookingCount":0}}""")
                }

            api.updateWorkingHoursRule("r1", dayOfWeek = 3, startsAt = "10:00", endsAt = "19:00")

            assertEquals(HttpMethod.Put, method)
            assertEquals("$baseUrl/api/v1/console/working-hours/r1", url)
            // No calendarId and no workerId: a rule is corrected where it is, never moved.
            assertEquals("""{"dayOfWeek":3,"startsAt":"10:00","endsAt":"19:00"}""", body)
        }

    /**
     * `26-97`'s one hard constraint, at the wire: the reconciliation is what tells the operator which
     * already-cut days the correction did not reach. An adapter that read the `200` and threw the body
     * away would compile, return success, and leave an already-booked slot silently unreconciled.
     */
    @Test
    fun `the reconciliation comes back verbatim`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respondJson(
                        """
                        {"rule":{"ruleId":"r1","workerId":"w1","dayOfWeek":1,"startsAt":"09:00","endsAt":"19:00"},
                         "reconciliation":{"recutFrom":"2026-09-28",
                           "alreadyCutDays":["2026-09-28","2026-10-05"],"liveBookingCount":2}}
                        """.trimIndent(),
                    )
                }

            assertEquals(
                WorkingHoursChangeResult.Changed(
                    WorkingHoursReconciliation("2026-09-28", listOf("2026-09-28", "2026-10-05"), 2),
                ),
                api.updateWorkingHoursRule("r1", 1, "09:00", "19:00"),
            )
        }

    @Test
    fun `a delete sends DELETE and reads the same body`() =
        runTest {
            var method: HttpMethod? = null
            var url: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    method = request.method
                    url = request.url.toString()
                    respondJson(
                        """
                        {"rule":null,
                         "reconciliation":{"recutFrom":"2026-09-28","alreadyCutDays":["2026-09-28"],"liveBookingCount":0}}
                        """.trimIndent(),
                    )
                }

            val result = api.deleteWorkingHoursRule("r1")

            assertEquals(HttpMethod.Delete, method)
            assertEquals("$baseUrl/api/v1/console/working-hours/r1", url)
            assertEquals(
                WorkingHoursChangeResult.Changed(WorkingHoursReconciliation("2026-09-28", listOf("2026-09-28"), 0)),
                result,
            )
        }

    @Test
    fun `a refusal carrying a problem-details detail is shown verbatim`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """{"type":"about:blank","title":"Bad Request","status":400,
                            "detail":"Working hours must end after they start; got 22:00 .. 02:00."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(
                WorkingHoursChangeResult.Refused("Working hours must end after they start; got 22:00 .. 02:00."),
                api.updateWorkingHoursRule("r1", 5, "22:00", "02:00"),
            )
        }

    @Test
    fun `a bare non-2xx with no detail to show is a failure, never a fabricated sentence`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.NotFound) }

            assertEquals(
                WorkingHoursChangeResult.Failed(BookingsQueueFailure.Unexpected),
                api.deleteWorkingHoursRule("r1"),
            )
        }

    @Test
    fun `a 200 whose change body cannot be read is a failure, not a silent success`() =
        runTest {
            // The reconciliation is the whole point of the call: a response this adapter cannot read
            // is not "a change with nothing to report".
            val api = apiFor(baseUrl) { respondJson("""{"reconciliation":"gone"}""") }

            assertEquals(
                WorkingHoursChangeResult.Failed(BookingsQueueFailure.Unexpected),
                api.updateWorkingHoursRule("r1", 1, "09:00", "18:00"),
            )
        }

    private fun apiFor(
        calendarApiBaseUrl: String?,
        handler: MockRequestHandler,
    ): KtorWorkingHoursApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorWorkingHoursApi(client, calendarApiBaseUrl)
    }
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) =
    respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
