package ago.chat.android.channels

import ago.chat.android.core.domain.channels.ChannelConnectResult
import ago.chat.android.core.domain.channels.ChannelConnectionApi
import ago.chat.android.core.domain.channels.ChannelDisconnectResult
import ago.chat.android.core.domain.channels.ChannelKind
import ago.chat.android.core.domain.channels.ChannelStatus
import ago.chat.android.core.domain.channels.ChannelStatusResult
import ago.chat.android.core.domain.channels.VkReveal
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
 * `26-190`/`C3`: [ChannelConnectViewModel]'s own state machine is already proven exhaustively through
 * [TelegramChannelViewModel] in `ChannelConnectViewModelTest` (including the reveal-carries-through and
 * reveal-cleared-on-disconnect cases). This file proves the two things specific to
 * [VkChannelViewModel]: that it fixes [ChannelKind.Vk] and threads it through to every port call, and
 * that a real VK-shaped [VkReveal] from [KtorChannelConnectionApi][ago.chat.android.core.network.channels.KtorChannelConnectionApi]
 * reaches its `Connected` state exactly as the base class's own generic test already shows for any kind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VkChannelViewModelTest {
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
    fun `the status read asks for ChannelKind Vk, never another channel's kind`() =
        runTest(dispatcher) {
            val api = FakeVkApi(statusResult = ChannelStatusResult.Loaded(NOT_CONNECTED))
            VkChannelViewModel(api, dispatcher)

            advanceUntilIdle()

            assertEquals(listOf(ChannelKind.Vk), api.kindsSeen)
        }

    @Test
    fun `connecting carries the reveal into the Connected state and addresses ChannelKind Vk throughout`() =
        runTest(dispatcher) {
            val reveal = VkReveal(callbackUrl = "https://api.example/vk/callback", webhookSecret = "s3cr3t")
            val api =
                FakeVkApi(
                    statusResult = ChannelStatusResult.Loaded(NOT_CONNECTED),
                    connectResult = ChannelConnectResult.Connected(reveal),
                )
            val viewModel = VkChannelViewModel(api, dispatcher)
            advanceUntilIdle()

            api.statusResult = ChannelStatusResult.Loaded(VERIFIED)
            viewModel.connect("vk-token-1")
            advanceUntilIdle()

            assertEquals(reveal, (viewModel.state.value as ChannelConnectUiState.Connected).reveal)
            assertEquals(listOf("vk-token-1"), api.connectedTokens)
            assertTrue(api.kindsSeen.isNotEmpty() && api.kindsSeen.all { it == ChannelKind.Vk })
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
                channelCredentialId = "cc-vk",
                createdAt = Instant.parse("2026-09-01T00:00:00Z"),
                verified = true,
                unreachable = false,
                refusalReason = null,
                checkedAt = Instant.parse("2026-09-26T12:00:00Z"),
            )
    }
}

/** A minimal local fake, the identical shape [MaxChannelViewModelTest]'s own `FakeMaxApi` establishes -
 * [ChannelConnectViewModelTest]'s own `FakeChannelConnectionApi` already covers every state-machine branch
 * through Telegram, so this one only needs to record which [ChannelKind] each call carried and answer a
 * configurable connect result. */
private class FakeVkApi(
    var statusResult: ChannelStatusResult,
    var connectResult: ChannelConnectResult = ChannelConnectResult.Connected(reveal = null),
) : ChannelConnectionApi {
    val kindsSeen: MutableList<ChannelKind> = mutableListOf()
    val connectedTokens: MutableList<String> = mutableListOf()

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
        return connectResult
    }

    override suspend fun disconnect(
        kind: ChannelKind,
        channelCredentialId: String,
    ): ChannelDisconnectResult {
        kindsSeen += kind
        return ChannelDisconnectResult.Disconnected
    }
}
