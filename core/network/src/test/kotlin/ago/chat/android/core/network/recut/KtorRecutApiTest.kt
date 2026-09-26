package ago.chat.android.core.network.recut

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.recut.RecutBooking
import ago.chat.android.core.domain.recut.RecutBookingDecision
import ago.chat.android.core.domain.recut.RecutBookingStatus
import ago.chat.android.core.domain.recut.RecutConfirmResult
import ago.chat.android.core.domain.recut.RecutConfirmation
import ago.chat.android.core.domain.recut.RecutDay
import ago.chat.android.core.domain.recut.RecutDecision
import ago.chat.android.core.domain.recut.RecutPreview
import ago.chat.android.core.domain.recut.RecutPreviewResult
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
 * `26-168`: the re-cut preview and confirm pair, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorWorkersApiTest`/`KtorWorkingHoursApiTest` already establish.
 */
class KtorRecutApiTest {
    private val baseUrl = "https://calendar-api.example.invalid"

    @Test
    fun `a preview sends the from date and reads back every day, including an empty one`() =
        runTest {
            var method: HttpMethod? = null
            var url: String? = null
            var requestBody: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    method = request.method
                    url = request.url.toString()
                    requestBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respondJson(
                        """
                        {"days":[
                          {"localDate":"2026-09-28","availableSlotsToDelete":3,"bookings":[
                            {"bookingId":"b1","startsAt":"2026-09-28T09:00:00+03:00","endsAt":"2026-09-28T09:30:00+03:00",
                             "status":"PendingConfirmation","serviceId":"s1","serviceName":"Haircut","personId":"p1",
                             "phone":"+7***1234","masked":true,"canDecide":true}
                          ]},
                          {"localDate":"2026-09-29","availableSlotsToDelete":0,"bookings":[]}
                        ],"fingerprint":"fp-1"}
                        """.trimIndent(),
                    )
                }

            val result = api.preview("w1", "2026-09-28")

            assertEquals(
                RecutPreviewResult.Loaded(
                    RecutPreview(
                        days =
                            listOf(
                                RecutDay(
                                    localDate = "2026-09-28",
                                    availableSlotsToDelete = 3,
                                    bookings =
                                        listOf(
                                            RecutBooking(
                                                bookingId = "b1",
                                                startsAt = "2026-09-28T09:00:00+03:00",
                                                endsAt = "2026-09-28T09:30:00+03:00",
                                                status = RecutBookingStatus.PendingConfirmation,
                                                rawStatus = "PendingConfirmation",
                                                serviceId = "s1",
                                                serviceName = "Haircut",
                                                personId = "p1",
                                                phone = "+7***1234",
                                                masked = true,
                                                canDecide = true,
                                            ),
                                        ),
                                ),
                                RecutDay(localDate = "2026-09-29", availableSlotsToDelete = 0, bookings = emptyList()),
                            ),
                        fingerprint = "fp-1",
                    ),
                ),
                result,
            )
            assertEquals(HttpMethod.Post, method)
            assertEquals("$baseUrl/api/v1/console/workers/w1/schedule/recut/preview", url)
            assertEquals("""{"from":"2026-09-28"}""", requestBody)
        }

    /** A `NoShow` booking always forces its own day to be skipped - it never carries a decision. */
    @Test
    fun `a NoShow booking cannot decide`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respondJson(
                        """
                        {"days":[{"localDate":"2026-09-28","availableSlotsToDelete":0,"bookings":[
                          {"bookingId":"b2","startsAt":"2026-09-28T09:00:00+03:00","endsAt":"2026-09-28T09:30:00+03:00",
                           "status":"NoShow","masked":false,"canDecide":false}
                        ]}],"fingerprint":"fp-2"}
                        """.trimIndent(),
                    )
                }

            val loaded = api.preview("w1", "2026-09-28") as RecutPreviewResult.Loaded
            val day = loaded.preview.days.single()
            val booking = day.bookings.single()
            assertEquals(RecutBookingStatus.NoShow, booking.status)
            assertEquals(false, booking.canDecide)
        }

    /** An unrecognised wire spelling must not crash the read - the identical
     * [ago.chat.android.core.domain.workerslots.WorkerSlotStatus] defensive stance restated for this
     * narrower, three-value list. */
    @Test
    fun `an unrecognised booking status is Unknown, raw spelling kept`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respondJson(
                        """
                        {"days":[{"localDate":"2026-09-28","availableSlotsToDelete":0,"bookings":[
                          {"bookingId":"b3","startsAt":"2026-09-28T09:00:00+03:00","endsAt":"2026-09-28T09:30:00+03:00",
                           "status":"Rescheduled","masked":false,"canDecide":false}
                        ]}],"fingerprint":"fp-3"}
                        """.trimIndent(),
                    )
                }

            val loaded = api.preview("w1", "2026-09-28") as RecutPreviewResult.Loaded
            val day = loaded.preview.days.single()
            val booking = day.bookings.single()
            assertEquals(RecutBookingStatus.Unknown, booking.status)
            assertEquals("Rescheduled", booking.rawStatus)
        }

    @Test
    fun `a deployment with no calendar base URL answers NotConfigured without making a request on preview`() =
        runTest {
            var called = false
            val api =
                apiFor(null) {
                    called = true
                    respondJson("""{"days":[],"fingerprint":"fp"}""")
                }

            assertEquals(RecutPreviewResult.NotConfigured, api.preview("w1", "2026-09-28"))
            assertTrue("The adapter must not reach the network at all.", !called)
        }

    /** A bounds refusal the operator can fix by picking a different `from` - `code` must carry the
     * server's own stable `type`, never just its prose. */
    @Test
    fun `recut_from_before_today carries both the detail and the code`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """{"type":"recut.from_before_today",
                            "detail":"2026-09-01 has already passed - 2026-09-28 is today."}""",
                        HttpStatusCode.BadRequest,
                        jsonHeaders,
                    )
                }

            assertEquals(
                RecutPreviewResult.Refused("2026-09-01 has already passed - 2026-09-28 is today.", "recut.from_before_today"),
                api.preview("w1", "2026-09-01"),
            )
        }

    @Test
    fun `a preview whose connection drops is a transport failure`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("boom") }

            assertEquals(RecutPreviewResult.Failed(BookingsQueueFailure.Transport), api.preview("w1", "2026-09-28"))
        }

    @Test
    fun `a preview with a bare non-2xx and no detail is a failure, never a fabricated sentence`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.InternalServerError) }

            assertEquals(RecutPreviewResult.Failed(BookingsQueueFailure.Unexpected), api.preview("w1", "2026-09-28"))
        }

    @Test
    fun `a preview whose 200 body is not the promised shape is a failure`() =
        runTest {
            val api = apiFor(baseUrl) { respondJson("""{"nope":true}""") }

            assertEquals(RecutPreviewResult.Failed(BookingsQueueFailure.Unexpected), api.preview("w1", "2026-09-28"))
        }

    @Test
    fun `a confirm sends the fingerprint and decisions as Cancel-or-Keep strings`() =
        runTest {
            var requestBody: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    requestBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respondJson(
                        """{"recutDays":["2026-09-28"],"skippedDays":["2026-09-29"],
                            "slotsDeleted":3,"slotsInserted":5,"bookingsCancelled":1}""",
                    )
                }

            val result =
                api.confirm(
                    "w1",
                    "2026-09-28",
                    "fp-1",
                    listOf(
                        RecutBookingDecision("b1", RecutDecision.Cancel),
                        RecutBookingDecision("b2", RecutDecision.Keep),
                    ),
                )

            assertEquals(
                RecutConfirmResult.Confirmed(
                    RecutConfirmation(
                        recutDays = listOf("2026-09-28"),
                        skippedDays = listOf("2026-09-29"),
                        slotsDeleted = 3,
                        slotsInserted = 5,
                        bookingsCancelled = 1,
                    ),
                ),
                result,
            )
            assertEquals(
                """{"from":"2026-09-28","fingerprint":"fp-1","decisions":[""" +
                    """{"bookingId":"b1","decision":"Cancel"},{"bookingId":"b2","decision":"Keep"}]}""",
                requestBody,
            )
        }

    /** `recut.stale` is this port's own central refusal - the follow-up screen must be able to tell it
     * apart from every other refusal to clear its stale preview, which is exactly why `code` exists. */
    @Test
    fun `recut_stale carries the code the UI branches on, and the server's own detail`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """{"type":"recut.stale",
                            "detail":"The bookings in this range changed since the preview was generated."}""",
                        HttpStatusCode.Conflict,
                        jsonHeaders,
                    )
                }

            val result = api.confirm("w1", "2026-09-28", "fp-stale", emptyList())

            assertEquals(
                RecutConfirmResult.Refused("The bookings in this range changed since the preview was generated.", "recut.stale"),
                result,
            )
        }

    @Test
    fun `a refusal with a detail but no type carries an empty code, not a crash`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """{"detail":"something the client does not recognise a type for"}""",
                        HttpStatusCode.BadRequest,
                        jsonHeaders,
                    )
                }

            val result = api.confirm("w1", "2026-09-28", "fp-1", emptyList())

            assertEquals(
                RecutConfirmResult.Refused("something the client does not recognise a type for", ""),
                result,
            )
        }

    @Test
    fun `a deployment with no calendar base URL answers NotConfigured without making a request on confirm`() =
        runTest {
            var called = false
            val api =
                apiFor(null) {
                    called = true
                    respondJson(
                        """{"recutDays":[],"skippedDays":[],"slotsDeleted":0,"slotsInserted":0,"bookingsCancelled":0}""",
                    )
                }

            assertEquals(RecutConfirmResult.NotConfigured, api.confirm("w1", "2026-09-28", "fp-1", emptyList()))
            assertTrue("The adapter must not reach the network at all.", !called)
        }

    @Test
    fun `a confirm whose connection drops is a transport failure`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("boom") }

            assertEquals(
                RecutConfirmResult.Failed(BookingsQueueFailure.Transport),
                api.confirm("w1", "2026-09-28", "fp-1", emptyList()),
            )
        }

    @Test
    fun `a confirm with a bare non-2xx and no detail is a failure, never a fabricated sentence`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.InternalServerError) }

            assertEquals(
                RecutConfirmResult.Failed(BookingsQueueFailure.Unexpected),
                api.confirm("w1", "2026-09-28", "fp-1", emptyList()),
            )
        }

    private fun apiFor(
        calendarApiBaseUrl: String?,
        handler: MockRequestHandler,
    ): KtorRecutApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorRecutApi(client, calendarApiBaseUrl)
    }

    private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) =
    respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
