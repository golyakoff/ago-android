package ago.chat.android.channels

import ago.chat.android.core.domain.channels.ChannelConnectResult
import ago.chat.android.core.domain.channels.ChannelConnectionApi
import ago.chat.android.core.domain.channels.ChannelDisconnectResult
import ago.chat.android.core.domain.channels.ChannelKind
import ago.chat.android.core.domain.channels.ChannelStatus
import ago.chat.android.core.domain.channels.ChannelStatusResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * `26-189`/`C2`: [ChannelConnectViewModel]'s own state machine is already proven exhaustively through
 * [TelegramChannelViewModel] in `ChannelConnectViewModelTest` — this file only proves the one thing
 * [MaxChannelViewModel] itself contributes: that it fixes [ChannelKind.Max] and threads it through to
 * every port call, never Telegram's or VK's kind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaxChannelViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the status read asks for ChannelKind Max, never another channel's kind`() =
        runTest(dispatcher) {
            val api = FakeMaxApi(statusResult = ChannelStatusResult.Loaded(NOT_CONNECTED))
            MaxChannelViewModel(api, dispatcher)

            advanceUntilIdle()

            assertEquals(listOf(ChannelKind.Max), api.kindsSeen)
        }

    @Test
    fun `connecting and disconnecting both address ChannelKind Max`() =
        runTest(dispatcher) {
            val api = FakeMaxApi(statusResult = ChannelStatusResult.Loaded(VERIFIED))
            val viewModel = MaxChannelViewModel(api, dispatcher)
            advanceUntilIdle()

            api.statusResult = ChannelStatusResult.Loaded(NOT_CONNECTED)
            viewModel.disconnect()
            advanceUntilIdle()

            viewModel.connect("max-token-1")
            advanceUntilIdle()

            assertEquals(listOf("cc-max"), api.disconnectedCredentialIds)
            assertEquals(listOf("max-token-1"), api.connectedTokens)
            // Every call this scenario made - status reads, the disconnect, the connect - carried
            // ChannelKind.Max alone; never Telegram's or VK's kind.
            assertTrue(api.kindsSeen.isNotEmpty() && api.kindsSeen.all { it == ChannelKind.Max })
        }

    private companion object {
        val NOT_CONNECTED =
            ChannelStatus(
                connected = false,
                channelCredentialId = null,
                createdAt = null,
                verified = null,
                unreachable = false,
                refusalReason = null,
                checkedAt = Instant.parse("2026-09-26T12:00:00Z"),
            )
        val VERIFIED =
            ChannelStatus(
                connected = true,
                channelCredentialId = "cc-max",
                createdAt = Instant.parse("2026-09-01T00:00:00Z"),
                verified = true,
                unreachable = false,
                refusalReason = null,
                checkedAt = Instant.parse("2026-09-26T12:00:00Z"),
            )
    }
}

/** A minimal local fake — [ChannelConnectViewModelTest]'s own `FakeChannelConnectionApi` already covers
 * every state-machine branch through Telegram, so this one only needs to record which [ChannelKind] each
 * call carried. */
private class FakeMaxApi(
    var statusResult: ChannelStatusResult,
) : ChannelConnectionApi {
    val kindsSeen: MutableList<ChannelKind> = mutableListOf()
    val connectedTokens: MutableList<String> = mutableListOf()
    val disconnectedCredentialIds: MutableList<String> = mutableListOf()

    override suspend fun fetchStatus(kind: ChannelKind): ChannelStatusResult {
        kindsSeen += kind
        return statusResult
    }

    override suspend fun connect(
        kind: ChannelKind,
        token: String,
    ): ChannelConnectResult {
        kindsSeen += kind
        connectedTokens += token
        return ChannelConnectResult.Connected(reveal = null)
    }

    override suspend fun disconnect(
        kind: ChannelKind,
        channelCredentialId: String,
    ): ChannelDisconnectResult {
        kindsSeen += kind
        disconnectedCredentialIds += channelCredentialId
        return ChannelDisconnectResult.Disconnected
    }
}
