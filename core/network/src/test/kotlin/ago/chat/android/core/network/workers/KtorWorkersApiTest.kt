package ago.chat.android.core.network.workers

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfiguredService
import ago.chat.android.core.domain.workers.Worker
import ago.chat.android.core.domain.workers.WorkerCalendar
import ago.chat.android.core.domain.workers.WorkerDetailResult
import ago.chat.android.core.domain.workers.WorkerDraft
import ago.chat.android.core.domain.workers.WorkersResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `26-139`: the worker dictionary's reads and its three writes, driven through the real client
 * configuration and a `MockEngine` — the identical shape `KtorBookingsApiTest`/`KtorWorkingHoursApiTest`
 * already establish.
 */
class KtorWorkersApiTest {
    private val baseUrl = "https://calendar-api.example.invalid"

    @Test
    fun `the roster is stitched to the configuration - calendar resolved, picker and checkboxes returned`() =
        runTest {
            val requestedPaths = mutableListOf<String>()
            val api =
                apiFor(baseUrl) { request ->
                    requestedPaths += request.url.encodedPath
                    when (request.url.encodedPath) {
                        "/api/v1/console/workers" ->
                            respondJson(
                                """
                                [
                                  {"workerId":"w1","lastName":"Ivanov","firstName":"Ivan","middleName":"Ivanovich",
                                   "displayName":"Ivan I.","displayNameIsCustom":false,"isActive":true,
                                   "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z",
                                   "serviceIds":["s1","s2"]},
                                  {"workerId":"w2","lastName":"Petrov","firstName":"Petr","middleName":null,
                                   "displayName":"Petr P.","displayNameIsCustom":true,"isActive":false,
                                   "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z",
                                   "serviceIds":[]}
                                ]
                                """.trimIndent(),
                            )
                        "/api/v1/console/configuration" ->
                            respondJson(
                                """
                                {
                                  "tenantName":"Barbershop","publicKey":"demo","allowedOrigins":[],
                                  "calendars":[
                                    {"calendarId":"cal1","name":"Main","timeZone":"Europe/Moscow","isPublished":true,
                                     "workerIds":["w1","w2"],"workingHours":[]}
                                  ],
                                  "workers":[],
                                  "services":[
                                    {"serviceId":"s1","name":"Haircut","durationMinutes":30,"priceMinorUnits":150000,
                                     "priceCurrencyCode":"RUB","priceIsFrom":false,"description":null,"isActive":true}
                                  ],
                                  "workerQuota":2
                                }
                                """.trimIndent(),
                            )
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }

            val result = api.fetchWorkers()

            assertEquals(
                WorkersResult.Loaded(
                    workers =
                        listOf(
                            Worker(
                                workerId = "w1",
                                lastName = "Ivanov",
                                firstName = "Ivan",
                                middleName = "Ivanovich",
                                displayName = "Ivan I.",
                                isActive = true,
                                serviceIds = listOf("s1", "s2"),
                                calendarId = "cal1",
                            ),
                            Worker(
                                workerId = "w2",
                                lastName = "Petrov",
                                firstName = "Petr",
                                middleName = null,
                                displayName = "Petr P.",
                                isActive = false,
                                serviceIds = emptyList(),
                                calendarId = "cal1",
                            ),
                        ),
                    calendars = listOf(WorkerCalendar("cal1", "Main")),
                    services =
                        listOf(
                            ConfiguredService(
                                serviceId = "s1",
                                name = "Haircut",
                                durationMinutes = 30,
                                priceMinorUnits = 150000,
                                priceCurrencyCode = "RUB",
                                priceIsFrom = false,
                                description = null,
                                isActive = true,
                            ),
                        ),
                ),
                result,
            )
            assertEquals(
                listOf("/api/v1/console/workers", "/api/v1/console/configuration"),
                requestedPaths,
            )
        }

    @Test
    fun `a worker no calendar lists keeps a null calendar, never disappears`() =
        runTest {
            // A row nobody can see is a row nobody can correct - the same resilience the working-hours read has.
            val api =
                apiFor(baseUrl) { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/console/workers" ->
                            respondJson(
                                """
                                [{"workerId":"w9","lastName":"Orphan","firstName":"O","middleName":null,
                                  "displayName":"Orphan O.","displayNameIsCustom":false,"isActive":true,
                                  "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","serviceIds":[]}]
                                """.trimIndent(),
                            )
                        else -> respondJson("""{"calendars":[],"services":[]}""")
                    }
                }

            val loaded = api.fetchWorkers() as WorkersResult.Loaded

            assertNull(loaded.workers.single().calendarId)
        }

    @Test
    fun `a deployment with no calendar base URL answers NotConfigured without making a request`() =
        runTest {
            var called = false
            val api =
                apiFor(null) {
                    called = true
                    respondJson("[]")
                }

            assertEquals(WorkersResult.NotConfigured, api.fetchWorkers())
            assertTrue("The adapter must not reach the network at all.", !called)
        }

