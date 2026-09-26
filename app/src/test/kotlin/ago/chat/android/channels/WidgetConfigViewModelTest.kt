package ago.chat.android.channels

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.widgetconfig.ChannelSwitcherIconSize
import ago.chat.android.core.domain.widgetconfig.ChannelSwitcherPlacement
import ago.chat.android.core.domain.widgetconfig.WidgetAutoOpenDelay
import ago.chat.android.core.domain.widgetconfig.WidgetConfig
import ago.chat.android.core.domain.widgetconfig.WidgetConfigApi
import ago.chat.android.core.domain.widgetconfig.WidgetConfigResult
import ago.chat.android.core.domain.widgetconfig.WidgetConfigWriteResult
import ago.chat.android.core.domain.widgetconfig.WidgetLocale
import ago.chat.android.core.domain.widgetconfig.WidgetPosition
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
 * `26-193` (`docs/design/tenant-widget-android.md` §3/§5.2): [WidgetConfigViewModel]'s own read and save
 * surface — the identical `StandardTestDispatcher`/`Dispatchers.setMain` shape every sibling view-model
 * test in this app already establishes ([ago.chat.android.bookings.CalendarSetupViewModelTest]).
 *
 * The load-bearing test here is `saving a committed-copy with one field changed leaves every other field
 * intact` — the view-model-level half of the absent-boolean-trap defence the design doc's §3 describes
 * (the wire-level half is `KtorWidgetConfigApiTest`'s own "sends all 16 fields" test): it proves that a
 * group editor's own `committed.copy(<its slice>)` save pattern, exercised here exactly as
 * [WidgetAppearanceEditor] exercises it, never drops or resets a field the editor never touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetConfigViewModelTest {
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
            val api = FakeWidgetConfigApi(hangFetch = true)
            val viewModel = viewModel(api)

            dispatcher.scheduler.runCurrent()

            assertEquals(WidgetConfigUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `the config arrives and is seeded as committed`() =
        runTest(dispatcher) {
            val api = FakeWidgetConfigApi(fetchResult = WidgetConfigResult.Loaded(fullConfig()))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(WidgetConfigUiState.Loaded(committed = fullConfig()), viewModel.state.value)
        }

    @Test
    fun `a failed read becomes a refusal carrying the adapter's own classification`() =
        runTest(dispatcher) {
            val api = FakeWidgetConfigApi(fetchResult = WidgetConfigResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(WidgetConfigUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `retry re-reads and can recover from a failure`() =
        runTest(dispatcher) {
            val api = FakeWidgetConfigApi(fetchResult = WidgetConfigResult.Failed(NetworkFailure.Unexpected))
            val viewModel = viewModel(api)
            advanceUntilIdle()
            assertTrue(viewModel.state.value is WidgetConfigUiState.Failed)

            api.fetchResult = WidgetConfigResult.Loaded(fullConfig())
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(WidgetConfigUiState.Loaded(committed = fullConfig()), viewModel.state.value)
        }

    @Test
    fun `a successful save re-seeds committed from the server's own echo and bumps savedTick`() =
        runTest(dispatcher) {
            val echoed = fullConfig().copy(panelTitle = "Нормализовано сервером")
            val api =
                FakeWidgetConfigApi(
                    fetchResult = WidgetConfigResult.Loaded(fullConfig()),
                    updateResult = WidgetConfigWriteResult.Saved(echoed),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.save(fullConfig().copy(primaryColorHex = "#123456"))
            advanceUntilIdle()

            val state = viewModel.state.value as WidgetConfigUiState.Loaded
            assertEquals(echoed, state.committed)
            assertEquals(1, state.savedTick)
            assertFalse(state.saving)
            assertNull(state.saveError)
        }

    @Test
    fun `saving a committed-copy with one field changed leaves every other field intact`() =
        runTest(dispatcher) {
            val committed = fullConfig()
            val api =
                FakeWidgetConfigApi(
                    fetchResult = WidgetConfigResult.Loaded(committed),
                    updateResult = WidgetConfigWriteResult.Saved(committed),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            // The exact pattern `WidgetAppearanceEditor` follows: `committed.copy(<its own slice>)`, never
            // a freshly-built `WidgetConfig`.
            val appearanceOnlySave = committed.copy(primaryColorHex = "#123456", position = WidgetPosition.BottomLeft)
            viewModel.save(appearanceOnlySave)
            advanceUntilIdle()

            val sent = api.updateCalls.single()
            // Every field this "appearance save" never touched must still equal the original committed
            // value, booleans included - the structural proof that this save pattern can never silently
            // reset `requireContactConsent` or any other flag to `false`.
            assertEquals(committed.copy(primaryColorHex = "#123456", position = WidgetPosition.BottomLeft), sent)
            assertEquals("requireContactConsent must survive an unrelated save untouched", true, sent.requireContactConsent)
            assertEquals("attractAttention must survive an unrelated save untouched", true, sent.attractAttention)
            assertEquals(
                "allowAttachmentUploadsByDefault must survive an unrelated save untouched",
                true,
                sent.allowAttachmentUploadsByDefault,
            )
        }

    @Test
    fun `a refused save keeps committed unchanged and shows the server's own words`() =
        runTest(dispatcher) {
            val committed = fullConfig()
            val api =
                FakeWidgetConfigApi(
                    fetchResult = WidgetConfigResult.Loaded(committed),
                    updateResult = WidgetConfigWriteResult.Refused("primaryColorHex must be a 6-digit hex colour."),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.save(committed.copy(primaryColorHex = "not-a-color"))
            advanceUntilIdle()

            val state = viewModel.state.value as WidgetConfigUiState.Loaded
            assertEquals("a refusal never changes what is committed", committed, state.committed)
            assertFalse(state.saving)
            assertEquals(
                WidgetConfigSaveError.ServerRefusal("primaryColorHex must be a 6-digit hex colour."),
                state.saveError,
            )
        }

    @Test
    fun `a transport failure on save is Unavailable, committed unchanged`() =
        runTest(dispatcher) {
            val committed = fullConfig()
            val api =
                FakeWidgetConfigApi(
                    fetchResult = WidgetConfigResult.Loaded(committed),
                    updateResult = WidgetConfigWriteResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.save(committed.copy(panelTitle = "Новый заголовок"))
            advanceUntilIdle()

            val state = viewModel.state.value as WidgetConfigUiState.Loaded
            assertEquals(committed, state.committed)
            assertEquals(WidgetConfigSaveError.Unavailable(NetworkFailure.NoConnection), state.saveError)
        }

    private fun viewModel(api: FakeWidgetConfigApi) = WidgetConfigViewModel(api = api, ioDispatcher = dispatcher)

    private fun fullConfig() =
        WidgetConfig(
            primaryColorHex = "#2F6FED",
            position = WidgetPosition.BottomRight,
            locale = WidgetLocale.Ru,
            panelTitle = "Есть вопрос?",
            attractAttention = true,
            autoOpenEnabled = true,
            autoOpenDelay = WidgetAutoOpenDelay.Seconds60,
            autoOpenGreetingText = "Здравствуйте!",
            channelSwitcherPlacement = ChannelSwitcherPlacement.BelowLauncher,
            channelSwitcherIconSize = ChannelSwitcherIconSize.Large,
            noticeText = "Мы обрабатываем ваши данные.",
            noticeUrl = "https://example.com/privacy",
            requireContactConsent = true,
            contactCaptureConfirmationText = "Спасибо!",
            acceptUnverifiedPhone = true,
            allowAttachmentUploadsByDefault = true,
        )

    private class FakeWidgetConfigApi(
        var fetchResult: WidgetConfigResult = WidgetConfigResult.Failed(NetworkFailure.Unexpected),
        private val hangFetch: Boolean = false,
        var updateResult: WidgetConfigWriteResult = WidgetConfigWriteResult.Failed(NetworkFailure.Unexpected),
    ) : WidgetConfigApi {
        val updateCalls: MutableList<WidgetConfig> = mutableListOf()

        override suspend fun fetch(): WidgetConfigResult {
            if (hangFetch) awaitCancellation()
            return fetchResult
        }

        override suspend fun update(config: WidgetConfig): WidgetConfigWriteResult {
            updateCalls.add(config)
            return updateResult
        }
    }
}
