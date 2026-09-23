package ago.chat.android.core.network.identity

import ago.chat.android.core.domain.identity.ProbeOutcome
import ago.chat.android.core.domain.identity.Tenancy
import ago.chat.android.core.domain.identity.TenancyListing
import ago.chat.android.core.domain.net.NetworkFailure
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
 * `26-12`: the status-code-to-meaning mapping, which is the one place a `403` may be read as "no
 * operator seat" and a `5xx` may not be read as anything at all.
 *
 * Driven through the real client configuration and a `MockEngine`, so these are assertions about
 * what `PostSignInRouter` will actually be handed against a server answering this way.
 */
class KtorIdentityApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"

    // ------------------------------------------------------------------- GET /api/v1/operators/me

    @Test
    fun `a 200 from the seat probe is accepted`() =
        runTest {
            val api = apiFor { respond("{}", HttpStatusCode.OK) }

            assertEquals(ProbeOutcome.Accepted, api.probeOperatorSeat())
        }

    @Test
    fun `a 403 from the seat probe is a refusal - the policy's own answer`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.Forbidden) }

            assertEquals(ProbeOutcome.Refused, api.probeOperatorSeat())
        }

    @Test
    fun `a 401 from the seat probe is not an answer, even though the console folds it in elsewhere`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.Unauthorized) }

            assertEquals(
                ProbeOutcome.Unanswered(NetworkFailure.ServerError(401)),
                api.probeOperatorSeat(),
            )
        }

    @Test
    fun `a 5xx from the seat probe is not an answer`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.BadGateway) }

            assertEquals(
                ProbeOutcome.Unanswered(NetworkFailure.ServerError(502)),
                api.probeOperatorSeat(),
            )
        }

    @Test
    fun `a dropped connection during the seat probe is not an answer`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(ProbeOutcome.Unanswered(NetworkFailure.NoConnection), api.probeOperatorSeat())
        }

    // -------------------------------------------------------------- GET /api/v1/owner/sites?limit=1

    @Test
    fun `the owner probe asks for a single row and reads only whether it was admitted`() =
        runTest {
            val asked = mutableListOf<String>()
            val api =
                apiFor(recordTo = asked) {
                    respond(
                        """{"sites":[],"nextBefore":null,"recentWindowDays":7,"matchingSites":0,"totalSites":0}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(ProbeOutcome.Accepted, api.probeOwnerEligibility())
            assertEquals("$baseUrl/api/v1/owner/sites?limit=1", asked.single())
        }

    @Test
    fun `a 403 from the owner probe is the refusal that routes to registration`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.Forbidden) }

            assertEquals(ProbeOutcome.Refused, api.probeOwnerEligibility())
        }

    @Test
    fun `a 401 from the owner probe is not read as a refusal`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.Unauthorized) }

            // `ownerApi.ts` folds 401 into "not-authorized" because it is deciding whether to render
            // a screen. This is deciding what kind of caller this is, and an identity that was
            // authenticated enough to be handed a 403 one request earlier cannot be unauthenticated
            // now - so it is evidence something is wrong, never evidence of a new registrant.
            assertEquals(
                ProbeOutcome.Unanswered(NetworkFailure.ServerError(401)),
                api.probeOwnerEligibility(),
            )
        }

    // ------------------------------------------------------------------ GET /api/v1/me/tenancies

    @Test
    fun `tenancies are read in the order the server sent them`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """
                        {"tenancies":[
                          {"siteId":"11111111-1111-1111-1111-111111111111","siteName":"Один магазин"},
                          {"siteId":"22222222-2222-2222-2222-222222222222","siteName":"Ярмарка"}
                        ]}
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(
                TenancyListing.Known(
                    listOf(
                        Tenancy("11111111-1111-1111-1111-111111111111", "Один магазин"),
                        Tenancy("22222222-2222-2222-2222-222222222222", "Ярмарка"),
                    ),
                ),
                api.listMyTenancies(),
            )
        }

    @Test
    fun `an identity with no tenancy reads as an empty list, not as a failure`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"tenancies":[]}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(TenancyListing.Known(emptyList()), api.listMyTenancies())
        }

    @Test
    fun `an unknown field on the wire does not break the read`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"tenancies":[{"siteId":"s1","siteName":"Ш","tierAddedByALaterItem":"free"}],"pageOf":1}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(TenancyListing.Known(listOf(Tenancy("s1", "Ш"))), api.listMyTenancies())
        }

    @Test
    fun `a 200 that dropped the array is a call that did not answer, never an identity with no shops`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"somethingElseEntirely":true}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            val listing = api.listMyTenancies()

            // This is `ago-console`'s `shapeGuard.ts` lesson: a dropped array read as "no tenancies"
            // looks exactly like a real pre-onboarding identity, and would route a working operator
            // into the registration arm.
            assertEquals(TenancyListing.Unanswered(NetworkFailure.Unexpected), listing)
        }

    @Test
    fun `a 5xx on the tenancy read is not an empty list`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(
                TenancyListing.Unanswered(NetworkFailure.ServerError(503)),
                api.listMyTenancies(),
            )
        }

    private fun apiFor(
        recordTo: MutableList<String>? = null,
        handler: MockRequestHandler,
    ): KtorIdentityApi {
        val client =
            HttpClient(
                MockEngine { request ->
                    recordTo?.add(request.url.toString())
                    handler(request)
                },
            ) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorIdentityApi(client, baseUrl)
    }
}
