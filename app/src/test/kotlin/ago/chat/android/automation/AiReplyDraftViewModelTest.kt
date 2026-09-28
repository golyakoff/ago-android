package ago.chat.android.automation

import ago.chat.android.core.domain.ai.AiReplyDraftApi
import ago.chat.android.core.domain.ai.AiReplyDraftStatus
import ago.chat.android.core.domain.ai.AiReplyDraftStatusResult
import ago.chat.android.core.domain.ai.AiReplyDraftWriteResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `26-246` (`ago-console`'s own `AiReplyDraftPage`): [AiReplyDraftViewModel]'s own read-then-toggle
 * surface — the identical `StandardTestDispatcher`/`Dispatchers.setMain` shape every sibling view-model
 * test in this app establishes ([OfflineAutoReplyViewModelTest]).
 *
 * The load-bearing cases here are: a successful toggle re-reads the server and renders *that* rather than
 * an optimistic flip (`savedTick` bumps, the new status is the re-read one), a refused toggle keeps the
 * last status and shows the server's own words, and the not-purchased / setup-incomplete faces come
 * straight off [AiReplyDraftStatus] with no local guessing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiReplyDraftViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --------------------------------------------------------------------------------------------- fetch

    @Test
    fun `starts Loading before the first answer comes back`() =
        runTest(dispatcher) {
            val api = FakeAiReplyDraftApi(hangFetch = true)
            val viewModel = viewModel(api)

            dispatcher.scheduler.runCurrent()

            assertEquals(AiReplyDraftUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a fully set-up status arrives and is seeded as loaded`() =
        runTest(dispatcher) {
            val api = FakeAiReplyDraftApi(fetchResult = AiReplyDraftStatusResult.Loaded(fullyEnabled()))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            val state = viewModel.state.value as AiReplyDraftUiState.Loaded
            assertTrue(state.status.setupComplete)
            assertTrue(state.status.enabled)
            assertFalse(state.busy)
            assertNull(state.actionError)
            assertEquals(0, state.savedTick)
        }

    @Test
    fun `a not-purchased account is loaded, not a failure - the not-available face`() =
        runTest(dispatcher) {
            val api = FakeAiReplyDraftApi(fetchResult = AiReplyDraftStatusResult.Loaded(notPurchased()))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            val state = viewModel.state.value as AiReplyDraftUiState.Loaded
            assertFalse(state.status.purchased)
            assertFalse(state.status.setupComplete)
        }

    @Test
    fun `a purchased-but-unconfigured account loads as setup-incomplete`() =
        runTest(dispatcher) {
            val api = FakeAiReplyDraftApi(fetchResult = AiReplyDraftStatusResult.Loaded(purchasedNotDeclared()))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            val state = viewModel.state.value as AiReplyDraftUiState.Loaded
            assertTrue(state.status.purchased)
            assertFalse(state.status.setupComplete)
        }

    @Test
    fun `a failed read becomes Failed carrying the adapter's own classification`() =
        runTest(dispatcher) {
            val api = FakeAiReplyDraftApi(fetchResult = AiReplyDraftStatusResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(AiReplyDraftUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `refresh re-reads and can recover from a failure`() =
        runTest(dispatcher) {
            val api = FakeAiReplyDraftApi(fetchResult = AiReplyDraftStatusResult.Failed(NetworkFailure.Unexpected))
            val viewModel = viewModel(api)
            advanceUntilIdle()
            assertTrue(viewModel.state.value is AiReplyDraftUiState.Failed)

            api.fetchResult = AiReplyDraftStatusResult.Loaded(fullyEnabled())
            viewModel.refresh()
            advanceUntilIdle()

            assertTrue(viewModel.state.value is AiReplyDraftUiState.Loaded)
        }

    // -------------------------------------------------------------------------------------------- toggle

    @Test
    fun `a successful toggle re-reads the server and renders that, bumping savedTick`() =
        runTest(dispatcher) {
            // Starts disabled; the write lands and the re-read comes back enabled - the screen must show
            // the re-read value, never a local optimistic flip.
            val api =
                FakeAiReplyDraftApi(
                    fetchResult = AiReplyDraftStatusResult.Loaded(fullyDisabled()),
                    setEnabledResult = AiReplyDraftWriteResult.Saved,
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            api.fetchResult = AiReplyDraftStatusResult.Loaded(fullyEnabled())
            viewModel.setEnabled(true)
            advanceUntilIdle()

            val state = viewModel.state.value as AiReplyDraftUiState.Loaded
            assertEquals(listOf(true), api.setEnabledCalls)
            assertTrue(state.status.enabled)
            assertEquals(1, state.savedTick)
            assertFalse(state.busy)
            assertNull(state.actionError)
        }

    @Test
    fun `a refused toggle keeps the last status and shows the server's own words`() =
        runTest(dispatcher) {
            val api =
                FakeAiReplyDraftApi(
                    fetchResult = AiReplyDraftStatusResult.Loaded(fullyDisabled()),
                    setEnabledResult = AiReplyDraftWriteResult.Refused("Сначала примите соглашение."),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.setEnabled(true)
            advanceUntilIdle()

            val state = viewModel.state.value as AiReplyDraftUiState.Loaded
            assertFalse("the write was refused, so the switch stays where it was", state.status.enabled)
            assertFalse(state.busy)
            assertEquals(AiReplyDraftActionError.ServerRefusal("Сначала примите соглашение."), state.actionError)
        }

    @Test
    fun `a transport failure on the toggle is Unavailable, status unchanged`() =
        runTest(dispatcher) {
            val api =
                FakeAiReplyDraftApi(
                    fetchResult = AiReplyDraftStatusResult.Loaded(fullyEnabled()),
                    setEnabledResult = AiReplyDraftWriteResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.setEnabled(false)
            advanceUntilIdle()

            val state = viewModel.state.value as AiReplyDraftUiState.Loaded
            assertTrue(state.status.enabled)
            assertFalse(state.busy)
            assertEquals(AiReplyDraftActionError.Unavailable(NetworkFailure.NoConnection), state.actionError)
        }

    @Test
    fun `a toggle whose write lands but whose re-read fails keeps the status and surfaces the read failure`() =
        runTest(dispatcher) {
            val api =
                FakeAiReplyDraftApi(
                    fetchResult = AiReplyDraftStatusResult.Loaded(fullyDisabled()),
                    setEnabledResult = AiReplyDraftWriteResult.Saved,
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            api.fetchResult = AiReplyDraftStatusResult.Failed(NetworkFailure.Unexpected)
            viewModel.setEnabled(true)
            advanceUntilIdle()

            val state = viewModel.state.value as AiReplyDraftUiState.Loaded
            assertFalse(state.busy)
            assertEquals(AiReplyDraftActionError.Unavailable(NetworkFailure.Unexpected), state.actionError)
        }

    @Test
    fun `a second toggle while one is already in flight is a no-op`() =
        runTest(dispatcher) {
            val api =
                FakeAiReplyDraftApi(
                    fetchResult = AiReplyDraftStatusResult.Loaded(fullyDisabled()),
                    hangSetEnabled = true,
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.setEnabled(true)
            dispatcher.scheduler.runCurrent()
            assertTrue((viewModel.state.value as AiReplyDraftUiState.Loaded).busy)

            viewModel.setEnabled(true)
            dispatcher.scheduler.runCurrent()

            assertEquals("the second toggle must never reach the network while the first is in flight", 1, api.setEnabledCalls.size)
        }

    private fun viewModel(api: FakeAiReplyDraftApi) = AiReplyDraftViewModel(api = api, ioDispatcher = dispatcher)

    private fun fullyEnabled() =
        AiReplyDraftStatus(
            purchased = true,
            enabled = true,
            effectiveFrom = "2026-09-28T10:00:00Z",
            agreementAccepted = true,
            basisDeclared = true,
        )

    private fun fullyDisabled() =
        AiReplyDraftStatus(purchased = true, enabled = false, effectiveFrom = null, agreementAccepted = true, basisDeclared = true)

    private fun notPurchased() =
        AiReplyDraftStatus(purchased = false, enabled = false, effectiveFrom = null, agreementAccepted = false, basisDeclared = false)

    private fun purchasedNotDeclared() =
        AiReplyDraftStatus(purchased = true, enabled = false, effectiveFrom = null, agreementAccepted = true, basisDeclared = false)

    private class FakeAiReplyDraftApi(
        var fetchResult: AiReplyDraftStatusResult = AiReplyDraftStatusResult.Failed(NetworkFailure.Unexpected),
        private val hangFetch: Boolean = false,
        var setEnabledResult: AiReplyDraftWriteResult = AiReplyDraftWriteResult.Failed(NetworkFailure.Unexpected),
        private val hangSetEnabled: Boolean = false,
    ) : AiReplyDraftApi {
        val setEnabledCalls: MutableList<Boolean> = mutableListOf()

        override suspend fun fetch(): AiReplyDraftStatusResult {
            if (hangFetch) awaitCancellation()
            return fetchResult
        }

        override suspend fun setEnabled(enabled: Boolean): AiReplyDraftWriteResult {
            setEnabledCalls.add(enabled)
            if (hangSetEnabled) awaitCancellation()
            return setEnabledResult
        }
    }
}
