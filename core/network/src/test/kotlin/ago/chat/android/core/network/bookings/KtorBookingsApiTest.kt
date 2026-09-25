package ago.chat.android.core.network.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmedBooking
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.core.domain.bookings.ContactsResult
import ago.chat.android.core.domain.bookings.PendingBooking
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import ago.chat.android.core.domain.bookings.PhoneReveal
import ago.chat.android.core.domain.bookings.PhoneRevealsResult
import ago.chat.android.core.domain.bookings.RevealPhoneResult
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
import io.ktor.http.content.OutgoingContent
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
                            "localDate":"2026-09-24","weekday":4,"originConversationId":"conv1"
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
                            // `26-121`: the origin conversation now decodes from the wire.
                            originConversationId = "conv1",
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

    @Test
    fun `contacts are read in the order the server sent them`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """
                        [
                          {
                            "customerId":"c1","phone":"+7***5678","masked":true,"displayName":"Анна",
                            "noShowCount":2,"phoneVerifiedAt":"2026-09-01T10:00:00Z",
                            "phoneConfirmedByOperatorAt":null
                          }
                        ]
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.fetchContacts()

            assertEquals(
                ContactsResult.Loaded(
                    listOf(
                        Contact(
                            customerId = "c1",
                            phone = "+7***5678",
                            masked = true,
                            displayName = "Анна",
                            noShowCount = 2,
                            phoneVerifiedAt = "2026-09-01T10:00:00Z",
                            phoneConfirmedByOperatorAt = null,
                        ),
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/console/contacts", requestedUrl)
        }

    @Test
    fun `an unknown field on the contacts wire does not break the read`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """
                        [
                          {
                            "customerId":"c1","phone":"+7***5678","masked":true,"displayName":null,
                            "noShowCount":0,"phoneVerifiedAt":null,"phoneConfirmedByOperatorAt":null,
                            "notes":"important","firstSeenAt":"2026-01-01T00:00:00Z",
                            "lastSeenAt":"2026-01-01T00:00:00Z","duplicatePhoneCustomerIds":["c2"]
                          }
                        ]
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertTrue(api.fetchContacts() is ContactsResult.Loaded)
        }

    @Test
    fun `contacts - a 5xx is Unexpected, not an empty list`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(ContactsResult.Failed(BookingsQueueFailure.Unexpected), api.fetchContacts())
        }

    @Test
    fun `contacts - a dropped connection is Transport, not an empty list`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("unexpected end of stream") }

            assertEquals(ContactsResult.Failed(BookingsQueueFailure.Transport), api.fetchContacts())
        }

    @Test
    fun `contacts - no calendar base URL configured is NotConfigured, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(ContactsResult.NotConfigured, api.fetchContacts())
            assertEquals("a null base URL must never reach the network", 0, calls)
        }

    @Test
    fun `a 204 rejects the booking, against the right path`() =
        runTest {
            var requestedUrl: String? = null
            var requestedMethod: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    requestedUrl = request.url.toString()
                    requestedMethod = request.method.value
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(BookingActionResult.Succeeded, api.rejectBooking("b1"))
            assertEquals("$baseUrl/api/v1/console/bookings/b1/reject", requestedUrl)
            assertEquals("POST", requestedMethod)
        }

    @Test
    fun `a 204 cancels the booking, against the right path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    requestedUrl = request.url.toString()
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(BookingActionResult.Succeeded, api.cancelBooking("b1"))
            assertEquals("$baseUrl/api/v1/console/bookings/b1/cancel", requestedUrl)
        }

    @Test
    fun `a 204 marks the booking a no-show, against the right path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    requestedUrl = request.url.toString()
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(BookingActionResult.Succeeded, api.markNoShow("b1"))
            assertEquals("$baseUrl/api/v1/console/bookings/b1/no-show", requestedUrl)
        }

    @Test
    fun `a 409 with a problem-details body is rendered as that exact refusal, not retried`() =
        runTest {
            var calls = 0
            val api =
                apiFor(baseUrl) {
                    calls++
                    respond(
                        """{"type":"Booking.InvalidState","detail":"Запись уже подтверждена."}""",
                        HttpStatusCode.Conflict,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            val result = api.rejectBooking("b1")

            assertEquals(BookingActionResult.Refused("Запись уже подтверждена."), result)
            assertEquals("the client makes exactly one attempt - a refusal is never retried into a success", 1, calls)
        }

    @Test
    fun `a refusal with no problem-details body classifies as Unexpected, never a fabricated detail`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(BookingActionResult.Failed(BookingsQueueFailure.Unexpected), api.cancelBooking("b1"))
        }

    @Test
    fun `a dropped connection on a booking action is Transport, not a silently retried write`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("unexpected end of stream") }

            assertEquals(BookingActionResult.Failed(BookingsQueueFailure.Transport), api.markNoShow("b1"))
        }

    @Test
    fun `no calendar base URL configured fails a booking action, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(BookingActionResult.Failed(BookingsQueueFailure.Unexpected), api.rejectBooking("b1"))
            assertEquals("a null base URL must never reach the network", 0, calls)
        }

    @Test
    fun `revealing a phone posts the surface and returns the unmasked number`() =
        runTest {
            var requestedUrl: String? = null
            var requestedBody: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    requestedUrl = request.url.toString()
                    requestedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"phone":"+79991234567"}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.revealCustomerPhone("c1", "AndroidContacts")

            assertEquals(RevealPhoneResult.Revealed("+79991234567"), result)
            assertEquals("$baseUrl/api/v1/console/contacts/c1/reveal-phone", requestedUrl)
            assertEquals("""{"surface":"AndroidContacts"}""", requestedBody)
        }

    @Test
    fun `a 403 with a problem-details body on reveal is rendered as that exact refusal`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """{"type":"Customer.NotEntitled","detail":"Недостаточно прав для просмотра номера."}""",
                        HttpStatusCode.Forbidden,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            val result = api.revealCustomerPhone("c1", "AndroidContacts")

            assertEquals(RevealPhoneResult.Refused("Недостаточно прав для просмотра номера."), result)
        }

    @Test
    fun `a reveal refusal with no problem-details body classifies as Unexpected`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(RevealPhoneResult.Failed(BookingsQueueFailure.Unexpected), api.revealCustomerPhone("c1", "AndroidContacts"))
        }

    @Test
    fun `a dropped connection on reveal is Transport`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("unexpected end of stream") }

            assertEquals(RevealPhoneResult.Failed(BookingsQueueFailure.Transport), api.revealCustomerPhone("c1", "AndroidContacts"))
        }

    @Test
    fun `a 200 that dropped the shape on reveal is Unexpected, never a fabricated number`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """{"somethingElseEntirely":true}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(RevealPhoneResult.Failed(BookingsQueueFailure.Unexpected), api.revealCustomerPhone("c1", "AndroidContacts"))
        }

    @Test
    fun `no calendar base URL configured fails a reveal, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(RevealPhoneResult.Failed(BookingsQueueFailure.Unexpected), api.revealCustomerPhone("c1", "AndroidContacts"))
            assertEquals("a null base URL must never reach the network", 0, calls)
        }

    @Test
    fun `phone reveals are read for the first page, newest first as the server sent them, with no query string`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """
                        {
                          "items": [
                            {"id":"r2","occurredAt":"2026-09-24T12:00:00Z","customerId":"c1","operatorId":"op1","surface":"AndroidContacts"},
                            {"id":"r1","occurredAt":"2026-09-23T09:00:00Z","customerId":"c2","operatorId":"op2","surface":"ConsoleQueue"}
                          ],
                          "nextBefore": "r1"
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.fetchPhoneReveals(before = null, limit = null)

            assertEquals(
                PhoneRevealsResult.Loaded(
                    reveals =
                        listOf(
                            PhoneReveal(
                                id = "r2",
                                occurredAt = "2026-09-24T12:00:00Z",
                                customerId = "c1",
                                operatorId = "op1",
                                surface = "AndroidContacts",
                            ),
                            PhoneReveal(
                                id = "r1",
                                occurredAt = "2026-09-23T09:00:00Z",
                                customerId = "c2",
                                operatorId = "op2",
                                surface = "ConsoleQueue",
                            ),
                        ),
                    nextBefore = "r1",
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/console/contacts/phone-reveals", requestedUrl)
        }

    @Test
    fun `phone reveals - a later page sends the cursor and limit as query parameters`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """{"items":[],"nextBefore":null}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.fetchPhoneReveals(before = "r1", limit = 50)

            assertEquals(PhoneRevealsResult.Loaded(emptyList(), null), result)
            assertEquals("$baseUrl/api/v1/console/contacts/phone-reveals?before=r1&limit=50", requestedUrl)
        }

    @Test
    fun `phone reveals - the oldest row reached is a null nextBefore, never a cursor pointing nowhere`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """{"items":[],"nextBefore":null}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(PhoneRevealsResult.Loaded(emptyList(), null), api.fetchPhoneReveals(before = null, limit = null))
        }

    @Test
    fun `phone reveals - a 5xx is Unexpected, not an empty trail`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(
                PhoneRevealsResult.Failed(BookingsQueueFailure.Unexpected),
                api.fetchPhoneReveals(before = null, limit = null),
            )
        }

    @Test
    fun `phone reveals - a dropped connection is Transport, not an empty trail`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("unexpected end of stream") }

            assertEquals(
                PhoneRevealsResult.Failed(BookingsQueueFailure.Transport),
                api.fetchPhoneReveals(before = null, limit = null),
            )
        }

    @Test
    fun `phone reveals - a 200 that dropped the shape is Unexpected, never an empty trail`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """{"somethingElseEntirely":true}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(
                PhoneRevealsResult.Failed(BookingsQueueFailure.Unexpected),
                api.fetchPhoneReveals(before = null, limit = null),
            )
        }

    @Test
    fun `phone reveals - no calendar base URL configured is NotConfigured, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(PhoneRevealsResult.NotConfigured, api.fetchPhoneReveals(before = null, limit = null))
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
