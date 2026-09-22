package ago.chat.android.core.network.realtime

import ago.chat.android.core.network.InMemoryActiveSite
import ago.chat.android.core.network.MutableAccessTokenProvider
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * What can be proven about [OperatorHubConnection] with no server, no emulator and no real socket:
 * that it builds the underlying `HubConnection` **at most once** per instance. That single property
 * is what actually backs two of this item's own Done-when boxes - "backgrounding and foregrounding
 * the app leaves exactly one connection" and "rotating the device does not drop or duplicate the
 * connection" - because [OperatorHubConnection] is a `@Singleton` in `:app`'s object graph
 * (`di/AppModule`): an `Activity` recreated by a rotation, or a lifecycle observer calling `connect()`
 * again on every foreground transition, both reach the identical instance and therefore the identical
 * `ensureConnection()` guard proven here. Everything past this point - an actual socket opening,
 * negotiating, and surviving a real network drop - needs a real `Ago.Chat.Api` and a real operator
 * session, which this worktree could not obtain (see this item's own hand-off notes).
 */
class OperatorHubConnectionTest {
    @Test
    fun `the underlying HubConnection is built at most once`() {
        val connection =
            OperatorHubConnection(
                hubUrl = "https://chat-api.reserve-me.ru/hubs/operator",
                accessTokens = MutableAccessTokenProvider(token = "a-token"),
                activeSite = InMemoryActiveSite(),
            )

        val first = connection.ensureConnection()
        val second = connection.ensureConnection()

        assertSame("every later call reuses the identical HubConnection object", first, second)
    }

    @Test
    fun `building the connection touches no network - it is safe to call with a fake, unreachable host`() {
        // HubConnectionBuilder.create(...).build() only constructs client-side objects; negotiation
        // happens on start(), never here. This is what makes ensureConnection() safe to call from a
        // unit test at all.
        val connection =
            OperatorHubConnection(
                hubUrl = "https://this-host-does-not-exist.invalid/hubs/operator",
                accessTokens = MutableAccessTokenProvider(token = null),
                activeSite = InMemoryActiveSite(siteId = "11111111-1111-1111-1111-111111111111"),
            )

        connection.ensureConnection()
    }
}
