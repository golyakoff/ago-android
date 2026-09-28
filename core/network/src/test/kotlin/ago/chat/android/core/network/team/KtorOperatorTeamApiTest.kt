package ago.chat.android.core.network.team

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.team.ChangeOperatorRoleResult
import ago.chat.android.core.domain.team.CreateInviteResult
import ago.chat.android.core.domain.team.OperatorInviteEffectiveStatus
import ago.chat.android.core.domain.team.OperatorInviteStatus
import ago.chat.android.core.domain.team.OperatorInvitesResult
import ago.chat.android.core.domain.team.OperatorRoleSeat
import ago.chat.android.core.domain.team.OperatorTeamFailure
import ago.chat.android.core.domain.team.OperatorTeamMember
import ago.chat.android.core.domain.team.OperatorTeamResult
import ago.chat.android.core.domain.team.RemoveOperatorResult
import ago.chat.android.core.domain.team.RevokeInviteResult
import ago.chat.android.core.domain.team.RoleSeatSummary
import ago.chat.android.core.domain.team.SeatSummaryResult
import ago.chat.android.core.domain.team.ToggleOperatorSeatResult
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
    fun `26-263 - joinedAt round-trips, and is null for the founder`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """
                        {
                          "operators": [
                            {"operatorId":"op-1","displayName":"Аня","email":"anya@example.com",
                             "roles":[{"roleName":"Operator","holdsSeat":true}],"joinedAt":"2026-09-21T08:00:00Z"},
                            {"operatorId":"op-founder","displayName":"Основатель","email":"f@example.com",
                             "roles":[{"roleName":"Admin","holdsSeat":true}]}
                          ]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val members = (api.fetchTeam() as OperatorTeamResult.Loaded).members

            assertEquals("2026-09-21T08:00:00Z", members[0].joinedAt)
            assertEquals("the founder was never invited, so has no join instant", null, members[1].joinedAt)
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

    // ------------------------------------------------- POST .../sites/{siteId}/operator-invites

    @Test
    fun `a 201 is Created, carrying the one-shot code verbatim`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var sentBody = ""
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """
                        {"operatorInviteId":"inv-1","code":"secret-code","expiresAt":"2026-10-01T00:00:00Z","sendFailed":false}
                        """.trimIndent(),
                        HttpStatusCode.Created,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.createInvite(roleNames = setOf("Operator", "Admin"), email = "kolya@example.com")

            assertEquals(
                CreateInviteResult.Created(
                    operatorInviteId = "inv-1",
                    code = "secret-code",
                    expiresAt = "2026-10-01T00:00:00Z",
                    sendFailed = false,
                ),
                result,
            )
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/sites/$siteId/operator-invites", requested)
            // `26-241`: the body now carries a set of role names (`roleNames`), never a single `roleName`.
            assertTrue(sentBody.contains("\"roleNames\":[\"Operator\",\"Admin\"]"))
            assertTrue(sentBody.contains("\"email\":\"kolya@example.com\""))
        }

    @Test
    fun `sendFailed round-trips as true - the invite still exists, this is not treated as a failure`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"operatorInviteId":"inv-2","code":"c2","expiresAt":"2026-10-01T00:00:00Z","sendFailed":true}""",
                        HttpStatusCode.Created,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.createInvite(roleNames = setOf("Admin"), email = "admin@example.com") as CreateInviteResult.Created

            assertEquals(true, result.sendFailed)
        }

    @Test
    fun `a 400 with a problem-details body is rendered as that exact refusal, InvalidEmail among them`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"OperatorInvite.InvalidEmail","detail":"Укажите корректный email."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.createInvite(roleNames = setOf("Operator"), email = "not-an-email")

            assertEquals(CreateInviteResult.Refused("Укажите корректный email."), result)
        }

    @Test
    fun `a 402 SeatLimitReached is the Operator role's own pool, mapped to RoleSeatFull`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"OperatorInvite.SeatLimitReached","detail":"No free operator seats."}""",
                        HttpStatusCode.PaymentRequired,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.createInvite(roleNames = setOf("Operator"), email = "any@example.com")

            // Branches on the `type`, not the `detail` - a typed refusal naming the role, never the words.
            assertEquals(CreateInviteResult.RoleSeatFull("Operator"), result)
        }

    @Test
    fun `a 402 AdminLimitReached is the Admin role's own pool, mapped to RoleSeatFull`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"OperatorInvite.AdminLimitReached","detail":"No free admin seats."}""",
                        HttpStatusCode.PaymentRequired,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.createInvite(roleNames = setOf("Admin"), email = "any@example.com")

            assertEquals(CreateInviteResult.RoleSeatFull("Admin"), result)
        }

    @Test
    fun `a 402 with an unknown type falls through to the verbatim detail refusal`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"OperatorInvite.SomethingElse","detail":"Payment required for another reason."}""",
                        HttpStatusCode.PaymentRequired,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.createInvite(roleNames = setOf("Operator"), email = "any@example.com")

            assertEquals(CreateInviteResult.Refused("Payment required for another reason."), result)
        }

    @Test
    fun `a refusal with no problem-details body classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(
                CreateInviteResult.Failed(NetworkFailure.ServerError(403)),
                api.createInvite(roleNames = setOf("Operator"), email = "any@example.com"),
            )
        }

    @Test
    fun `a dropped connection on create-invite is a transport failure, not a silently retried write`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(
                CreateInviteResult.Failed(NetworkFailure.NoConnection),
                api.createInvite(roleNames = setOf("Operator"), email = "any@example.com"),
            )
        }

    @Test
    fun `a 201 that dropped the shape is Failed, never a fabricated code`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"somethingElseEntirely":true}""",
                        HttpStatusCode.Created,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(
                CreateInviteResult.Failed(NetworkFailure.Unexpected),
                api.createInvite(roleNames = setOf("Operator"), email = "any@example.com"),
            )
        }

    @Test
    fun `no active site selected is Failed, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(
                CreateInviteResult.Failed(NetworkFailure.Unexpected),
                api.createInvite(roleNames = setOf("Operator"), email = "any@example.com"),
            )
            assertEquals("no active site must never reach the network", 0, calls)
        }

    // --------------------------------------------- GET .../sites/{siteId}/operator-invites (`26-242`)

    @Test
    fun `the invite list is read with the current site id in the path, statuses mapped`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """
                        {
                          "invites": [
                            {"operatorInviteId":"inv-1","email":"a@example.com","createdAt":"2026-09-20T10:00:00Z",
                             "expiresAt":"2026-09-27T10:00:00Z","status":"Sent","smtpErrorCode":null,
                             "roles":["Operator"],"effectiveStatus":"Pending","redeemedAt":null,"removedAt":null},
                            {"operatorInviteId":"inv-2","email":"b@example.com","createdAt":"2026-09-20T10:00:00Z",
                             "expiresAt":"2026-09-27T10:00:00Z","status":"SendFailed","smtpErrorCode":"550",
                             "roles":["Operator","Admin"],"effectiveStatus":"Pending","redeemedAt":null,"removedAt":null},
                            {"operatorInviteId":"inv-3","email":"c@example.com","createdAt":"2026-09-20T10:00:00Z",
                             "expiresAt":"2026-09-27T10:00:00Z","status":"Revoked","smtpErrorCode":null,
                             "roles":["Admin"],"effectiveStatus":"Revoked","redeemedAt":null,"removedAt":null},
                            {"operatorInviteId":"inv-4","email":"d@example.com","createdAt":"2026-09-20T10:00:00Z",
                             "expiresAt":"2026-09-27T10:00:00Z","status":"Redeemed","smtpErrorCode":null,
                             "roles":["Operator"],"effectiveStatus":"InTeam","redeemedAt":"2026-09-21T08:00:00Z","removedAt":null},
                            {"operatorInviteId":"inv-5","email":"e@example.com","createdAt":"2026-09-20T10:00:00Z",
                             "expiresAt":"2026-09-27T10:00:00Z","status":"Expired","smtpErrorCode":null,
                             "roles":[],"effectiveStatus":"Expired","redeemedAt":null,"removedAt":null}
                          ]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val result = api.listInvites() as OperatorInvitesResult.Loaded

            assertEquals(
                listOf(
                    OperatorInviteStatus.Sent,
                    OperatorInviteStatus.SendFailed,
                    OperatorInviteStatus.Revoked,
                    OperatorInviteStatus.Redeemed,
                    OperatorInviteStatus.Expired,
                ),
                result.invites.map { it.status },
            )
            assertEquals("550", result.invites[1].smtpErrorCode)
            assertEquals("2026-09-20T10:00:00Z", result.invites[0].createdAt)
            assertEquals("$baseUrl/api/v1/sites/$siteId/operator-invites", requestedUrl)
        }

    @Test
    fun `26-263 - the three new invite fields and the role set round-trip verbatim`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """
                        {
                          "invites": [
                            {"operatorInviteId":"inv-1","email":"a@example.com","createdAt":"2026-09-20T10:00:00Z",
                             "expiresAt":"2026-09-27T10:00:00Z","status":"Sent","smtpErrorCode":null,
                             "roles":["Operator","Admin"],"effectiveStatus":"Pending","redeemedAt":null,"removedAt":null},
                            {"operatorInviteId":"inv-2","email":"b@example.com","createdAt":"2026-09-20T10:00:00Z",
                             "expiresAt":"2026-09-27T10:00:00Z","status":"Redeemed","smtpErrorCode":null,
                             "roles":["Operator"],"effectiveStatus":"Removed",
                             "redeemedAt":"2026-09-21T08:00:00Z","removedAt":"2026-09-25T12:00:00Z","revokedAt":null},
                            {"operatorInviteId":"inv-3","email":"c@example.com","createdAt":"2026-09-20T10:00:00Z",
                             "expiresAt":"2026-09-27T10:00:00Z","status":"Revoked","smtpErrorCode":null,
                             "roles":["Admin"],"effectiveStatus":"Revoked",
                             "redeemedAt":null,"removedAt":null,"revokedAt":"2026-09-24T09:30:00Z"}
                          ]
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val invites = (api.listInvites() as OperatorInvitesResult.Loaded).invites

            assertEquals(listOf("Operator", "Admin"), invites[0].roles)
            assertEquals(OperatorInviteEffectiveStatus.Pending, invites[0].effectiveStatus)
            assertEquals(null, invites[0].redeemedAt)
            assertEquals(null, invites[0].removedAt)
            assertEquals(null, invites[0].revokedAt)

            assertEquals(OperatorInviteEffectiveStatus.Removed, invites[1].effectiveStatus)
            assertEquals("2026-09-21T08:00:00Z", invites[1].redeemedAt)
            assertEquals("2026-09-25T12:00:00Z", invites[1].removedAt)
            assertEquals(null, invites[1].revokedAt)

            assertEquals(OperatorInviteEffectiveStatus.Revoked, invites[2].effectiveStatus)
            assertEquals("2026-09-24T09:30:00Z", invites[2].revokedAt)
        }

    @Test
    fun `26-263 - an unrecognised effectiveStatus member fails the read as Unexpected, never a fallback`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"invites":[{"operatorInviteId":"x","email":"x@example.com","createdAt":"2026-09-20T10:00:00Z","expiresAt":"2026-09-27T10:00:00Z","status":"Sent","smtpErrorCode":null,"roles":[],"effectiveStatus":"BrandNewState","redeemedAt":null,"removedAt":null}]}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(OperatorInvitesResult.Failed(OperatorTeamFailure.Unexpected), api.listInvites())
        }

    @Test
    fun `26-263 - a missing effectiveStatus fails the read as Unexpected, never a silently defaulted status`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"invites":[{"operatorInviteId":"x","email":"x@example.com","createdAt":"2026-09-20T10:00:00Z","expiresAt":"2026-09-27T10:00:00Z","status":"Sent","smtpErrorCode":null,"roles":[]}]}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(OperatorInvitesResult.Failed(OperatorTeamFailure.Unexpected), api.listInvites())
        }

    @Test
    fun `an empty invite list is Loaded with no entries`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"invites":[]}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(OperatorInvitesResult.Loaded(emptyList()), api.listInvites())
        }

    @Test
    fun `an unrecognised status member fails the read as Unexpected, never a silent fallback`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"invites":[{"operatorInviteId":"x","email":"x@example.com","createdAt":"2026-09-20T10:00:00Z","expiresAt":"2026-09-27T10:00:00Z","status":"SomethingNew","smtpErrorCode":null,"roles":[],"effectiveStatus":"Pending","redeemedAt":null,"removedAt":null}]}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(OperatorInvitesResult.Failed(OperatorTeamFailure.Unexpected), api.listInvites())
        }

    @Test
    fun `a 200 that dropped the invite-list shape is Unexpected, never an empty list`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"somethingElseEntirely":true}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(OperatorInvitesResult.Failed(OperatorTeamFailure.Unexpected), api.listInvites())
        }

    @Test
    fun `a 5xx on the invite-list read is Unexpected`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(OperatorInvitesResult.Failed(OperatorTeamFailure.Unexpected), api.listInvites())
        }

    @Test
    fun `a dropped connection on the invite-list read is Transport`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(OperatorInvitesResult.Failed(OperatorTeamFailure.Transport), api.listInvites())
        }

    @Test
    fun `no active site on the invite-list read is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(OperatorInvitesResult.Failed(OperatorTeamFailure.Unexpected), api.listInvites())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    // ------------------------ POST .../operator-invites/{operatorInviteId}/revoke (`26-242`)

    @Test
    fun `a 204 revoke is Revoked, posted to the revoke path`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(RevokeInviteResult.Revoked, api.revokeInvite("inv-9"))
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/sites/$siteId/operator-invites/inv-9/revoke", requested)
        }

    @Test
    fun `a non-2xx revoke is Unexpected - the server's own already-redeemed guard is the real check`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Conflict) }

            assertEquals(RevokeInviteResult.Failed(OperatorTeamFailure.Unexpected), api.revokeInvite("inv-9"))
        }

    @Test
    fun `a dropped connection on revoke is Transport`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(RevokeInviteResult.Failed(OperatorTeamFailure.Transport), api.revokeInvite("inv-9"))
        }

    @Test
    fun `no active site on revoke is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(RevokeInviteResult.Failed(OperatorTeamFailure.Unexpected), api.revokeInvite("inv-9"))
            assertEquals("no active site must never reach the network", 0, calls)
        }

    // ------------------------------ POST .../operators/{operatorId}/role (`26-253`)

    @Test
    fun `a 204 role change is Changed, posted with the new role name in the body`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var sentBody = ""
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(ChangeOperatorRoleResult.Changed, api.changeOperatorRole("op-9", "Admin"))
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/sites/$siteId/operators/op-9/role", requested)
            assertTrue(sentBody.contains("\"roleName\":\"Admin\""))
        }

    @Test
    fun `a 409 IsLastManager role change is mapped by code to LastManager, never the detail`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Operator.IsLastManager","detail":"This site must always have a manager."}""",
                        HttpStatusCode.Conflict,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(ChangeOperatorRoleResult.LastManager, api.changeOperatorRole("op-9", "Operator"))
        }

    @Test
    fun `a 402 AdminLimitReached role change is mapped by code to AdminSeatFull`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Operator.AdminLimitReached","detail":"Admin limit reached."}""",
                        HttpStatusCode.PaymentRequired,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(ChangeOperatorRoleResult.AdminSeatFull, api.changeOperatorRole("op-9", "Admin"))
        }

    @Test
    fun `an unknown role-change refusal with a detail is shown verbatim`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Operator.RoleNotFound","detail":"Site has no role named 'Ghost'."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(
                ChangeOperatorRoleResult.Refused("Site has no role named 'Ghost'."),
                api.changeOperatorRole("op-9", "Ghost"),
            )
        }

    @Test
    fun `a role-change refusal with no problem-details body is a server error, never a fabricated string`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(ChangeOperatorRoleResult.Failed(NetworkFailure.ServerError(403)), api.changeOperatorRole("op-9", "Admin"))
        }

    @Test
    fun `a dropped connection on a role change is a transport failure`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(ChangeOperatorRoleResult.Failed(NetworkFailure.NoConnection), api.changeOperatorRole("op-9", "Admin"))
        }

    @Test
    fun `no active site on a role change is Failed, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(ChangeOperatorRoleResult.Failed(NetworkFailure.Unexpected), api.changeOperatorRole("op-9", "Admin"))
            assertEquals("no active site must never reach the network", 0, calls)
        }

    // ------------------------------ POST .../operators/{operatorId}/remove (`26-253`)

    @Test
    fun `a 204 removal is Removed, posted to the remove path`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(RemoveOperatorResult.Removed, api.removeOperator("op-9"))
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/sites/$siteId/operators/op-9/remove", requested)
        }

    @Test
    fun `a 409 IsLastManager removal is mapped by code to LastManager`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Operator.IsLastManager","detail":"This site must always have a manager."}""",
                        HttpStatusCode.Conflict,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(RemoveOperatorResult.LastManager, api.removeOperator("op-9"))
        }

    @Test
    fun `an already-removed operator surfaces its detail verbatim`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Operator.AlreadyRemoved","detail":"Operator op-9 has already been removed."}""",
                        HttpStatusCode.Conflict,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(RemoveOperatorResult.Refused("Operator op-9 has already been removed."), api.removeOperator("op-9"))
        }

    @Test
    fun `a dropped connection on a removal is a transport failure`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(RemoveOperatorResult.Failed(NetworkFailure.NoConnection), api.removeOperator("op-9"))
        }

    // ------------------------------ POST .../operators/{operatorId}/seat (`26-253`)

    @Test
    fun `a 204 seat toggle is Toggled, posted with the role and new value in the body`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var sentBody = ""
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(ToggleOperatorSeatResult.Toggled, api.toggleOperatorSeat("op-9", "Operator", holdsSeat = true))
            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/sites/$siteId/operators/op-9/seat", requested)
            assertTrue(sentBody.contains("\"roleName\":\"Operator\""))
            assertTrue(sentBody.contains("\"holdsSeat\":true"))
        }

    @Test
    fun `a 402 SeatLimitReached toggle is the Operator pool, mapped to SeatFull`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Operator.SeatLimitReached","detail":"No free operator seats."}""",
                        HttpStatusCode.PaymentRequired,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(ToggleOperatorSeatResult.SeatFull("Operator"), api.toggleOperatorSeat("op-9", "Operator", holdsSeat = true))
        }

    @Test
    fun `a 402 AdminLimitReached toggle is the Admin pool, mapped to SeatFull`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Operator.AdminLimitReached","detail":"No free admin seats."}""",
                        HttpStatusCode.PaymentRequired,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(ToggleOperatorSeatResult.SeatFull("Admin"), api.toggleOperatorSeat("op-9", "Admin", holdsSeat = true))
        }

    @Test
    fun `an unknown seat-toggle refusal with a detail is shown verbatim`() =
        runTest {
            val api =
                apiFor(siteId) {
                    respond(
                        """{"type":"Operator.NotFound","detail":"Operator op-9 was not found for this site."}""",
                        HttpStatusCode.NotFound,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(
                ToggleOperatorSeatResult.Refused("Operator op-9 was not found for this site."),
                api.toggleOperatorSeat("op-9", "Operator", holdsSeat = false),
            )
        }

    @Test
    fun `a dropped connection on a seat toggle is a transport failure`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(
                ToggleOperatorSeatResult.Failed(NetworkFailure.NoConnection),
                api.toggleOperatorSeat("op-9", "Operator", holdsSeat = true),
            )
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
