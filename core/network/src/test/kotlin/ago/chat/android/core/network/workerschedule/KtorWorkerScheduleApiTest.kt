package ago.chat.android.core.network.workerschedule

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.workerschedule.SaveWorkerScheduleResult
import ago.chat.android.core.domain.workerschedule.ScheduleKind
import ago.chat.android.core.domain.workerschedule.WorkerSchedule
import ago.chat.android.core.domain.workerschedule.WorkerScheduleDraft
import ago.chat.android.core.domain.workerschedule.WorkerScheduleResult
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
 * `26-168`: the worker schedule template's read and its one create-or-replace write, driven through the
 * real client configuration and a `MockEngine` — the identical shape `KtorWorkersApiTest`/
 * `KtorWorkingHoursApiTest` already establish.
 */
class KtorWorkerScheduleApiTest {
    private val baseUrl = "https://calendar-api.example.invalid"

    private val fullScheduleJson =
        """
        {"scheduleId":"sch1","workerId":"w1","kind":"Cycle","cycleAnchor":"2026-01-01",
         "cycleWorkingDays":2,"cycleRestDays":1,"cycleStartsAt":"09:00","cycleEndsAt":"18:00",
         "slotMinutes":30,"bufferMinutes":10,"horizonDays":60,"materializeFrom":"2026-09-01",
         "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z",
         "buffersCountTowardServiceDuration":true}
        """.trimIndent()

    private val fullSchedule =
        WorkerSchedule(
            scheduleId = "sch1",
            workerId = "w1",
            kind = ScheduleKind.Cycle,
            cycleAnchor = "2026-01-01",
            cycleWorkingDays = 2,
            cycleRestDays = 1,
            cycleStartsAt = "09:00",
            cycleEndsAt = "18:00",
            slotMinutes = 30,
            bufferMinutes = 10,
            horizonDays = 60,
            materializeFrom = "2026-09-01",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-02T00:00:00Z",
            buffersCountTowardServiceDuration = true,
        )

    @Test
    fun `a loaded schedule carries every field, cycle and weekly alike`() =
        runTest {
            var url: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    url = request.url.toString()
                    respondJson(fullScheduleJson)
                }

