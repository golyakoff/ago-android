package ago.chat.android.channels

import ago.chat.android.core.domain.channels.ChannelConnectResult
import ago.chat.android.core.domain.channels.ChannelConnectionApi
import ago.chat.android.core.domain.channels.ChannelDisconnectResult
import ago.chat.android.core.domain.channels.ChannelKind
import ago.chat.android.core.domain.channels.ChannelStatus
import ago.chat.android.core.domain.channels.ChannelStatusResult
import ago.chat.android.core.domain.channels.VkReveal
import ago.chat.android.core.domain.net.NetworkFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * `26-188`: [ChannelConnectViewModel]'s own state machine, driven through [TelegramChannelViewModel] -
 * the cheapest concrete subclass to construct directly, the identical "test the base through its
 * thinnest real subclass" shape this file's own fake establishes rather than testing an `abstract class`
 * through an anonymous object.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelConnectViewModelTest {
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
    fun `starts Loading before the first answer comes back`() =
        runTest(dispatcher) {
            val viewModel = TelegramChannelViewModel(FakeChannelConnectionApi(hangFetch = true), dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(ChannelConnectUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a not-connected status is Disconnected, never a load failure`() =
        runTest(dispatcher) {
            val api = FakeChannelConnectionApi(statusResult = ChannelStatusResult.Loaded(NOT_CONNECTED))
            val viewModel = TelegramChannelViewModel(api, dispatcher)

            advanceUntilIdle()

            assertEquals(ChannelConnectUiState.Disconnected(connecting = false, connectError = null), viewModel.state.value)
        }

    @Test
    fun `the status read asks for this view model's own channel kind`() =
        runTest(dispatcher) {
            val api = FakeChannelConnectionApi(statusResult = ChannelStatusResult.Loaded(NOT_CONNECTED))
            TelegramChannelViewModel(api, dispatcher)

            advanceUntilIdle()

            assertEquals(listOf(ChannelKind.Telegram), api.kindsFetched)
        }

    @Test
    fun `a verified connected status carries every field through`() =
        runTest(dispatcher) {
            val api = FakeChannelConnectionApi(statusResult = ChannelStatusResult.Loaded(VERIFIED))
            val viewModel = TelegramChannelViewModel(api, dispatcher)

            advanceUntilIdle()

            assertEquals(
                ChannelConnectUiState.Connected(
                    verified = true,
                    unreachable = false,
                    refusalReason = null,
                    createdAt = Instant.parse("2026-09-01T00:00:00Z"),
                    checkedAt = Instant.parse("2026-09-26T12:00:00Z"),
                    channelCredentialId = "cc1",
                    reveal = null,
                    disconnecting = false,
                    disconnectError = null,
                ),
                viewModel.state.value,
            )
        }

    @Test
    fun `a connected status with no credential id is a shape failure, never an unusable connected screen`() =
        runTest(dispatcher) {
            val shapeless = VERIFIED.copy(channelCredentialId = null)
            val api = FakeChannelConnectionApi(statusResult = ChannelStatusResult.Loaded(shapeless))
            val viewModel = TelegramChannelViewModel(api, dispatcher)

            advanceUntilIdle()

            assertEquals(ChannelConnectUiState.Failed(NetworkFailure.Unexpected), viewModel.state.value)
        }

    @Test
    fun `a status read failure is shown as Failed with the classified reason`() =
        runTest(dispatcher) {
            val api = FakeChannelConnectionApi(statusResult = ChannelStatusResult.Failed(NetworkFailure.NoConnection))
            val viewModel = TelegramChannelViewModel(api, dispatcher)

            advanceUntilIdle()

            assertEquals(ChannelConnectUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `connecting sends the typed token and reloads status on success, never an optimistic flip`() =
        runTest(dispatcher) {
            val api = FakeChannelConnectionApi(statusResult = ChannelStatusResult.Loaded(NOT_CONNECTED))
            val viewModel = TelegramChannelViewModel(api, dispatcher)
            advanceUntilIdle()

            api.statusResult = ChannelStatusResult.Loaded(VERIFIED)
            viewModel.connect("bot-token-1")
            advanceUntilIdle()

            assertEquals(listOf("bot-token-1"), api.connectedTokens)
            assertEquals((viewModel.state.value as ChannelConnectUiState.Connected).channelCredentialId, "cc1")
            // The authoritative answer is always the next status read - never the connect's own echo.
            assertEquals(2, api.fetchCount)
        }

    @Test
    fun `a bad-token refusal is shown verbatim, never dressed up as a network failure`() =
        runTest(dispatcher) {
            val api =
                FakeChannelConnectionApi(
                    statusResult = ChannelStatusResult.Loaded(NOT_CONNECTED),
                    connectResult = ChannelConnectResult.Refused("Токен бота недействителен."),
                )
            val viewModel = TelegramChannelViewModel(api, dispatcher)
            advanceUntilIdle()

            viewModel.connect("bad-token")
            advanceUntilIdle()

            assertEquals(
                ChannelConnectUiState.Disconnected(
                    connecting = false,
                    connectError = ChannelActionError.ServerRefusal("Токен бота недействителен."),
                ),
                viewModel.state.value,
            )
            // A refusal never reloads: there is nothing new to read after a rejected token.
            assertEquals(1, api.fetchCount)
        }

    @Test
    fun `a transport failure on connect is not dressed up as a refusal`() =
        runTest(dispatcher) {
            val api =
                FakeChannelConnectionApi(
                    statusResult = ChannelStatusResult.Loaded(NOT_CONNECTED),
                    connectResult = ChannelConnectResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = TelegramChannelViewModel(api, dispatcher)
            advanceUntilIdle()

            viewModel.connect("t")
            advanceUntilIdle()

            assertEquals(
                ChannelActionError.Unavailable(NetworkFailure.NoConnection),
                (viewModel.state.value as ChannelConnectUiState.Disconnected).connectError,
            )
        }

    @Test
    fun `a second connect while one is already in flight is a no-op`() =
        runTest(dispatcher) {
            val api = FakeChannelConnectionApi(statusResult = ChannelStatusResult.Loaded(NOT_CONNECTED), hangConnect = true)
            val viewModel = TelegramChannelViewModel(api, dispatcher)
            advanceUntilIdle()

            viewModel.connect("first")
            dispatcher.scheduler.runCurrent()
            viewModel.connect("second")
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.connectAttempts)
            assertTrue((viewModel.state.value as ChannelConnectUiState.Disconnected).connecting)
        }

    @Test
    fun `a vk-shaped reveal from connect carries into the next Connected state`() =
        runTest(dispatcher) {
            val reveal = VkReveal(callbackUrl = "https://api.example/vk/callback", webhookSecret = "s3cr3t")
            val api =
                FakeChannelConnectionApi(
                    statusResult = ChannelStatusResult.Loaded(NOT_CONNECTED),
                    connectResult = ChannelConnectResult.Connected(reveal),
                )
            val viewModel = TelegramChannelViewModel(api, dispatcher)
            advanceUntilIdle()

            api.statusResult = ChannelStatusResult.Loaded(VERIFIED)
            viewModel.connect("t")
            advanceUntilIdle()

            assertEquals(reveal, (viewModel.state.value as ChannelConnectUiState.Connected).reveal)
        }

    @Test
    fun `a reveal is cleared on disconnect, so the next connect starts from nothing shown`() =
        runTest(dispatcher) {
            val reveal = VkReveal(callbackUrl = "https://api.example/vk/callback", webhookSecret = "s3cr3t")
            val api =
                FakeChannelConnectionApi(
                    statusResult = ChannelStatusResult.Loaded(NOT_CONNECTED),
                    connectResult = ChannelConnectResult.Connected(reveal),
                )
            val viewModel = TelegramChannelViewModel(api, dispatcher)
            advanceUntilIdle()
            api.statusResult = ChannelStatusResult.Loaded(VERIFIED)
            viewModel.connect("t")
            advanceUntilIdle()
            check((viewModel.state.value as ChannelConnectUiState.Connected).reveal == reveal)

            api.statusResult = ChannelStatusResult.Loaded(NOT_CONNECTED)
            viewModel.disconnect()
            advanceUntilIdle()

            api.statusResult = ChannelStatusResult.Loaded(VERIFIED)
            api.connectResult = ChannelConnectResult.Connected(reveal = null)
            viewModel.connect("t-again")
            advanceUntilIdle()

            // A stale reveal surviving a disconnect would show a secret pair that no longer means
            // anything - a fresh connect always starts from "nothing shown yet".
            assertNull((viewModel.state.value as ChannelConnectUiState.Connected).reveal)
        }

    @Test
    fun `disconnecting reloads status on success and sends the connected credential id`() =
        runTest(dispatcher) {
            val api = FakeChannelConnectionApi(statusResult = ChannelStatusResult.Loaded(VERIFIED))
            val viewModel = TelegramChannelViewModel(api, dispatcher)
            advanceUntilIdle()

            api.statusResult = ChannelStatusResult.Loaded(NOT_CONNECTED)
            viewModel.disconnect()
            advanceUntilIdle()

            assertEquals(listOf("cc1"), api.disconnectedCredentialIds)
            assertEquals(ChannelConnectUiState.Disconnected(connecting = false, connectError = null), viewModel.state.value)
        }

    @Test
    fun `a disconnect refusal keeps the connected state on screen with the refusal attached`() =
        runTest(dispatcher) {
            val api =
                FakeChannelConnectionApi(
                    statusResult = ChannelStatusResult.Loaded(VERIFIED),
                    disconnectResult = ChannelDisconnectResult.Refused("Канал уже отключён."),
                )
            val viewModel = TelegramChannelViewModel(api, dispatcher)
            advanceUntilIdle()

            viewModel.disconnect()
            advanceUntilIdle()

            val state = viewModel.state.value as ChannelConnectUiState.Connected
            assertEquals(ChannelActionError.ServerRefusal("Канал уже отключён."), state.disconnectError)
            assertEquals(false, state.disconnecting)
            assertEquals("cc1", state.channelCredentialId)
            // A refusal never reloads: the operator is still looking at the same connected channel.
            assertEquals(1, api.fetchCount)
        }

    @Test
    fun `dismissing a disconnect error clears only the error, keeping the rest of the connected state`() =
        runTest(dispatcher) {
            val api =
                FakeChannelConnectionApi(
                    statusResult = ChannelStatusResult.Loaded(VERIFIED),
                    disconnectResult = ChannelDisconnectResult.Refused("Канал уже отключён."),
                )
            val viewModel = TelegramChannelViewModel(api, dispatcher)
            advanceUntilIdle()
            viewModel.disconnect()
            advanceUntilIdle()

            viewModel.dismissDisconnectError()

            val state = viewModel.state.value as ChannelConnectUiState.Connected
            assertNull(state.disconnectError)
            assertEquals("cc1", state.channelCredentialId)
        }

    @Test
    fun `a second disconnect while one is already in flight is a no-op`() =
        runTest(dispatcher) {
            val api = FakeChannelConnectionApi(statusResult = ChannelStatusResult.Loaded(VERIFIED), hangDisconnect = true)
            val viewModel = TelegramChannelViewModel(api, dispatcher)
            advanceUntilIdle()

            viewModel.disconnect()
            dispatcher.scheduler.runCurrent()
            viewModel.disconnect()
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.disconnectAttempts)
            assertTrue((viewModel.state.value as ChannelConnectUiState.Connected).disconnecting)
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
                channelCredentialId = "cc1",
                createdAt = Instant.parse("2026-09-01T00:00:00Z"),
                verified = true,
                unreachable = false,
                refusalReason = null,
                checkedAt = Instant.parse("2026-09-26T12:00:00Z"),
            )
    }
}

/** The identical hand-written fake shape `FakeWorkingHoursApi` establishes - no mocking framework, so
 * "what was actually sent" assertions read off plain fields rather than a verification DSL.
 * [statusResult]/[connectResult]/[disconnectResult] are `var`s, not constructor-fixed, so a test can
 * change what the next read/write answers mid-scenario (e.g. after a successful connect, the next status
 * read should show the channel connected). */
private class FakeChannelConnectionApi(
    var statusResult: ChannelStatusResult = ChannelStatusResult.Loaded(NOT_CONNECTED_DEFAULT),
    var connectResult: ChannelConnectResult = ChannelConnectResult.Connected(reveal = null),
    var disconnectResult: ChannelDisconnectResult = ChannelDisconnectResult.Disconnected,
    private val hangFetch: Boolean = false,
    private val hangConnect: Boolean = false,
    private val hangDisconnect: Boolean = false,
) : ChannelConnectionApi {
    var fetchCount: Int = 0
    var connectAttempts: Int = 0
    var disconnectAttempts: Int = 0
    val kindsFetched: MutableList<ChannelKind> = mutableListOf()
    val connectedTokens: MutableList<String> = mutableListOf()
    val disconnectedCredentialIds: MutableList<String> = mutableListOf()

    override suspend fun fetchStatus(kind: ChannelKind): ChannelStatusResult {
        fetchCount++
        kindsFetched += kind
        if (hangFetch) awaitCancellation()
        return statusResult
    }

    override suspend fun connect(
        kind: ChannelKind,
        token: String,
    ): ChannelConnectResult {
        connectAttempts++
        connectedTokens += token
        if (hangConnect) awaitCancellation()
        return connectResult
    }

    override suspend fun disconnect(
        kind: ChannelKind,
        channelCredentialId: String,
    ): ChannelDisconnectResult {
        disconnectAttempts++
        disconnectedCredentialIds += channelCredentialId
        if (hangDisconnect) awaitCancellation()
        return disconnectResult
    }
}

private val NOT_CONNECTED_DEFAULT =
    ChannelStatus(
        connected = false,
        channelCredentialId = null,
        createdAt = null,
        verified = null,
        unreachable = false,
        refusalReason = null,
        checkedAt = Instant.parse("2026-09-26T12:00:00Z"),
    )
