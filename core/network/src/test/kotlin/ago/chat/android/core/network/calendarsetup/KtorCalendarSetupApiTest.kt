package ago.chat.android.core.network.calendarsetup

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.calendarsetup.CalendarDraft
import ago.chat.android.core.domain.calendarsetup.ConfiguredCalendar
import ago.chat.android.core.domain.calendarsetup.TenantSetup
import ago.chat.android.core.domain.calendarsetup.TenantSetupResult
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
 * `26-141`: the tenant-configuration read and the three Setup writes, driven through the real client
 * configuration and a `MockEngine` — the identical shape `KtorWorkingHoursApiTest` already establishes.
 */
class KtorCalendarSetupApiTest {
    private val baseUrl = "https://calendar-api.example.invalid"

    @Test
    fun `the configuration read is reduced to the four fields the Setup screen draws`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(baseUrl) { request ->
                    requestedUrl = request.url.toString()
                    respondJson(
                        """
                        {
                          "tenantName":"Barbershop","publicKey":"pub_demo",
                          "allowedOrigins":["https://shop.example","https://www.shop.example"],
                          "calendars":[
                            {"calendarId":"cal1","name":"Main","timeZone":"Europe/Moscow","isPublished":true,
                             "workerIds":["w1"],"workingHours":[]},
                            {"calendarId":"cal2","name":"Spa","timeZone":"Asia/Yekaterinburg","isPublished":false,
                             "workerIds":[],"workingHours":[]}
                          ],
                          "workers":[{"workerId":"w1","displayName":"Alex","isActive":true,"serviceIds":[]}],
                          "services":[],"workerQuota":2
                        }
                        """.trimIndent(),
                    )
                }

            val result = api.fetchSetup()

            assertEquals(
                TenantSetupResult.Loaded(
                    TenantSetup(
                        tenantName = "Barbershop",
                        publicKey = "pub_demo",
                        allowedOrigins = listOf("https://shop.example", "https://www.shop.example"),
                        calendars =
                            listOf(
                                ConfiguredCalendar("cal1", "Main", "Europe/Moscow", published = true),
                                ConfiguredCalendar("cal2", "Spa", "Asia/Yekaterinburg", published = false),
                            ),
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/console/configuration", requestedUrl)
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

            assertEquals(TenantSetupResult.NotConfigured, api.fetchSetup())
            assertTrue("The adapter must not reach the network at all.", !called)
        }

    @Test
    fun `a dropped connection is Transport, never a hostname on screen`() =
        runTest {
            val api = apiFor(baseUrl) { throw IOException("Unable to resolve host \"calendar-api.example.invalid\"") }

            assertEquals(TenantSetupResult.Failed(BookingsQueueFailure.Transport), api.fetchSetup())
        }

    @Test
    fun `a 200 whose body is not the promised shape is a failure, not an empty setup`() =
        runTest {
            val api = apiFor(baseUrl) { respondJson("""{"tenantName":"Barbershop"}""") }

            assertEquals(TenantSetupResult.Failed(BookingsQueueFailure.Unexpected), api.fetchSetup())
        }

    @Test
    fun `saving allowed origins sends PUT with the whole list and answers Succeeded on 204`() =
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

            val result = api.saveAllowedOrigins(listOf("https://a.example", "https://b.example"))

            assertEquals(BookingActionResult.Succeeded, result)
            assertEquals(HttpMethod.Put, method)
            assertEquals("$baseUrl/api/v1/console/configuration/allowed-origins", url)
            assertEquals("""{"origins":["https://a.example","https://b.example"]}""", body)
        }

    @Test
    fun `creating a calendar sends POST with name, timeZone and publish and answers Succeeded on 201`() =
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
                        """{"calendarId":"cal9"}""",
                        HttpStatusCode.Created,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result =
                api.createCalendar(
                    CalendarDraft(name = "Evenings", published = true),
                    timeZone = "Europe/Moscow",
                )

            assertEquals(BookingActionResult.Succeeded, result)
            assertEquals(HttpMethod.Post, method)
            assertEquals("$baseUrl/api/v1/console/calendars", url)
            assertEquals("""{"name":"Evenings","timeZone":"Europe/Moscow","publish":true}""", body)
        }

    @Test
    fun `updating a calendar sends PUT with name and publish only, never a timezone`() =
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

            val result = api.updateCalendar("cal1", CalendarDraft(name = "Main hall", published = false))

            assertEquals(BookingActionResult.Succeeded, result)
            assertEquals(HttpMethod.Put, method)
            assertEquals("$baseUrl/api/v1/console/calendars/cal1", url)
            // Timezone is create-only: it must never appear in the update body.
            assertEquals("""{"name":"Main hall","publish":false}""", body)
        }

    @Test
    fun `a refusal carrying a problem-details detail is shown verbatim`() =
        runTest {
            val api =
                apiFor(baseUrl) {
                    respond(
                        """{"type":"about:blank","title":"Bad Request","status":400,
                            "detail":"An allowed origin must be an absolute https URL."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(
                BookingActionResult.Refused("An allowed origin must be an absolute https URL."),
                api.saveAllowedOrigins(listOf("not-a-url")),
            )
        }

    @Test
    fun `a bare non-2xx with no detail to show is a failure, never a fabricated sentence`() =
        runTest {
            val api = apiFor(baseUrl) { respondError(HttpStatusCode.InternalServerError) }

            assertEquals(
                BookingActionResult.Failed(BookingsQueueFailure.Unexpected),
                api.createCalendar(CalendarDraft(name = "Evenings", published = true), timeZone = "Europe/Moscow"),
            )
        }

    private fun apiFor(
        calendarApiBaseUrl: String?,
        handler: MockRequestHandler,
    ): KtorCalendarSetupApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorCalendarSetupApi(client, calendarApiBaseUrl)
    }
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) =
    respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
