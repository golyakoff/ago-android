package ago.chat.android.faq

import ago.chat.android.core.domain.modules.EnabledModule
import ago.chat.android.core.domain.modules.ModulesApi
import ago.chat.android.core.domain.modules.ModulesResult
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
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * `26-199`/`M1`: the Модули panel's own state machine — the load/empty/failed/retry states this
 * ticket's own Done-when asks for, the identical `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * [ago.chat.android.analytics.SiteAnalyticsViewModelTest] already establishes for its own single-read
 * sibling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModulesFaqViewModelTest {
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
            val api = FakeModulesApi(hangFetch = true)
            val viewModel = ModulesFaqViewModel(modulesApi = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(ModulesFaqUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a loaded answer carries the enabled modules through unedited`() =
        runTest(dispatcher) {
            val modules =
                listOf(
                    EnabledModule(
                        moduleKey = "faq",
                        triggerWords = listOf("помощь"),
                        entryPoint = "https://faq.example/entry",
                        grantedByOwner = true,
                        expiresAt = Instant.parse("2027-01-01T00:00:00Z"),
                    ),
                )
            val api = FakeModulesApi(result = ModulesResult.Loaded(modules))
            val viewModel = ModulesFaqViewModel(modulesApi = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ModulesFaqUiState.Loaded(modules), viewModel.state.value)
            assertEquals(1, api.calls)
        }

    @Test
    fun `an empty module list is Loaded, not Failed`() =
        runTest(dispatcher) {
            val api = FakeModulesApi(result = ModulesResult.Loaded(emptyList()))
            val viewModel = ModulesFaqViewModel(modulesApi = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ModulesFaqUiState.Loaded(emptyList()), viewModel.state.value)
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeModulesApi(result = ModulesResult.Failed(NetworkFailure.NoConnection))
            val viewModel = ModulesFaqViewModel(modulesApi = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ModulesFaqUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `refresh repeats the read, the whole of retry`() =
        runTest(dispatcher) {
            val api = FakeModulesApi(result = ModulesResult.Failed(NetworkFailure.Unexpected))
            val viewModel = ModulesFaqViewModel(modulesApi = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.calls)

            api.result = ModulesResult.Loaded(emptyList())
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(2, api.calls)
            assertEquals(ModulesFaqUiState.Loaded(emptyList()), viewModel.state.value)
        }

    private class FakeModulesApi(
        var result: ModulesResult = ModulesResult.Failed(NetworkFailure.Unexpected),
        private val hangFetch: Boolean = false,
    ) : ModulesApi {
        var calls: Int = 0

        override suspend fun fetch(): ModulesResult {
            calls++
            if (hangFetch) awaitCancellation()
            return result
        }
    }
}
