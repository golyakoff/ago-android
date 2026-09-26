package ago.chat.android.core.network.readiness

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.readiness.BookingPrecondition
import ago.chat.android.core.domain.readiness.BookingReadinessResult
import ago.chat.android.core.domain.readiness.CalendarReadiness
import ago.chat.android.core.domain.readiness.PreconditionState
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
 * `26-163`: [KtorBookingReadinessApi]'s one read, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorWorkersApiTest` already establishes.
 */
class KtorBookingReadinessApiTest {
    private val baseUrl = "https://calendar-api.example.invalid"

    @Test
    fun `a loaded answer carries every calendar and its six preconditions through, in server order`() =
        runTest {
            var url: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    url = request.url.toString()
                    respondJson(
                        """
                        [
                          {"calendarId":"cal1","calendarName":"Main","isBookable":true,
                           "preconditions":[
                             {"precondition":"WorkerOnCalendar","isMet":true},
                             {"precondition":"ServiceOffered","isMet":true},
                             {"precondition":"WorkingHoursConfigured","isMet":true},
                             {"precondition":"ScheduleSaved","isMet":true},
                             {"precondition":"SlotsMaterialized","isMet":true},
                             {"precondition":"CalendarPublished","isMet":true}
                           ]}
                        ]
                        """.trimIndent(),
                    )
                }

            val result = api.fetchReadiness()

            assertEquals(
                BookingReadinessResult.Loaded(
                    listOf(
                        CalendarReadiness(
                            calendarId = "cal1",
                            calendarName = "Main",
                            isBookable = true,
                            preconditions =
                                listOf(
                                    PreconditionState(BookingPrecondition.WorkerOnCalendar, "WorkerOnCalendar", true),
                                    PreconditionState(BookingPrecondition.ServiceOffered, "ServiceOffered", true),
                                    PreconditionState(
                                        BookingPrecondition.WorkingHoursConfigured,
                                        "WorkingHoursConfigured",
                                        true,
                                    ),
                                    PreconditionState(BookingPrecondition.ScheduleSaved, "ScheduleSaved", true),
                                    PreconditionState(
                                        BookingPrecondition.SlotsMaterialized,
                                        "SlotsMaterialized",
                                        true,
                                    ),
                                    PreconditionState(
                                        BookingPrecondition.CalendarPublished,
                                        "CalendarPublished",
                                        true,
                                    ),
                                ),
                        ),
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/console/booking-readiness", url)
        }

    /** `GetBookingReadinessHandler`'s own synthetic placeholder for a tenant with no calendar at all — a
     * single entry, `calendarId`/`calendarName` both `null`, every precondition unmet. */
    @Test
    fun `a tenant with no calendar comes back as the one synthetic entry, everything unmet`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respondJson(
                        """
                        [{"calendarId":null,"calendarName":null,"isBookable":false,
                          "preconditions":[
                            {"precondition":"WorkerOnCalendar","isMet":false},
                            {"precondition":"ServiceOffered","isMet":false},
                            {"precondition":"WorkingHoursConfigured","isMet":false},
                            {"precondition":"ScheduleSaved","isMet":false},
                            {"precondition":"SlotsMaterialized","isMet":false},
                            {"precondition":"CalendarPublished","isMet":false}
                          ]}]
                        """.trimIndent(),
                    )
                }

            val loaded = api.fetchReadiness() as BookingReadinessResult.Loaded

            val calendar = loaded.calendars.single()
            assertEquals(null, calendar.calendarId)
            assertEquals(null, calendar.calendarName)
            assertEquals(false, calendar.isBookable)
            assertTrue(calendar.preconditions.all { !it.isMet })
        }

    /** An unrecognised wire spelling becomes [BookingPrecondition.Unknown] rather than dropped or
     * crashing the parse — [PreconditionState.rawPrecondition] still carries it verbatim. */
    @Test
    fun `an unrecognised precondition spelling becomes Unknown, raw spelling kept`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respondJson(
                        """
                        [{"calendarId":"cal1","calendarName":"Main","isBookable":false,
                          "preconditions":[{"precondition":"SomeFuturePrecondition","isMet":false}]}]
                        """.trimIndent(),
                    )
                }

            val loaded = api.fetchReadiness() as BookingReadinessResult.Loaded

            val calendar = loaded.calendars.single()
            val row = calendar.preconditions.single()
            assertEquals(BookingPrecondition.Unknown, row.precondition)
            assertEquals("SomeFuturePrecondition", row.rawPrecondition)
            assertEquals(false, row.isMet)
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

            assertEquals(BookingReadinessResult.NotConfigured, api.fetchReadiness())
            assertTrue("The adapter must not reach the network at all.", !called)
        }

    @Test
    fun `a dropped connection is Transport, never a hostname on screen`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("Unable to resolve host \"calendar-api.example.invalid\"") }

            assertEquals(BookingReadinessResult.Failed(BookingsQueueFailure.Transport), api.fetchReadiness())
        }

    @Test
    fun `a non-2xx is a failure`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(BookingReadinessResult.Failed(BookingsQueueFailure.Unexpected), api.fetchReadiness())
        }

    @Test
    fun `a 200 whose body is not the promised shape is a failure, not an empty list`() =
        runTest {
            val api = apiFor(baseUrl) { respondJson("""{"nope":true}""") }

            assertEquals(BookingReadinessResult.Failed(BookingsQueueFailure.Unexpected), api.fetchReadiness())
        }

    private fun apiFor(
        calendarApiBaseUrl: String?,
        handler: MockRequestHandler,
    ): KtorBookingReadinessApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorBookingReadinessApi(client, calendarApiBaseUrl)
    }
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) =
    respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
