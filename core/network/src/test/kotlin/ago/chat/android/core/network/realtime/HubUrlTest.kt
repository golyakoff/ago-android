package ago.chat.android.core.network.realtime

import org.junit.Assert.assertEquals
import org.junit.Test

class HubUrlTest {
    @Test
    fun `no site known means no query string at all, not an empty one`() {
        assertEquals("https://chat-api.reserve-me.ru/hubs/operator", buildHubUrl("https://chat-api.reserve-me.ru/hubs/operator", null))
        assertEquals("https://chat-api.reserve-me.ru/hubs/operator", buildHubUrl("https://chat-api.reserve-me.ru/hubs/operator", ""))
    }

    @Test
    fun `a known site rides the connection URL as activeSite`() {
        val url =
            buildHubUrl(
                "https://chat-api.reserve-me.ru/hubs/operator",
                "11111111-1111-1111-1111-111111111111",
            )

        assertEquals(
            "https://chat-api.reserve-me.ru/hubs/operator?activeSite=11111111-1111-1111-1111-111111111111",
            url,
        )
    }

    @Test
    fun `the site id is URL-encoded`() {
        val url = buildHubUrl("https://chat-api.reserve-me.ru/hubs/operator", "a b&c")

        assertEquals("https://chat-api.reserve-me.ru/hubs/operator?activeSite=a+b%26c", url)
    }
}
