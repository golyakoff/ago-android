package ago.chat.android.core.network.auth

import ago.chat.android.core.network.InMemoryActiveSite
import ago.chat.android.core.network.MutableAccessTokenProvider
import ago.chat.android.core.network.installAgoRestDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `26-12`: the two properties `5-16` is about, asserted over the **production client
 * configuration** (`installAgoRestDefaults`) rather than over a hand-built client that could drift
 * from it.
 *
 * The first test is deliberately written as a regression test for a shape rather than for a bug:
 * mutate the token between two calls and assert the second request carried the new one. A client
 * that captured its token at construction — Ktor's own `Auth`/`bearer` provider included, which
 * caches until a `401` — fails it.
 */
class BearerTokenPluginTest {
    @Test
    fun `the token is read fresh on every request, never captured at construction`() =
        runTest {
            val tokens = MutableAccessTokenProvider(token = "first-token")
            val sent = mutableListOf<String?>()
            val client = clientFor(tokens, sent) { respond("", HttpStatusCode.OK) }

            client.get("https://chat-api.reserve-me.ru/api/v1/first")
            tokens.token = "rotated-by-appauth-in-the-background"
            client.get("https://chat-api.reserve-me.ru/api/v1/second")

            // The second element is the whole assertion: a client that captured its token at
            // construction - Ktor's own `Auth`/`bearer` provider included, which caches until a
            // `401` - sends "Bearer first-token" twice and passes every other check in this file.
            assertEquals("Bearer first-token", sent[0])
            assertEquals("Bearer rotated-by-appauth-in-the-background", sent[1])
            assertEquals("one read per request, no cache in between", 2, tokens.currentReads)
        }

    @Test
    fun `a 401 forces a renewal and the retried request carries the new token`() =
        runTest {
            val tokens =
                MutableAccessTokenProvider(
                    token = "expired-token",
                    renewal = { "renewed-token" },
                )
            val sent = mutableListOf<String?>()
            val client =
                clientFor(tokens, sent) { request ->
                    // The server's side of the contract: it refuses the stale credential and
                    // accepts the renewed one. Keyed on what was actually sent, so a retry that
                    // re-sent the old token would keep getting 401 and the test would see it.
                    if (request.headers[HttpHeaders.Authorization] == "Bearer renewed-token") {
                        respond("", HttpStatusCode.OK)
                    } else {
                        respond("", HttpStatusCode.Unauthorized)
                    }
                }

            val response = client.get("https://chat-api.reserve-me.ru/api/v1/operators/me")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("exactly one retry, not a loop", 2, sent.size)
            assertEquals("Bearer expired-token", sent[0])
            assertEquals("Bearer renewed-token", sent[1])
            assertEquals(1, tokens.refreshes)
        }

    @Test
    fun `a 401 that renewal cannot fix is returned to the caller rather than retried forever`() =
        runTest {
            val tokens = MutableAccessTokenProvider(token = "dead-token", renewal = { null })
            val sent = mutableListOf<String?>()
            val client = clientFor(tokens, sent) { respond("", HttpStatusCode.Unauthorized) }

            val response = client.get("https://chat-api.reserve-me.ru/api/v1/operators/me")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("no retry is attempted when there is no new token to try", 1, sent.size)
            assertEquals(1, tokens.refreshes)
        }

    @Test
    fun `a second 401 after a successful renewal is not retried again`() =
        runTest {
            val tokens = MutableAccessTokenProvider(token = "a", renewal = { "b" })
            val sent = mutableListOf<String?>()
            val client = clientFor(tokens, sent) { respond("", HttpStatusCode.Unauthorized) }

            val response = client.get("https://chat-api.reserve-me.ru/api/v1/operators/me")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(2, sent.size)
            assertEquals(1, tokens.refreshes)
        }

    @Test
    fun `no session means no Authorization header at all, rather than an empty one`() =
        runTest {
            val tokens = MutableAccessTokenProvider(token = null)
            val sent = mutableListOf<String?>()
            val client = clientFor(tokens, sent) { respond("", HttpStatusCode.OK) }

            client.get("https://chat-api.reserve-me.ru/api/v1/me/tenancies")

            assertNull(sent.single())
        }

    private fun clientFor(
        tokens: MutableAccessTokenProvider,
        sent: MutableList<String?>,
        handler: MockRequestHandler,
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                sent += request.headers[HttpHeaders.Authorization]
                handler(request)
            },
        ) {
            installAgoRestDefaults(accessTokens = tokens, activeSite = InMemoryActiveSite())
        }
}
