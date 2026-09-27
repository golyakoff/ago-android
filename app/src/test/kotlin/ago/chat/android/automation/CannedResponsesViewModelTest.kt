package ago.chat.android.automation

import ago.chat.android.core.domain.cannedresponses.CannedResponse
import ago.chat.android.core.domain.cannedresponses.CannedResponseBounds
import ago.chat.android.core.domain.cannedresponses.CannedResponsesApi
import ago.chat.android.core.domain.cannedresponses.CannedResponsesResult
import ago.chat.android.core.domain.cannedresponses.CannedResponsesWriteResult
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
 * `26-220` (`docs/design/tenant-canned-tags-android.md` §1.6): [CannedResponsesViewModel]'s own
 * read-then-edit-then-save surface — the identical `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * every sibling view-model test in this app already establishes
 * ([ago.chat.android.automation.OfflineAutoReplyViewModelTest]).
 *
 * The load-bearing tests here are: [CannedResponsesViewModel.addOrReplace]/[CannedResponsesViewModel.delete]
 * each send the *whole* list, never a per-item request (there is no per-item endpoint); [save] runs the
 * courtesy check before ever touching the network; and a successful save re-seeds from the server's own
 * echo and bumps `savedTick`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CannedResponsesViewModelTest {
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
            val api = FakeCannedResponsesApi(hangFetch = true)
            val viewModel = viewModel(api)

            dispatcher.scheduler.runCurrent()

            assertEquals(CannedResponsesUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `the library arrives and is seeded as loaded`() =
        runTest(dispatcher) {
            val api =
                FakeCannedResponsesApi(
                    fetchResult = CannedResponsesResult.Loaded(listOf(CannedResponse("Оплата", "Принимаем карты и наличные."))),
                )
            val viewModel = viewModel(api)

            advanceUntilIdle()

            val state = viewModel.state.value as CannedResponsesUiState.Loaded
            assertEquals(listOf(CannedResponse("Оплата", "Принимаем карты и наличные.")), state.responses)
            assertFalse(state.saving)
            assertNull(state.error)
        }

    @Test
    fun `a failed read becomes a refusal carrying the adapter's own classification`() =
        runTest(dispatcher) {
            val api = FakeCannedResponsesApi(fetchResult = CannedResponsesResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(CannedResponsesUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `refresh re-reads and can recover from a failure`() =
        runTest(dispatcher) {
            val api = FakeCannedResponsesApi(fetchResult = CannedResponsesResult.Failed(NetworkFailure.Unexpected))
            val viewModel = viewModel(api)
            advanceUntilIdle()
            assertTrue(viewModel.state.value is CannedResponsesUiState.Failed)

            api.fetchResult = CannedResponsesResult.Loaded(emptyList())
            viewModel.refresh()
            advanceUntilIdle()

            assertTrue(viewModel.state.value is CannedResponsesUiState.Loaded)
        }

    // ---------------------------------------------------------------------------------------- addOrReplace

    @Test
    fun `addOrReplace with a null index appends and saves the whole list`() =
        runTest(dispatcher) {
            val api =
                FakeCannedResponsesApi(
                    fetchResult = CannedResponsesResult.Loaded(listOf(CannedResponse("Оплата", "Картой и наличными."))),
                    saveResult = CannedResponsesWriteResult.Saved(emptyList()),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.addOrReplace(null, "Доставка", "Доставляем за три дня.")
            advanceUntilIdle()

            assertEquals(1, api.saveCalls)
            assertEquals(
                listOf(CannedResponse("Оплата", "Картой и наличными."), CannedResponse("Доставка", "Доставляем за три дня.")),
                api.lastSaveRequest,
            )
        }

    @Test
    fun `addOrReplace with an index replaces only that entry`() =
        runTest(dispatcher) {
            val api =
                FakeCannedResponsesApi(
                    fetchResult =
                        CannedResponsesResult.Loaded(
                            listOf(CannedResponse("Оплата", "Картой."), CannedResponse("Доставка", "Три дня.")),
                        ),
                    saveResult = CannedResponsesWriteResult.Saved(emptyList()),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.addOrReplace(0, "Оплата", "Картой, наличными и переводом.")
            advanceUntilIdle()

            assertEquals(
                listOf(CannedResponse("Оплата", "Картой, наличными и переводом."), CannedResponse("Доставка", "Три дня.")),
                api.lastSaveRequest,
            )
        }

    @Test
    fun `addOrReplace with an out-of-range index is ignored rather than crashing`() =
        runTest(dispatcher) {
            val api = FakeCannedResponsesApi(fetchResult = CannedResponsesResult.Loaded(emptyList()))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.addOrReplace(5, "x", "y")
            advanceUntilIdle()

            assertEquals("an out-of-range index must never reach the network", 0, api.saveCalls)
        }

    // ----------------------------------------------------------------------------------------------- delete

    @Test
    fun `delete removes only the addressed entry and saves the rest`() =
        runTest(dispatcher) {
            val api =
                FakeCannedResponsesApi(
                    fetchResult =
                        CannedResponsesResult.Loaded(
                            listOf(CannedResponse("Оплата", "Картой."), CannedResponse("Доставка", "Три дня.")),
                        ),
                    saveResult = CannedResponsesWriteResult.Saved(emptyList()),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.delete(0)
            advanceUntilIdle()

            assertEquals(1, api.saveCalls)
            assertEquals(listOf(CannedResponse("Доставка", "Три дня.")), api.lastSaveRequest)
        }

    @Test
    fun `delete with an out-of-range index is ignored rather than crashing`() =
        runTest(dispatcher) {
            val api = FakeCannedResponsesApi(fetchResult = CannedResponsesResult.Loaded(emptyList()))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.delete(0)
            advanceUntilIdle()

            assertEquals("an out-of-range index must never reach the network", 0, api.saveCalls)
        }

    // ------------------------------------------------------------------------------------------------ save

    @Test
    fun `save runs the courtesy check first - a too-large list never calls the network`() =
        runTest(dispatcher) {
            val fullLibrary = (1..CannedResponseBounds.MAX_COUNT).map { CannedResponse("Заголовок $it", "Текст $it") }
            val api = FakeCannedResponsesApi(fetchResult = CannedResponsesResult.Loaded(fullLibrary))
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.addOrReplace(null, "Ещё один", "Текст")
            advanceUntilIdle()

            assertEquals("an invalid list must never reach the network", 0, api.saveCalls)
            val state = viewModel.state.value as CannedResponsesUiState.Loaded
            assertEquals(CannedResponsesActionError.Invalid(CannedResponseValidationProblem.TooMany), state.error)
            assertFalse(state.saving)
            assertEquals("the in-memory list is left unchanged on a courtesy-check failure", fullLibrary, state.responses)
        }

    @Test
    fun `save trims the title but sends the body as typed`() =
        runTest(dispatcher) {
            val api =
                FakeCannedResponsesApi(
                    fetchResult = CannedResponsesResult.Loaded(emptyList()),
                    saveResult = CannedResponsesWriteResult.Saved(emptyList()),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.addOrReplace(null, "  Заголовок  ", "  Текст с пробелами  ")
            advanceUntilIdle()

            assertEquals(listOf(CannedResponse("Заголовок", "  Текст с пробелами  ")), api.lastSaveRequest)
        }

    @Test
    fun `a successful save re-seeds from the server's own echo and bumps savedTick`() =
        runTest(dispatcher) {
            val echoed = listOf(CannedResponse("Нормализовано", "сервером"))
            val api =
                FakeCannedResponsesApi(
                    fetchResult = CannedResponsesResult.Loaded(emptyList()),
                    saveResult = CannedResponsesWriteResult.Saved(echoed),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.addOrReplace(null, "исходное", "значение")
            advanceUntilIdle()

            val state = viewModel.state.value as CannedResponsesUiState.Loaded
            assertEquals(echoed, state.responses)
            assertEquals(1, state.savedTick)
            assertFalse(state.saving)
            assertNull(state.error)
        }

    @Test
    fun `a second successful save bumps savedTick again`() =
        runTest(dispatcher) {
            val api =
                FakeCannedResponsesApi(
                    fetchResult = CannedResponsesResult.Loaded(emptyList()),
                    saveResult = CannedResponsesWriteResult.Saved(emptyList()),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.addOrReplace(null, "a", "b")
            advanceUntilIdle()
            viewModel.addOrReplace(null, "c", "d")
            advanceUntilIdle()

            assertEquals(2, (viewModel.state.value as CannedResponsesUiState.Loaded).savedTick)
        }

    @Test
    fun `a refused save keeps the list and shows the server's own words`() =
        runTest(dispatcher) {
            val original = listOf(CannedResponse("Оплата", "Картой."))
            val api =
                FakeCannedResponsesApi(
                    fetchResult = CannedResponsesResult.Loaded(original),
                    saveResult = CannedResponsesWriteResult.Refused("Заголовок не может быть пустым."),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.addOrReplace(null, "Новый", "Ответ")
            advanceUntilIdle()

            val state = viewModel.state.value as CannedResponsesUiState.Loaded
            assertEquals(original, state.responses)
            assertFalse(state.saving)
            assertEquals(CannedResponsesActionError.ServerRefusal("Заголовок не может быть пустым."), state.error)
        }

    @Test
    fun `a transport failure on save is Unavailable, list unchanged`() =
        runTest(dispatcher) {
            val api =
                FakeCannedResponsesApi(
                    fetchResult = CannedResponsesResult.Loaded(emptyList()),
                    saveResult = CannedResponsesWriteResult.Failed(NetworkFailure.NoConnection),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.addOrReplace(null, "a", "b")
            advanceUntilIdle()

            val state = viewModel.state.value as CannedResponsesUiState.Loaded
            assertEquals(CannedResponsesActionError.Unavailable(NetworkFailure.NoConnection), state.error)
            assertFalse(state.saving)
        }

    @Test
    fun `a second write while one is already in flight is a no-op`() =
        runTest(dispatcher) {
            val api = FakeCannedResponsesApi(fetchResult = CannedResponsesResult.Loaded(emptyList()), hangSave = true)
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.addOrReplace(null, "a", "b")
            dispatcher.scheduler.runCurrent()
            assertTrue((viewModel.state.value as CannedResponsesUiState.Loaded).saving)

            viewModel.addOrReplace(null, "c", "d")
            viewModel.delete(0)
            dispatcher.scheduler.runCurrent()

            assertEquals("a write already in flight must never let a second one reach the network", 1, api.saveCalls)
        }

    private fun viewModel(api: FakeCannedResponsesApi) = CannedResponsesViewModel(api = api, ioDispatcher = dispatcher)

    private class FakeCannedResponsesApi(
        var fetchResult: CannedResponsesResult = CannedResponsesResult.Failed(NetworkFailure.Unexpected),
        private val hangFetch: Boolean = false,
        var saveResult: CannedResponsesWriteResult = CannedResponsesWriteResult.Failed(NetworkFailure.Unexpected),
        private val hangSave: Boolean = false,
    ) : CannedResponsesApi {
        var saveCalls: Int = 0
            private set
        var lastSaveRequest: List<CannedResponse>? = null
            private set

        override suspend fun fetch(): CannedResponsesResult {
            if (hangFetch) awaitCancellation()
            return fetchResult
        }

        override suspend fun save(responses: List<CannedResponse>): CannedResponsesWriteResult {
            saveCalls++
            lastSaveRequest = responses
            if (hangSave) awaitCancellation()
            return saveResult
        }
    }
}
