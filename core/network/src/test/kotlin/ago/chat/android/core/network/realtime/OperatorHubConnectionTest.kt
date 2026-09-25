package ago.chat.android.core.network.realtime

import ago.chat.android.core.network.InMemoryActiveSite
import ago.chat.android.core.network.MutableAccessTokenProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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

    /**
     * `26-17`: the hub-side half of a site switch, proven at the same level as the two tests above —
     * no server, no emulator, no real socket. [OperatorHubConnection.reconnectToActiveSite] itself is
     * not called here (it calls `HubConnection.start()`, which is genuinely a network operation and
     * cannot be run against a fake host — the same reason [ensureConnection] is the only thing this
     * whole file can safely call). What is provably true without a socket is
     * [OperatorHubConnection.discardConnection]'s own contract: after it runs, [ensureConnection]
     * builds a *second* `HubConnection`, reading whatever [InMemoryActiveSite] reports at that later
     * point — never the value it was constructed with.
     */
    @Test
    fun `discarding the connection makes ensureConnection build a fresh HubConnection against the site active right now`() {
        val activeSite = InMemoryActiveSite(siteId = "11111111-1111-1111-1111-111111111111")
        val connection =
            OperatorHubConnection(
                hubUrl = "https://chat-api.reserve-me.ru/hubs/operator",
                accessTokens = MutableAccessTokenProvider(token = "a-token"),
                activeSite = activeSite,
            )

        val first = connection.ensureConnection()

        // The switch: a different site is selected, then the connection is discarded - the exact
        // two-step sequence `reconnectToActiveSite()` performs internally, minus the network-touching
        // `connect()` call that must not run here.
        activeSite.select("22222222-2222-2222-2222-222222222222")
        connection.discardConnection()
        val second = connection.ensureConnection()

        assertNotSame(
            "switching sites must rebuild the hub connection rather than keep the old site's socket",
            first,
            second,
        )
    }

    /**
     * `26-144`: what is provable about [OperatorHubConnection.getVisitorHistoryConversation] with no
     * server, no emulator and no real socket — the same ceiling every invoke-based method in this class
     * hits (the actual `HubConnection.invoke` needs a real, negotiated socket, exactly why
     * [OperatorHubConnection.joinConversation]/[OperatorHubConnection.loadOlderHistory] have no
     * invoke-level test here either). The read-only "open one past dialog" path is guarded by
     * `requireConnection()`, so calling it before [OperatorHubConnection.connect] fails fast with an
     * [IllegalStateException] rather than silently building or opening a socket of its own — the property
     * that keeps this a pure read against an already-owned connection, never a second connection lifecycle.
     */
    @Test
    fun `opening a past dialog before connect fails fast rather than silently opening a socket`() {
        val connection =
            OperatorHubConnection(
                hubUrl = "https://chat-api.reserve-me.ru/hubs/operator",
                accessTokens = MutableAccessTokenProvider(token = "a-token"),
                activeSite = InMemoryActiveSite(),
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                connection.getVisitorHistoryConversation(
                    conversationId = "c1",
                    historicalConversationId = "hist-1",
                    beforeSequence = null,
                    pageSize = 20,
                )
            }
        }
    }
}