            assertEquals(WorkerScheduleResult.Loaded(fullSchedule), api.fetchSchedule("w1"))
            assertEquals("$baseUrl/api/v1/console/workers/w1/schedule", url)
        }

    @Test
    fun `a weekly schedule carries no cycle fields`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respondJson(
                        """
                        {"scheduleId":"sch2","workerId":"w2","kind":"Weekly","cycleAnchor":null,
                         "cycleWorkingDays":null,"cycleRestDays":null,"cycleStartsAt":null,"cycleEndsAt":null,
                         "slotMinutes":20,"bufferMinutes":5,"horizonDays":30,"materializeFrom":"2026-09-01",
                         "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z",
                         "buffersCountTowardServiceDuration":false}
                        """.trimIndent(),
                    )
                }

            val loaded = api.fetchSchedule("w2") as WorkerScheduleResult.Loaded
            assertEquals(ScheduleKind.Weekly, loaded.schedule.kind)
            assertEquals(null, loaded.schedule.cycleAnchor)
            assertEquals(false, loaded.schedule.buffersCountTowardServiceDuration)
        }

    /** `configuration.no_schedule`'s whole reason for existing: a fresh worker's empty state must not
     * read as a failure. */
    @Test
    fun `configuration_no_schedule is None, told apart from a failure by type - not detail`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """{"type":"configuration.no_schedule","title":"configuration.no_schedule",
                            "detail":"Worker w1 has no schedule yet."}""",
                        HttpStatusCode.NotFound,
                        jsonHeaders,
                    )
                }

            assertEquals(WorkerScheduleResult.None, api.fetchSchedule("w1"))
        }

    @Test
    fun `a different 404 is an ordinary failure, never None`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """{"type":"configuration.not_found","detail":"No worker w1 in this tenant."}""",
                        HttpStatusCode.NotFound,
                        jsonHeaders,
                    )
                }

            assertEquals(WorkerScheduleResult.Failed(BookingsQueueFailure.Unexpected), api.fetchSchedule("w1"))
        }

    @Test
    fun `a deployment with no calendar base URL answers NotConfigured without making a request`() =
        runTest {
            var called = false
            val api =
                apiFor(null) {
                    called = true
                    respondJson(fullScheduleJson)
                }

            assertEquals(WorkerScheduleResult.NotConfigured, api.fetchSchedule("w1"))
            assertTrue("The adapter must not reach the network at all.", !called)
        }

    @Test
    fun `a dropped connection reading the schedule is Transport, never a hostname on screen`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("Unable to resolve host \"calendar-api.example.invalid\"") }

            assertEquals(WorkerScheduleResult.Failed(BookingsQueueFailure.Transport), api.fetchSchedule("w1"))
        }

    @Test
    fun `a 200 whose body is not the promised shape is a failure, never None`() =
        runTest {
            val api = apiFor(baseUrl) { respondJson("""{"nope":true}""") }

            assertEquals(WorkerScheduleResult.Failed(BookingsQueueFailure.Unexpected), api.fetchSchedule("w1"))
        }

    @Test
    fun `a save sends PUT with every field and reads the stored schedule back`() =
        runTest {
            var method: HttpMethod? = null
            var url: String? = null
            var body: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    method = request.method
                    url = request.url.toString()
                    body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respondJson(fullScheduleJson)
                }

            val result =
                api.saveSchedule(
                    "w1",
                    WorkerScheduleDraft(
                        kind = ScheduleKind.Cycle,
                        cycleAnchor = "2026-01-01",
                        cycleWorkingDays = 2,
                        cycleRestDays = 1,
                        cycleStartsAt = "09:00",
                        cycleEndsAt = "18:00",
                        slotMinutes = 30,
                        bufferMinutes = 10,
                        horizonDays = 60,
                        materializeFrom = "2026-09-01",
                        buffersCountTowardServiceDuration = true,
                    ),
                )

            assertEquals(SaveWorkerScheduleResult.Saved(fullSchedule), result)
            assertEquals(HttpMethod.Put, method)
            assertEquals("$baseUrl/api/v1/console/workers/w1/schedule", url)
            assertEquals(
                """{"kind":"Cycle","cycleAnchor":"2026-01-01","cycleWorkingDays":2,"cycleRestDays":1,""" +
                    """"cycleStartsAt":"09:00","cycleEndsAt":"18:00","slotMinutes":30,"bufferMinutes":10,""" +
                    """"horizonDays":60,"materializeFrom":"2026-09-01","buffersCountTowardServiceDuration":true}""",
                body,
            )
        }

    @Test
    fun `a weekly draft sends null cycle fields, never omitted`() =
        runTest {
            var body: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respondJson(fullScheduleJson)
                }

            api.saveSchedule(
                "w1",
                WorkerScheduleDraft(
                    kind = ScheduleKind.Weekly,
                    cycleAnchor = null,
                    cycleWorkingDays = null,
                    cycleRestDays = null,
                    cycleStartsAt = null,
                    cycleEndsAt = null,
                    slotMinutes = 20,
                    bufferMinutes = 5,
                    horizonDays = 30,
                    materializeFrom = "2026-09-01",
                ),
            )

            assertEquals(
                """{"kind":"Weekly","cycleAnchor":null,"cycleWorkingDays":null,"cycleRestDays":null,""" +
                    """"cycleStartsAt":null,"cycleEndsAt":null,"slotMinutes":20,"bufferMinutes":5,""" +
                    """"horizonDays":30,"materializeFrom":"2026-09-01","buffersCountTowardServiceDuration":true}""",
                body,
            )
        }

    /** `MaterializeFrom` moving the schedule's own cursor backwards is the save's own central,
     * caller-actionable refusal - it must reach the operator verbatim. */
    @Test
    fun `a save refused for moving the cursor backwards shows the server detail verbatim`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """{"type":"configuration.invalid",
                            "detail":"materializeFrom cannot move the schedule's cursor backwards."}""",
                        HttpStatusCode.BadRequest,
                        jsonHeaders,
                    )
                }

            val result =
                api.saveSchedule(
                    "w1",
                    WorkerScheduleDraft(
                        kind = ScheduleKind.Weekly,
                        cycleAnchor = null,
                        cycleWorkingDays = null,
                        cycleRestDays = null,
                        cycleStartsAt = null,
                        cycleEndsAt = null,
                        slotMinutes = 20,
                        bufferMinutes = 5,
                        horizonDays = 30,
                        materializeFrom = "2026-01-01",
                    ),
                )

            assertEquals(
                SaveWorkerScheduleResult.Refused("materializeFrom cannot move the schedule's cursor backwards."),
                result,
            )
        }

    @Test
    fun `a save with a bare non-2xx and no detail is a failure, never a fabricated sentence`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.InternalServerError) }

            val result =
                api.saveSchedule(
                    "w1",
                    WorkerScheduleDraft(
                        kind = ScheduleKind.Weekly,
                        cycleAnchor = null,
                        cycleWorkingDays = null,
                        cycleRestDays = null,
                        cycleStartsAt = null,
                        cycleEndsAt = null,
                        slotMinutes = 20,
                        bufferMinutes = 5,
                        horizonDays = 30,
                        materializeFrom = "2026-09-01",
                    ),
                )

            assertEquals(SaveWorkerScheduleResult.Failed(BookingsQueueFailure.Unexpected), result)
        }

    @Test
    fun `a save whose connection drops is a transport failure`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("boom") }

            val result =
                api.saveSchedule(
                    "w1",
                    WorkerScheduleDraft(
                        kind = ScheduleKind.Weekly,
                        cycleAnchor = null,
                        cycleWorkingDays = null,
                        cycleRestDays = null,
                        cycleStartsAt = null,
                        cycleEndsAt = null,
                        slotMinutes = 20,
                        bufferMinutes = 5,
                        horizonDays = 30,
                        materializeFrom = "2026-09-01",
                    ),
                )

            assertEquals(SaveWorkerScheduleResult.Failed(BookingsQueueFailure.Transport), result)
        }

    private fun apiFor(
        calendarApiBaseUrl: String?,
        handler: MockRequestHandler,
    ): KtorWorkerScheduleApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorWorkerScheduleApi(client, calendarApiBaseUrl)
    }

    private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) =
    respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
