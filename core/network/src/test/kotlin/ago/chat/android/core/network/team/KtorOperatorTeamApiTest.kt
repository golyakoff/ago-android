package ago.chat.android.core.network.team

import ago.chat.android.core.domain.team.OperatorRoleSeat
import ago.chat.android.core.domain.team.OperatorTeamFailure
import ago.chat.android.core.domain.team.OperatorTeamMember
import ago.chat.android.core.domain.team.OperatorTeamResult
import ago.chat.android.core.domain.team.RoleSeatSummary
import ago.chat.android.core.domain.team.SeatSummaryResult
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
 * `26-55`: the two roster reads, driven through the real client configuration and a `MockEngine` — the
 * identical shape `KtorBookingsApiTest` already establishes.
 */
class KtorOperatorTeamApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"
    private val siteId = "site-123"

    // ------------------------------------------------------------- GET .../sites/{siteId}/operators

    @Test
    fun `the roster is read with the current site id in the path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """
                        {
                          "operators": [
                            {
                              "operatorId":"op-1","displayName":"Аня","email":"anya@example.com",
                              "roles":[{"roleName":"Admin","holdsSeat":true},{"roleName":"Operator","holdsSeat":false}]
                            }
                          ]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.fetchTeam()

            assertEquals(
                OperatorTeamResult.Loaded(
                    listOf(
                        OperatorTeamMember(
                            operatorId = "op-1",
                            displayName = "Аня",
                            email = "anya@example.com",
                            roles =
                                listOf(
                                    OperatorRoleSeat(roleName = "Admin", holdsSeat = true),
                                    OperatorRoleSeat(roleName = "Operator", holdsSeat = false),
                                ),
                        ),
                    ),
                ),
                result,
            )
            assertEquals("$baseUrl/api/v1/sites/$siteId/operators", requestedUrl)
        }

    @Test
    fun `a member with no display name or email still round-trips`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"operators":[{"operatorId":"op-2","roles":[]}]}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.fetchTeam()

            assertEquals(
                OperatorTeamResult.Loaded(
                    listOf(OperatorTeamMember(operatorId = "op-2", displayName = null, email = null, roles = emptyList())),
                ),
                result,
            )
        }

    @Test
    fun `a 5xx on the roster read is Unexpected, not an empty team`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(OperatorTeamResult.Failed(OperatorTeamFailure.Unexpected), api.fetchTeam())
        }

    @Test
    fun `a dropped connection on the roster read is Transport`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(OperatorTeamResult.Failed(OperatorTeamFailure.Transport), api.fetchTeam())
        }

    @Test
    fun `a 200 that dropped the shape is Unexpected, never an empty team`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"somethingElseEntirely":true}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(OperatorTeamResult.Failed(OperatorTeamFailure.Unexpected), api.fetchTeam())
        }

    @Test
    fun `no active site selected is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(OperatorTeamResult.Failed(OperatorTeamFailure.Unexpected), api.fetchTeam())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    // ---------------------------------------------- GET .../operators/seat-assignment-summary

    @Test
    fun `the seat summary is read with the current site id in the path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """{"roles":[{"roleName":"Operator","heldSeats":3,"limit":5,"overLimit":false}]}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.fetchSeatSummary()

            assertEquals(
                SeatSummaryResult.Loaded(listOf(RoleSeatSummary(roleName = "Operator", heldSeats = 3, limit = 5, overLimit = false))),
                result,
            )
            assertEquals("$baseUrl/api/v1/sites/$siteId/operators/seat-assignment-summary", requestedUrl)
        }

    @Test
    fun `overLimit is read from the response, never recomputed`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"roles":[{"roleName":"Admin","heldSeats":4,"limit":2,"overLimit":true}]}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.fetchSeatSummary() as SeatSummaryResult.Loaded

            assertEquals(true, result.roles.single().overLimit)
        }

    @Test
    fun `a dropped connection on the summary read is Transport`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(SeatSummaryResult.Failed(OperatorTeamFailure.Transport), api.fetchSeatSummary())
        }

    @Test
    fun `a 5xx on the summary read is Unexpected`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(SeatSummaryResult.Failed(OperatorTeamFailure.Unexpected), api.fetchSeatSummary())
        }

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorOperatorTeamApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorOperatorTeamApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }
}
