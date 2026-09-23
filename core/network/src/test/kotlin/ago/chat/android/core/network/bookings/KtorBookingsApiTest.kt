package ago.chat.android.core.network.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmedBooking
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.PendingBooking
import ago.chat.android.core.domain.bookings.PendingBookingsResult
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
 * `26-48`: the pending-booking queue read, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorConversationsApiTest` already establishes.
 */
class KtorBookingsApiTest {
    private val baseUrl = "https://calendar-api.reserve-me.ru"

    @Test
    fun `the queue is read in the order the server sent it`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """
                        [
                          {
                            "bookingId":"b1","calendarId":"cal1","workerId":"w1","serviceId":"s1",
                            "startsAt":"2026-09-22T09:00:00Z","endsAt":"2026-09-22T09:30:00Z",
                            "confirmationDeadline":"2026-09-22T10:00:00Z"
                          }
                        ]
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.fetchPendingQueue()

            assertEquals(
                PendingBookingsResult.Loaded(
                    listOf(
                        PendingBooking(
                            bookingId = "b1",
                            calendarId = "cal1",
                            workerId = "w1",
                            serviceId = "s1",
                            startsAt = "2026-09-22T09:00:00Z",
                            endsAt = "2026-09-22T09:30:00Z",
                            confirmationDeadline = "2026-09-22T10:00:00Z",
                        ),
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/console/pending-bookings", requestedUrl)
        }

    @Test
    fun `an unknown field on the wire does not break the read`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """
                        [
                          {
                            "bookingId":"b1","calendarId":"cal1","workerId":"w1","serviceId":"s1",
                            "customerId":"c1","localDate":"2026-09-22","isOverdue":false,"phone":null,"masked":false,
                            "startsAt":"2026-09-22T09:00:00Z","endsAt":"2026-09-22T09:30:00Z",
                            "confirmationDeadline":"2026-09-22T10:00:00Z"
                          }
                        ]
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertTrue(api.fetchPendingQueue() is PendingBookingsResult.Loaded)
        }

    @Test
    fun `a 5xx is Unexpected, not an empty queue`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(PendingBookingsResult.Failed(BookingsQueueFailure.Unexpected), api.fetchPendingQueue())
        }

    @Test
    fun `a dropped connection is Transport, not an empty queue`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("unexpected end of stream") }

            assertEquals(PendingBookingsResult.Failed(BookingsQueueFailure.Transport), api.fetchPendingQueue())
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never an empty queue`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """{"somethingElseEntirely":true}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(PendingBookingsResult.Failed(BookingsQueueFailure.Unexpected), api.fetchPendingQueue())
        }

    @Test
    fun `no calendar base URL configured is NotConfigured, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(PendingBookingsResult.NotConfigured, api.fetchPendingQueue())
            assertEquals("a null base URL must never reach the network", 0, calls)
        }

    @Test
    fun `confirmed bookings are read for the given range, in the order the server sent them`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """
                        [
                          {
                            "bookingId":"b1","calendarId":"cal1","workerId":"w1","workerDisplayName":"Ирина Соколова",
                            "serviceId":"s1","serviceName":"Стрижка","customerId":"c1","customerDisplayName":"Анна",
                            "startsAt":"2026-09-24T09:00:00Z","endsAt":"2026-09-24T09:30:00Z",
                            "localDate":"2026-09-24","weekday":4
                          }
                        ]
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.fetchConfirmedBookings(from = "2026-09-23", to = "2026-09-29")

            assertEquals(
                ConfirmedBookingsResult.Loaded(
                    listOf(
                        ConfirmedBooking(
                            bookingId = "b1",
                            calendarId = "cal1",
                            workerId = "w1",
                            workerDisplayName = "Ирина Соколова",
                            serviceId = "s1",
                            serviceName = "Стрижка",
                            customerId = "c1",
                            customerDisplayName = "Анна",
                            startsAt = "2026-09-24T09:00:00Z",
                            endsAt = "2026-09-24T09:30:00Z",
                            localDate = "2026-09-24",
                            weekday = 4,
                        ),
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/console/confirmed-bookings?from=2026-09-23&to=2026-09-29", requestedUrl)
        }

    @Test
    fun `an unknown field on the confirmed-bookings wire does not break the read`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """
                        [
                          {
                            "bookingId":"b1","calendarId":"cal1","workerId":"w1","workerDisplayName":"Ирина Соколова",
                            "serviceId":"s1","serviceName":null,"customerId":"c1","customerDisplayName":null,
                            "startsAt":"2026-09-24T09:00:00Z","endsAt":"2026-09-24T09:30:00Z",
                            "localDate":"2026-09-24","weekday":4,"phone":"+7***","masked":true
                          }
                        ]
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertTrue(api.fetchConfirmedBookings(from = "2026-09-23", to = "2026-09-29") is ConfirmedBookingsResult.Loaded)
        }

    @Test
    fun `confirmed bookings - a 5xx is Unexpected, not an empty range`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(
                ConfirmedBookingsResult.Failed(BookingsQueueFailure.Unexpected),
                api.fetchConfirmedBookings(from = "2026-09-23", to = "2026-09-29"),
            )
        }

    @Test
    fun `confirmed bookings - a dropped connection is Transport, not an empty range`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("unexpected end of stream") }

            assertEquals(
                ConfirmedBookingsResult.Failed(BookingsQueueFailure.Transport),
                api.fetchConfirmedBookings(from = "2026-09-23", to = "2026-09-29"),
            )
        }

    @Test
    fun `confirmed bookings - no calendar base URL configured is NotConfigured, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(ConfirmedBookingsResult.NotConfigured, api.fetchConfirmedBookings(from = "2026-09-23", to = "2026-09-29"))
            assertEquals("a null base URL must never reach the network", 0, calls)
        }

    private fun apiFor(
        calendarApiBaseUrl: String?,
        handler: MockRequestHandler,
    ): KtorBookingsApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorBookingsApi(client, calendarApiBaseUrl)
    }
}
