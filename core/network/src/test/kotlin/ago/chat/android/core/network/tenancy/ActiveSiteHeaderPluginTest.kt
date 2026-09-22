package ago.chat.android.core.network.tenancy

import ago.chat.android.core.network.InMemoryActiveSite
import ago.chat.android.core.network.MutableAccessTokenProvider
import ago.chat.android.core.network.installAgoRestDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `26-12`'s own Done-when: *"Every authenticated request carries `X-Ago-Active-Site` — asserted by a
 * `MockEngine` test over the client itself, not by reading call sites."*
 *
 * So these tests name **no endpoint of this product at all**. They ask the client for arbitrary
 * paths it has never heard of, on two different verbs, and assert the header arrived. A test that
 * enumerated `operators/me`, `me/tenancies` and `owner/sites` would only ever prove that those three
 * were remembered — which is precisely the property a per-call-site approach cannot give and a
 * plugin can.
 */
class ActiveSiteHeaderPluginTest {
    private val siteId = "11111111-1111-1111-1111-111111111111"

    @Test
    fun `an endpoint this test has never heard of still carries the active site`() =
        runTest {
            val seen = mutableListOf<String?>()
            val client = clientFor(InMemoryActiveSite(siteId), seen)

            client.get("https://chat-api.reserve-me.ru/api/v1/some/route/invented/by/a/later/item")
            client.post("https://chat-api.reserve-me.ru/api/v1/another/one?with=a&query=string")

            assertEquals(listOf(siteId, siteId), seen)
        }

    @Test
    fun `the header names the exact spelling the server reads`() {
        // `OperatorIdentityClaimsTransformation` (`ago-chat`) and `activeSite.ts` (`ago-console`)
        // both spell it this way. A test rather than a comment, because a header name is the kind of
        // string a refactor renames confidently and wrongly, and the server answers a request
        // carrying the wrong spelling exactly as it answers one carrying none.
        assertEquals("X-Ago-Active-Site", ACTIVE_SITE_HEADER_NAME)
    }

    @Test
    fun `no chosen tenancy means the header is absent, not empty`() =
        runTest {
            val seen = mutableListOf<String?>()
            val client = clientFor(InMemoryActiveSite(null), seen)

            client.get("https://chat-api.reserve-me.ru/api/v1/me/tenancies")

            // Absent and empty are different answers server-side: `ResolveOperatorIdentityHandler`
            // resolves an absent header against the identity's single tenancy, and refuses an empty
            // one outright because it matches no row.
            assertNull(seen.single())
        }

    @Test
    fun `a tenancy chosen after the client was built is still sent`() =
        runTest {
            val selection = InMemoryActiveSite(null)
            val seen = mutableListOf<String?>()
            val client = clientFor(selection, seen)

            client.get("https://chat-api.reserve-me.ru/api/v1/anything")
            selection.select(siteId)
            client.get("https://chat-api.reserve-me.ru/api/v1/anything")

            // The site picker runs *after* the client is constructed, and `26-17`'s switcher changes
            // it again mid-session. A plugin that captured the value at install time would send
            // nothing here for the rest of the process's life.
            assertEquals(listOf(null, siteId), seen)
        }

    private fun clientFor(
        selection: InMemoryActiveSite,
        seen: MutableList<String?>,
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                seen += request.headers[ACTIVE_SITE_HEADER_NAME]
                respond("", HttpStatusCode.OK)
            },
        ) {
            installAgoRestDefaults(
                accessTokens = MutableAccessTokenProvider(token = "any-token"),
                activeSite = selection,
            )
        }
}
