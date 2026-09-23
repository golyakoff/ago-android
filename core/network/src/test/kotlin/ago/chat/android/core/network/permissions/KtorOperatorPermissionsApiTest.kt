package ago.chat.android.core.network.permissions

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.permissions.PermissionsFetch
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
 * `26-16`: the same `GET /api/v1/operators/me` endpoint `KtorIdentityApiTest` already exercises for
 * its status code alone — this suite is about the body instead.
 */
class KtorOperatorPermissionsApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"

    @Test
    fun `a 200 with a permissions array is loaded, verbatim`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"operatorId":"op-1","siteId":"site-1","permissions":""" +
                            """["calendar:configure","customer:read"],"locale":"Ru","enabledModules":[]}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(
                PermissionsFetch.Loaded(setOf("calendar:configure", "customer:read")),
                api.fetchMyPermissions(),
            )
        }

    @Test
    fun `an operator holding nothing reads as a genuinely empty set, not a failure`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"operatorId":"op-1","siteId":"site-1","permissions":[],"locale":"Ru","enabledModules":[]}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(PermissionsFetch.Loaded(emptySet()), api.fetchMyPermissions())
        }

    @Test
    fun `an unknown field on the wire does not break the read`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"operatorId":"op-1","siteId":"site-1","permissions":["site:configure"],"locale":"Ru",""" +
                            """"enabledModules":["calendar"],"credentialsArePublished":false}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(PermissionsFetch.Loaded(setOf("site:configure")), api.fetchMyPermissions())
        }

    @Test
    fun `a 200 that dropped the permissions array is a failure, never an empty grant set`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"operatorId":"op-1","siteId":"site-1"}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(PermissionsFetch.Failed(NetworkFailure.Unexpected), api.fetchMyPermissions())
        }

    @Test
    fun `a 403 is a failure here, unlike the routing probe's own reading of the identical status`() =
        runTest {
            // `probeOperatorSeat` reads a `403` from this same endpoint as `ProbeOutcome.Refused` - a
            // real, terminal answer for a routing question. This class asks a different question
            // ("what may this operator do"), which a policy refusal has no answer for at all, so it is
            // read as a plain failure rather than a second `Refused`-shaped success case.
            val api = apiFor { respondError(HttpStatusCode.Forbidden) }

            assertEquals(PermissionsFetch.Failed(NetworkFailure.ServerError(403)), api.fetchMyPermissions())
        }

    @Test
    fun `a 5xx is a failure`() =
        runTest {
            val api = apiFor { respondError(HttpStatusCode.BadGateway) }

            assertEquals(
                PermissionsFetch.Failed(NetworkFailure.ServerError(502)),
                api.fetchMyPermissions(),
            )
        }

    @Test
    fun `a dropped connection is a failure, never an invented empty grant set`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(PermissionsFetch.Failed(NetworkFailure.NoConnection), api.fetchMyPermissions())
        }

    private fun apiFor(handler: MockRequestHandler): KtorOperatorPermissionsApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(),
                )
            }
        return KtorOperatorPermissionsApi(client, baseUrl)
    }
}
