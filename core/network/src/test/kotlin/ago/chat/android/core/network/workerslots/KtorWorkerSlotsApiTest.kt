package ago.chat.android.core.network.workerslots

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.workerslots.WorkerSlot
import ago.chat.android.core.domain.workerslots.WorkerSlotStatus
import ago.chat.android.core.domain.workerslots.WorkerSlotsResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `26-168`: the materialised slot view's one read, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorWorkersApiTest`/`KtorBookingReadinessApiTest` already
 * establish.
 */
class KtorWorkerSlotsApiTest {
    private val baseUrl = "https://calendar-api.example.invalid"

    @Test
    fun `every slot field round-trips, including a booking group and a masked phone`() =
        runTest {
            var url: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    url = request.url.toString()
                    respondJson(
                        """
                        [
                          {"eventId":"e1","localDate":"2026-09-28","weekday":1,
                           "startsAt":"2026-09-28T09:00:00+03:00","endsAt":"2026-09-28T09:30:00+03:00",
                           "status":"Booked","serviceId":"s1","serviceName":"Haircut","personId":"p1",
                           "phone":"+7***1234","masked":true,"bookingId":"b1"},
                          {"eventId":"e2","localDate":"2026-09-28","weekday":1,
                           "startsAt":"2026-09-28T09:30:00+03:00","endsAt":"2026-09-28T10:00:00+03:00",
                           "status":"Available","serviceId":null,"serviceName":null,"personId":null,
                           "phone":null,"masked":false,"bookingId":null}
                        ]
                        """.trimIndent(),
                    )
                }

            val result = api.fetchSlots("w1", "2026-09-28", "2026-10-05")

            assertEquals(
                WorkerSlotsResult.Loaded(
                    listOf(
                        WorkerSlot(
                            eventId = "e1",
                            localDate = "2026-09-28",
                            weekday = 1,
                            startsAt = "2026-09-28T09:00:00+03:00",
                            endsAt = "2026-09-28T09:30:00+03:00",
                            status = WorkerSlotStatus.Booked,
                            rawStatus = "Booked",
                            serviceId = "s1",
                            serviceName = "Haircut",
                            personId = "p1",
                            phone = "+7***1234",
                            masked = true,
                            bookingId = "b1",
                        ),
                        WorkerSlot(
                            eventId = "e2",
                            localDate = "2026-09-28",
                            weekday = 1,
                            startsAt = "2026-09-28T09:30:00+03:00",
                            endsAt = "2026-09-28T10:00:00+03:00",
                            status = WorkerSlotStatus.Available,
                            rawStatus = "Available",
                            serviceId = null,
                            serviceName = null,
                            personId = null,
                            phone = null,
                            masked = false,
                            bookingId = null,
                        ),
                    ),
                ),
                result,
            )
            assertTrue(url?.contains("from=2026-09-28") == true)
            assertTrue(url?.contains("to=2026-10-05") == true)
        }

    @Test
    fun `an empty range is an empty list, not NotConfigured or a failure`() =
        runTest {
            val api = apiFor(baseUrl) { respondJson("[]") }

            assertEquals(WorkerSlotsResult.Loaded(emptyList()), api.fetchSlots("w1", "2026-09-28", "2026-10-05"))
        }

    /** An unrecognised wire spelling must not crash the read - it degrades to [WorkerSlotStatus.Unknown]
     * with the raw spelling kept for a caller that wants to show it rather than drop it. */
    @Test
    fun `an unrecognised status is Unknown, raw spelling kept, never a parse failure`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respondJson(
                        """
                        [{"eventId":"e9","localDate":"2026-09-28","weekday":1,
                          "startsAt":"2026-09-28T09:00:00+03:00","endsAt":"2026-09-28T09:30:00+03:00",
                          "status":"Rescheduled","masked":false}]
                        """.trimIndent(),
                    )
                }

            val loaded = api.fetchSlots("w1", "2026-09-28", "2026-10-05") as WorkerSlotsResult.Loaded
            val slot = loaded.slots.single()
            assertEquals(WorkerSlotStatus.Unknown, slot.status)
            assertEquals("Rescheduled", slot.rawStatus)
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

            assertEquals(WorkerSlotsResult.NotConfigured, api.fetchSlots("w1", "2026-09-28", "2026-10-05"))
            assertTrue("The adapter must not reach the network at all.", !called)
        }

    /** `worker_slots.invalid_range`'s whole reason for existing: a caller-fixable mistake must reach the
     * operator verbatim, not as a generic failure. */
    @Test
    fun `an invalid range shows the server detail verbatim`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """{"type":"worker_slots.invalid_range",
                            "detail":"The range must end on or after it starts; got 2026-10-05 .. 2026-09-28."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(
                WorkerSlotsResult.Refused("The range must end on or after it starts; got 2026-10-05 .. 2026-09-28."),
                api.fetchSlots("w1", "2026-10-05", "2026-09-28"),
            )
        }

    @Test
    fun `a bare non-2xx with no detail is a failure, never a fabricated sentence`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(
                WorkerSlotsResult.Failed(BookingsQueueFailure.Unexpected),
                api.fetchSlots("w1", "2026-09-28", "2026-10-05"),
            )
        }

    @Test
    fun `a dropped connection is Transport, never a hostname on screen`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("Unable to resolve host \"calendar-api.example.invalid\"") }

            assertEquals(
                WorkerSlotsResult.Failed(BookingsQueueFailure.Transport),
                api.fetchSlots("w1", "2026-09-28", "2026-10-05"),
            )
        }

    @Test
    fun `a 200 whose body is not the promised shape is a failure, not an empty list`() =
        runTest {
            val api = apiFor(baseUrl) { respondJson("""{"nope":true}""") }

            assertEquals(
                WorkerSlotsResult.Failed(BookingsQueueFailure.Unexpected),
                api.fetchSlots("w1", "2026-09-28", "2026-10-05"),
            )
        }

    @Test
    fun `a worker's own null calendar-gated fields stay null, never guessed`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respondJson(
                        """
                        [{"eventId":"e1","localDate":"2026-09-28","weekday":1,
                          "startsAt":"2026-09-28T09:00:00+03:00","endsAt":"2026-09-28T09:30:00+03:00",
                          "status":"PendingConfirmation","personId":"p1","phone":null,"masked":false,
                          "bookingId":"b1"}]
                        """.trimIndent(),
                    )
                }

            val loaded = api.fetchSlots("w1", "2026-09-28", "2026-10-05") as WorkerSlotsResult.Loaded
            val slot = loaded.slots.single()
            assertEquals("p1", slot.personId)
            assertNull("A held slot this operator lacks customer:read for keeps a null phone.", slot.phone)
        }

    private fun apiFor(
        calendarApiBaseUrl: String?,
        handler: MockRequestHandler,
    ): KtorWorkerSlotsApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorWorkerSlotsApi(client, calendarApiBaseUrl)
    }
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) =
    respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
