package ago.chat.android.core.network.persons

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.persons.PersonProfile
import ago.chat.android.core.domain.persons.PersonsResult
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
import org.junit.Test
import java.io.IOException

/**
 * `26-162`: the person-registry batch read, driven through the real client configuration and a
 * `MockEngine` — the identical shape `KtorVisitorSummaryApiTest` already establishes for the same
 * `Ago.Chat.Api` origin.
 */
class KtorPersonsApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"

    @Test
    fun `ids are joined into one comma-separated query parameter, in the order given, against the right path`() =
        runTest {
            var requestedPath: String? = null
            var requestedIds: String? = null
            val api =
                apiFor { request ->
                    requestedPath = request.url.toString().substringBefore("?")
                    requestedIds = request.url.parameters["ids"]
                    respond("""{"persons":[]}""", HttpStatusCode.OK, jsonHeaders())
                }

            api.fetchPersons(listOf("p1", "p2"))

            assertEquals("$baseUrl/api/v1/persons", requestedPath)
            assertEquals("p1,p2", requestedIds)
        }

    @Test
    fun `duplicate ids are asked for once`() =
        runTest {
            var requestedIds: String? = null
            val api =
                apiFor { request ->
                    requestedIds = request.url.parameters["ids"]
                    respond("""{"persons":[]}""", HttpStatusCode.OK, jsonHeaders())
                }

            api.fetchPersons(listOf("p1", "p1"))

            assertEquals("p1", requestedIds)
        }

    @Test
    fun `an empty id list is Loaded with nothing, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(PersonsResult.Loaded(emptyList()), api.fetchPersons(emptyList()))
            assertEquals("an empty batch must never reach the network", 0, calls)
        }

    @Test
    fun `a 200 maps every person in the batch, a null display name kept honest`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {"persons":[
                          {"personId":"p1","displayName":"Анна"},
                          {"personId":"p2","displayName":null}
                        ]}
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetchPersons(listOf("p1", "p2"))

            assertEquals(
                PersonsResult.Loaded(
                    listOf(
                        PersonProfile(personId = "p1", displayName = "Анна"),
                        PersonProfile(personId = "p2", displayName = null),
                    ),
                ),
                result,
            )
        }

    @Test
    fun `an unknown field on the wire does not break the read`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"persons":[{"personId":"p1","displayName":"Анна","channels":[],"firstSeenAt":"2026-01-01T00:00:00Z"}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            assertEquals(
                PersonsResult.Loaded(listOf(PersonProfile(personId = "p1", displayName = "Анна"))),
                api.fetchPersons(listOf("p1")),
            )
        }

    @Test
    fun `a 5xx is a server error, not a fabricated batch`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(PersonsResult.Failed(NetworkFailure.ServerError(503)), api.fetchPersons(listOf("p1")))
        }

    @Test
    fun `a dropped connection is NoConnection`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(PersonsResult.Failed(NetworkFailure.NoConnection), api.fetchPersons(listOf("p1")))
        }

    @Test
    fun `a 200 with a body that is not even JSON is Unexpected, never a fabricated batch`() =
        runTest {
            val api = apiFor { respond("not json at all", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(PersonsResult.Failed(NetworkFailure.Unexpected), api.fetchPersons(listOf("p1")))
        }

    @Test
    fun `a 200 whose body is a bare object with no persons key decodes as an empty batch, never a failure`() =
        runTest {
            // `PersonsWireDto.persons` defaults to empty - the identical resilience
            // `TenantConfigurationWireDto.services` already carries in `KtorBookingsApi`, applied here so
            // a response this app cannot make sense of degrades to "no names yet" rather than failing the
            // whole calendar screen doing the display-merge (`adr/0184`'s own Consequences).
            val api = apiFor { respond("""{"somethingElseEntirely":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(PersonsResult.Loaded(emptyList()), api.fetchPersons(listOf("p1")))
        }

    private fun jsonHeaders() = headersOf("Content-Type", ContentType.Application.Json.toString())

    private fun apiFor(handler: MockRequestHandler): KtorPersonsApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorPersonsApi(client, baseUrl)
    }
}
