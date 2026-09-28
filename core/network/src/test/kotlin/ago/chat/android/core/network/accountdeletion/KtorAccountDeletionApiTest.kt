package ago.chat.android.core.network.accountdeletion

import ago.chat.android.core.domain.accountdeletion.EraseAccountResult
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.network.InMemoryActiveSite
import ago.chat.android.core.network.MutableAccessTokenProvider
import ago.chat.android.core.network.installAgoRestDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * `26-252` (`ago-console`'s own `sitesApi.ts` `eraseSite`): the erase request's own `202`/`detail` mapping —
 * driven through the real client configuration and a `MockEngine`, the identical shape
 * [ago.chat.android.core.network.siteexport.KtorSiteExportApiTest] establishes.
 *
 * `baseUrl` is the RFC 2606 reserved `.invalid` TLD, never a routable host — CLAUDE.md forbids a real
 * endpoint in any repository, test fixtures included.
 */
class KtorAccountDeletionApiTest {
    private val baseUrl = "https://chat-api.example.invalid"

    @Test
    fun `erasing posts to the bare sites-erase path with no site id`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            val api =
                apiWith { request ->
                    requested = request.method to request.url.toString()
                    respond("", HttpStatusCode.Accepted)
                }

            val result = api.eraseAccount()

            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/sites/erase", requested)
            assertEquals(EraseAccountResult.Requested, result)
        }

    @Test
    fun `any 2xx is Requested`() =
        runTest {
            val api = apiWith { respond("", HttpStatusCode.OK) }

            assertEquals(EraseAccountResult.Requested, api.eraseAccount())
        }

    @Test
    fun `a refusal with a problem-details body is rendered as that exact refusal`() =
        runTest {
            val api =
                apiWith {
                    respond(
                        """{"type":"Auth.Forbidden","detail":"У вас нет права удалить этот аккаунт."}""",
                        HttpStatusCode.Forbidden,
                        headersOf("Content-Type", "application/problem+json"),
                    )
                }

            assertEquals(
                EraseAccountResult.Refused("У вас нет права удалить этот аккаунт."),
                api.eraseAccount(),
            )
        }

    @Test
    fun `a refusal with no problem-details body classifies as a server error, never a fabricated string`() =
        runTest {
            val api = apiWith { respondError(HttpStatusCode.Forbidden) }

            assertEquals(EraseAccountResult.Failed(NetworkFailure.ServerError(403)), api.eraseAccount())
        }

    @Test
    fun `a 5xx with no detail is a server error`() =
        runTest {
            val api = apiWith { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(EraseAccountResult.Failed(NetworkFailure.ServerError(503)), api.eraseAccount())
        }

    @Test
    fun `a dropped connection is a transport failure`() =
        runTest {
            val api = apiWith { throw IOException("unexpected end of stream") }

            assertEquals(EraseAccountResult.Failed(NetworkFailure.NoConnection), api.eraseAccount())
        }

    // ------------------------------------------------------------------------------------ fixtures

    private fun apiWith(handler: MockRequestHandler): KtorAccountDeletionApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite("site-123"),
                )
            }
        return KtorAccountDeletionApi(client, baseUrl)
    }
}