    @Test
    fun `a dropped connection is Transport, never a hostname on screen`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("Unable to resolve host \"calendar-api.example.invalid\"") }

            assertEquals(WorkersResult.Failed(BookingsQueueFailure.Transport), api.fetchWorkers())
        }

    @Test
    fun `a non-2xx on the roster read is a failure`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.InternalServerError) }

            assertEquals(WorkersResult.Failed(BookingsQueueFailure.Unexpected), api.fetchWorkers())
        }

    @Test
    fun `a 200 whose roster body is not the promised shape is a failure, not an empty list`() =
        runTest {
            val api =
                apiFor(baseUrl) { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/console/workers" -> respondJson("""{"nope":true}""")
                        else -> respondJson("""{"calendars":[],"services":[]}""")
                    }
                }

            assertEquals(WorkersResult.Failed(BookingsQueueFailure.Unexpected), api.fetchWorkers())
        }

    @Test
    fun `a single worker read comes back with a null calendar`() =
        runTest {
            var url: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    url = request.url.toString()
                    respondJson(
                        """
                        {"workerId":"w1","lastName":"Ivanov","firstName":"Ivan","middleName":"Ivanovich",
                         "displayName":"Ivan I.","displayNameIsCustom":false,"isActive":true,
                         "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z","serviceIds":["s1"]}
                        """.trimIndent(),
                    )
                }

            val result = api.fetchWorker("w1")

            assertEquals(
                WorkerDetailResult.Loaded(
                    Worker(
                        workerId = "w1",
                        lastName = "Ivanov",
                        firstName = "Ivan",
                        middleName = "Ivanovich",
                        displayName = "Ivan I.",
                        isActive = true,
                        serviceIds = listOf("s1"),
                        calendarId = null,
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/console/workers/w1", url)
        }

    @Test
    fun `a create sends POST with the calendar and no active flag`() =
        runTest {
            var method: HttpMethod? = null
            var url: String? = null
            var body: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    method = request.method
                    url = request.url.toString()
                    body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"workerId":"w-new"}""",
                        HttpStatusCode.Created,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result =
                api.createWorker(
                    WorkerDraft(
                        lastName = "Ivanov",
                        firstName = "Ivan",
                        middleName = "Ivanovich",
                        displayName = "Ivan I.",
                        calendarId = "cal1",
                        // isActive is deliberately not sent on create - the body assertion below proves it.
                        isActive = true,
                        serviceIds = listOf("s1", "s2"),
                    ),
                )

            assertEquals(BookingActionResult.Succeeded, result)
            assertEquals(HttpMethod.Post, method)
            assertEquals("$baseUrl/api/v1/console/workers", url)
            assertEquals(
                """{"lastName":"Ivanov","firstName":"Ivan","middleName":"Ivanovich",""" +
                    """"displayName":"Ivan I.","calendarId":"cal1","serviceIds":["s1","s2"]}""",
                body,
            )
        }

    @Test
    fun `an update sends PUT with the active flag and no calendar`() =
        runTest {
            var method: HttpMethod? = null
            var url: String? = null
            var body: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    method = request.method
                    url = request.url.toString()
                    body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond("", HttpStatusCode.NoContent)
                }

            val result =
                api.updateWorker(
                    "w1",
                    WorkerDraft(
                        lastName = "Petrov",
                        firstName = "Petr",
                        middleName = "Petrovich",
                        displayName = "Petr P.",
                        // calendarId is deliberately not sent on update - the body assertion below proves it.
                        calendarId = "cal1",
                        isActive = false,
                        serviceIds = listOf("s1"),
                    ),
                )

            assertEquals(BookingActionResult.Succeeded, result)
            assertEquals(HttpMethod.Put, method)
            assertEquals("$baseUrl/api/v1/console/workers/w1", url)
            assertEquals(
                """{"lastName":"Petrov","firstName":"Petr","middleName":"Petrovich",""" +
                    """"displayName":"Petr P.","isActive":false,"serviceIds":["s1"]}""",
                body,
            )
        }

    /**
     * `26-139`'s one hard constraint, at the wire: a worker who was ever booked cannot be deleted, and
     * the server's own sentence saying so - and saying to deactivate instead - must reach the operator
     * verbatim, not as a generic "something went wrong".
     */
    @Test
    fun `a delete refused because the worker was booked shows the server detail verbatim`() =
        runTest {
            var method: HttpMethod? = null
            var url: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    method = request.method
                    url = request.url.toString()
                    respond(
                        """{"type":"about:blank","title":"Conflict","status":409,
                            "detail":"Worker w1 has a booking that is pending, confirmed, or a recorded no-show, and cannot be deleted. Deactivate him instead."}""",
                        HttpStatusCode.Conflict,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.deleteWorker("w1")

            assertEquals(
                BookingActionResult.Refused(
                    "Worker w1 has a booking that is pending, confirmed, or a recorded no-show, " +
                        "and cannot be deleted. Deactivate him instead.",
                ),
                result,
            )
            assertEquals(HttpMethod.Delete, method)
            assertEquals("$baseUrl/api/v1/console/workers/w1", url)
        }

    @Test
    fun `a delete with a bare non-2xx and no detail is a failure, never a fabricated sentence`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.NotFound) }

            assertEquals(
                BookingActionResult.Failed(BookingsQueueFailure.Unexpected),
                api.deleteWorker("w1"),
            )
        }

    @Test
    fun `a write whose connection drops is a transport failure`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("boom") }

            assertEquals(
                BookingActionResult.Failed(BookingsQueueFailure.Transport),
                api.deleteWorker("w1"),
            )
        }

    private fun apiFor(
        calendarApiBaseUrl: String?,
        handler: MockRequestHandler,
    ): KtorWorkersApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorWorkersApi(client, calendarApiBaseUrl)
    }
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) =
    respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
