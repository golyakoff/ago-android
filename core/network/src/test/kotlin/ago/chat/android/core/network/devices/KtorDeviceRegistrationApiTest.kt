package ago.chat.android.core.network.devices

import ago.chat.android.core.domain.devices.PushProvider
import ago.chat.android.core.network.InMemoryActiveSite
import ago.chat.android.core.network.MutableAccessTokenProvider
import ago.chat.android.core.network.installAgoRestDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
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
 * `26-06`: the client half of `Ago.Chat.Api.Me.MeDeviceEndpoints`, driven through the real client
 * configuration and a `MockEngine` - the identical shape `KtorConversationsApiTest` already
 * establishes.
 */
class KtorDeviceRegistrationApiTest {
    private val baseUrl = "https://chat-api.reserve-me.ru"

    // ---------------------------------------------------------- PUT /api/v1/me/devices/{id}

    @Test
    fun `a 204 is true, and the body carries the fixed provider and platform alongside the real token`() =
        runTest {
            val requested = mutableListOf<Pair<HttpMethod, String>>()
            var sentBody = ""
            val api =
                apiFor(recordTo = requested) { request ->
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(true, api.register("install-1", "the-real-push-token", PushProvider.RuStore))
            assertEquals(HttpMethod.Put to "$baseUrl/api/v1/me/devices/install-1", requested.single())
            assertTrue(sentBody.contains("\"provider\":\"rustore\""))
            assertTrue(sentBody.contains("\"platform\":\"android\""))
            assertTrue(sentBody.contains("\"token\":\"the-real-push-token\""))
        }

    @Test
    fun `26-100 - an fcm provider renders its own wire value, not rustore's`() =
        runTest {
            var sentBody = ""
            val api =
                apiFor { request ->
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(true, api.register("install-1", "an-fcm-token", PushProvider.Fcm))
            assertTrue(sentBody.contains("\"provider\":\"fcm\""))
            assertTrue(sentBody.contains("\"platform\":\"android\""))
        }

    @Test
    fun `the same installationId is sent under two different active sites - one row per tenancy relies on the header alone`() =
        runTest {
            val requested = mutableListOf<Pair<HttpMethod, String>>()
            val activeSite = InMemoryActiveSite("site-a")
            val api = apiFor(activeSite = activeSite, recordTo = requested) { respond("", HttpStatusCode.NoContent) }

            assertTrue(api.register("install-1", "token-a", PushProvider.RuStore))
            activeSite.select("site-b")
            assertTrue(api.register("install-1", "token-b", PushProvider.RuStore))

            // This class sends the identical URL and installationId both times - `ActiveSiteHeaderPluginTest`
            // is where the header itself (the thing that actually makes these two rows, not one) is proven;
            // this test only proves this adapter never collapses the two calls into one on its own.
            assertEquals(
                listOf(
                    HttpMethod.Put to "$baseUrl/api/v1/me/devices/install-1",
                    HttpMethod.Put to "$baseUrl/api/v1/me/devices/install-1",
                ),
                requested,
            )
        }

    @Test
    fun `a 400 for an unknown provider is false, not thrown`() =
        runTest {
            val api =
                apiFor {
                    respond(
                        """{"type":"OperatorDevice.InvalidProvider","detail":"'x' is not a known push provider."}""",
                        HttpStatusCode.BadRequest,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(false, api.register("install-1", "token", PushProvider.RuStore))
        }

    @Test
    fun `a dropped connection on register is false, not a thrown exception`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(false, api.register("install-1", "token", PushProvider.RuStore))
        }

    // ------------------------------------------------------- DELETE /api/v1/me/devices/{id}

    @Test
    fun `a 204 revoke is true`() =
        runTest {
            val requested = mutableListOf<Pair<HttpMethod, String>>()
            val api = apiFor(recordTo = requested) { respond("", HttpStatusCode.NoContent) }

            assertEquals(true, api.revoke("install-1"))
            assertEquals(HttpMethod.Delete to "$baseUrl/api/v1/me/devices/install-1", requested.single())
        }

    @Test
    fun `revoke of a row that never existed is still true - DELETE's own idempotence`() =
        runTest {
            // The server always answers 204 here (`MeDeviceEndpoints.HandleRevokeAsync`'s own doc
            // comment: "Always NoContent"), so this is the one status this adapter ever actually sees.
            val api = apiFor { respond("", HttpStatusCode.NoContent) }

            assertEquals(true, api.revoke("never-registered"))
        }

    @Test
    fun `a dropped connection on revoke is false, not a thrown exception`() =
        runTest {
            val api = apiFor { throw IOException("unexpected end of stream") }

            assertEquals(false, api.revoke("install-1"))
        }

    private fun apiFor(
        activeSite: InMemoryActiveSite = InMemoryActiveSite(),
        recordTo: MutableList<Pair<HttpMethod, String>>? = null,
        handler: MockRequestHandler,
    ): KtorDeviceRegistrationApi {
        val client =
            HttpClient(
                MockEngine { request ->
                    recordTo?.add(request.method to request.url.toString())
                    handler(request)
                },
            ) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = activeSite,
                )
            }
        return KtorDeviceRegistrationApi(client, baseUrl)
    }
}
