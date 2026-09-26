package ago.chat.android.automation

import ago.chat.android.core.domain.autoreply.AutoReplyRule
import ago.chat.android.core.domain.autoreply.OfflineAutoReply
import ago.chat.android.core.domain.autoreply.OfflineAutoReplyApi
import ago.chat.android.core.domain.autoreply.OfflineAutoReplyResult
import ago.chat.android.core.domain.autoreply.OfflineAutoReplyWriteResult
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
 * `26-192`/`C5` (`docs/design/tenant-channels-android.md` §4.3): [OfflineAutoReplyViewModel]'s own
 * read-then-edit-then-save surface — the identical `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * every sibling view-model test in this app already establishes
 * ([ago.chat.android.channels.BrandingViewModelTest]).
 *
 * The load-bearing tests here are: [save] runs the courtesy check before ever touching the network (a
 * failing draft makes zero `update` calls), a successful save re-seeds from the server's own echo and
 * bumps `savedTick`, and [OfflineAutoReplyViewModel.moveRule] leaves the intended order behind for the
 * next save to send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineAutoReplyViewModelTest {
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
            val api = FakeOfflineAutoReplyApi(hangFetch = true)
            val viewModel = viewModel(api)

            dispatcher.scheduler.runCurrent()

            assertEquals(OfflineAutoReplyUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `the settings arrive and are seeded as loaded, one draft row per server rule`() =
        runTest(dispatcher) {
            val api =
                FakeOfflineAutoReplyApi(
                    fetchResult =
                        OfflineAutoReplyResult.Loaded(
                            OfflineAutoReply(
                                enabled = true,
                                fallbackReply = "Мы вернёмся утром.",
                                rules = listOf(AutoReplyRule("возврат", "Возвраты занимают три рабочих дня.")),
                            ),
                        ),
                )
            val viewModel = viewModel(api)

            advanceUntilIdle()

            val state = viewModel.state.value as OfflineAutoReplyUiState.Loaded
            assertTrue(state.enabled)
            assertEquals("Мы вернёмся утром.", state.fallbackReply)
            assertEquals(listOf("возврат" to "Возвраты занимают три рабочих дня."), state.rules.map { it.keyword to it.reply })
            assertFalse(state.saving)
            assertNull(state.saveError)
        }

    @Test
    fun `a failed read becomes a refusal carrying the adapter's own classification`() =
        runTest(dispatcher) {
            val api = FakeOfflineAutoReplyApi(fetchResult = OfflineAutoReplyResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(OfflineAutoReplyUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `refresh re-reads and can recover from a failure`() =
        runTest(dispatcher) {
            val api = FakeOfflineAutoReplyApi(fetchResult = OfflineAutoReplyResult.Failed(NetworkFailure.Unexpected))
            val viewModel = viewModel(api)
            advanceUntilIdle()
            assertTrue(viewModel.state.value is OfflineAutoReplyUiState.Failed)

            api.fetchResult = OfflineAutoReplyResult.Loaded(emptySettings())
            viewModel.refresh()
            advanceUntilIdle()

            assertTrue(viewModel.state.value is OfflineAutoReplyUiState.Loaded)
        }

    // --------------------------------------------------------------------------------------- draft edits

    @Test
    fun `addRule appends one blank rule at the end`() =
        runTest(dispatcher) {
            val viewModel = loadedViewModel(FakeOfflineAutoReplyApi(fetchResult = OfflineAutoReplyResult.Loaded(emptySettings())))

            viewModel.addRule()
            viewModel.addRule()

            val rules = (viewModel.state.value as OfflineAutoReplyUiState.Loaded).rules
            assertEquals(2, rules.size)
            assertTrue(rules.all { it.keyword.isEmpty() && it.reply.isEmpty() })
            assertEquals("each added row gets its own id", 2, rules.map { it.id }.toSet().size)
        }

    @Test
    fun `updateRuleKeyword and updateRuleReply edit only the addressed row`() =
        runTest(dispatcher) {
            val viewModel = loadedViewModel(FakeOfflineAutoReplyApi(fetchResult = OfflineAutoReplyResult.Loaded(emptySettings())))
            viewModel.addRule()
            viewModel.addRule()
            val ids = (viewModel.state.value as OfflineAutoReplyUiState.Loaded).rules.map { it.id }

            viewModel.updateRuleKeyword(ids[0], "возврат")
            viewModel.updateRuleReply(ids[0], "Три дня.")
            viewModel.updateRuleKeyword(ids[1], "доставка")

            val rules = (viewModel.state.value as OfflineAutoReplyUiState.Loaded).rules
            assertEquals("возврат" to "Три дня.", rules[0].keyword to rules[0].reply)
            assertEquals("доставка" to "", rules[1].keyword to rules[1].reply)
        }

    @Test
    fun `removeRule drops only the addressed row`() =
        runTest(dispatcher) {
            val viewModel = loadedViewModel(FakeOfflineAutoReplyApi(fetchResult = OfflineAutoReplyResult.Loaded(emptySettings())))
            viewModel.addRule()
            viewModel.addRule()
            val ids = (viewModel.state.value as OfflineAutoReplyUiState.Loaded).rules.map { it.id }

            viewModel.removeRule(ids[0])

            val rules = (viewModel.state.value as OfflineAutoReplyUiState.Loaded).rules
            assertEquals(listOf(ids[1]), rules.map { it.id })
        }

    @Test
    fun `moveRule reorders the draft, the reorderable library's own settle shape`() =
        runTest(dispatcher) {
            val viewModel = loadedViewModel(FakeOfflineAutoReplyApi(fetchResult = OfflineAutoReplyResult.Loaded(emptySettings())))
            viewModel.addRule()
            viewModel.addRule()
            viewModel.addRule()
            val ids = (viewModel.state.value as OfflineAutoReplyUiState.Loaded).rules.map { it.id }
            viewModel.updateRuleKeyword(ids[0], "a")
            viewModel.updateRuleKeyword(ids[1], "b")
            viewModel.updateRuleKeyword(ids[2], "c")

            // Move the first rule ("a") to the end.
            viewModel.moveRule(0, 2)

            val keywords = (viewModel.state.value as OfflineAutoReplyUiState.Loaded).rules.map { it.keyword }
            assertEquals(listOf("b", "c", "a"), keywords)
        }

    @Test
    fun `moveRule with an out-of-range index is ignored rather than crashing`() =
        runTest(dispatcher) {
            val viewModel = loadedViewModel(FakeOfflineAutoReplyApi(fetchResult = OfflineAutoReplyResult.Loaded(emptySettings())))
            viewModel.addRule()
            val before = (viewModel.state.value as OfflineAutoReplyUiState.Loaded).rules

            viewModel.moveRule(0, 5)

            assertEquals(before, (viewModel.state.value as OfflineAutoReplyUiState.Loaded).rules)
        }

    // ---------------------------------------------------------------------------------------------- save

    @Test
    fun `save runs the courtesy check first - an invalid draft never calls update`() =
        runTest(dispatcher) {
            val api = FakeOfflineAutoReplyApi(fetchResult = OfflineAutoReplyResult.Loaded(emptySettings()))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.setEnabled(true)
            // Fallback left blank while enabled - `FallbackRequired`.
            viewModel.save()
            advanceUntilIdle()

            assertEquals("an invalid draft must never reach the network", 0, api.updateCalls)
            val state = viewModel.state.value as OfflineAutoReplyUiState.Loaded
            assertEquals(OfflineAutoReplyActionError.Invalid(OfflineAutoReplyValidationProblem.FallbackRequired), state.saveError)
            assertFalse(state.saving)
        }

    @Test
    fun `save sends the meaningful rules only, keyword trimmed, order preserved`() =
        runTest(dispatcher) {
            val api =
                FakeOfflineAutoReplyApi(
                    fetchResult = OfflineAutoReplyResult.Loaded(emptySettings()),
                    updateResult = OfflineAutoReplyWriteResult.Saved(emptySettings()),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()
            viewModel.addRule()
            viewModel.addRule()
            viewModel.addRule()
            val ids = (viewModel.state.value as OfflineAutoReplyUiState.Loaded).rules.map { it.id }
            viewModel.updateRuleKeyword(ids[0], "  зет  ")
            viewModel.updateRuleReply(ids[0], "z")
            // ids[1] left wholly blank - dropped, not sent.
            viewModel.updateRuleKeyword(ids[2], "альфа")
            viewModel.updateRuleReply(ids[2], "a")
            viewModel.setFallbackReply("  По умолчанию  ")

            viewModel.save()
            advanceUntilIdle()

            assertEquals(1, api.updateCalls)
            val sent = api.lastUpdateRequest!!
            assertEquals("По умолчанию", sent.fallbackReply)
            assertEquals(listOf(AutoReplyRule("зет", "z"), AutoReplyRule("альфа", "a")), sent.rules)
        }

    @Test
    fun `a successful save re-seeds from the server's own echo and bumps savedTick`() =
        runTest(dispatcher) {
            val echoed = OfflineAutoReply(enabled = true, fallbackReply = "Нормализовано сервером", rules = emptyList())
            val api =
                FakeOfflineAutoReplyApi(
                    fetchResult = OfflineAutoReplyResult.Loaded(emptySettings()),
                    updateResult = OfflineAutoReplyWriteResult.Saved(echoed),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.save()
            advanceUntilIdle()

            val state = viewModel.state.value as OfflineAutoReplyUiState.Loaded
            assertEquals("Нормализовано сервером", state.fallbackReply)
            assertTrue(state.enabled)
            assertEquals(1, state.savedTick)
            assertFalse(state.saving)
            assertNull(state.saveError)
        }

    @Test
    fun `a second successful save bumps savedTick again`() =
        runTest(dispatcher) {
            val api =
                FakeOfflineAutoReplyApi(
                    fetchResult = OfflineAutoReplyResult.Loaded(emptySettings()),
                    updateResult = OfflineAutoReplyWriteResult.Saved(emptySettings()),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.save()
            advanceUntilIdle()
            viewModel.save()
            advanceUntilIdle()

            assertEquals(2, (viewModel.state.value as OfflineAutoReplyUiState.Loaded).savedTick)
        }

    @Test
    fun `a refused save keeps the draft and shows the server's own words`() =
        runTest(dispatcher) {
            val api =
                FakeOfflineAutoReplyApi(
                    fetchResult = OfflineAutoReplyResult.Loaded(emptySettings()),
                    updateResult = OfflineAutoReplyWriteResult.Refused("Правило нуждается в ключевом слове и ответе."),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()
            viewModel.setFallbackReply("что-то")

            viewModel.save()
            advanceUntilIdle()

            val state = viewModel.state.value as OfflineAutoReplyUiState.Loaded
            assertEquals("что-то", state.fallbackReply)
            assertFalse(state.saving)
            assertEquals(OfflineAutoReplyActionError.ServerRefusal("Правило нуждается в ключевом слове и ответе."), state.saveError)
        }

    @Test
    fun `a transport failure on save is Unavailable, draft unchanged`() =
        runTest(dispatcher) {
            val api =
                FakeOfflineAutoReplyApi(
                    fetchResult = OfflineAutoReplyResult.Loaded(emptySettings()),
                    updateResult = OfflineAutoReplyWriteResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.save()
            advanceUntilIdle()

            val state = viewModel.state.value as OfflineAutoReplyUiState.Loaded
            assertEquals(OfflineAutoReplyActionError.Unavailable(NetworkFailure.NoConnection), state.saveError)
            assertFalse(state.saving)
        }

    @Test
    fun `a second save while one is already in flight is a no-op`() =
        runTest(dispatcher) {
            val api = FakeOfflineAutoReplyApi(fetchResult = OfflineAutoReplyResult.Loaded(emptySettings()), hangUpdate = true)
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.save()
            dispatcher.scheduler.runCurrent()
            assertTrue((viewModel.state.value as OfflineAutoReplyUiState.Loaded).saving)

            viewModel.save()
            dispatcher.scheduler.runCurrent()

            assertEquals("the second save call must never reach the network while the first is in flight", 1, api.updateCalls)
        }

    private fun viewModel(api: FakeOfflineAutoReplyApi) = OfflineAutoReplyViewModel(api = api, ioDispatcher = dispatcher)

    private fun loadedViewModel(api: FakeOfflineAutoReplyApi): OfflineAutoReplyViewModel {
        val viewModel = viewModel(api)
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    private fun emptySettings() = OfflineAutoReply(enabled = false, fallbackReply = "", rules = emptyList())

    private class FakeOfflineAutoReplyApi(
        var fetchResult: OfflineAutoReplyResult = OfflineAutoReplyResult.Failed(NetworkFailure.Unexpected),
        private val hangFetch: Boolean = false,
        var updateResult: OfflineAutoReplyWriteResult = OfflineAutoReplyWriteResult.Failed(NetworkFailure.Unexpected),
        private val hangUpdate: Boolean = false,
    ) : OfflineAutoReplyApi {
        var updateCalls: Int = 0
            private set
        var lastUpdateRequest: OfflineAutoReply? = null
            private set

        override suspend fun fetch(): OfflineAutoReplyResult {
            if (hangFetch) awaitCancellation()
            return fetchResult
        }

        override suspend fun update(settings: OfflineAutoReply): OfflineAutoReplyWriteResult {
            updateCalls++
            lastUpdateRequest = settings
            if (hangUpdate) awaitCancellation()
            return updateResult
        }
    }
}
