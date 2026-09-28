package ago.chat.android.products

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `26-249`: the «Продукты» screen's own state machine — the load/held/not-held/failed/retry states this
 * ticket's own Done-when asks for, the identical `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * [ago.chat.android.faq.ModulesFaqViewModelTest] establishes for its own single-read sibling. Asserts the
 * `ago-console` `buildRows` derivation key for key: chat is always held; calendar/faq follow the wire's
 * own `"calendar"`/`"faq"` keys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductsViewModelTest {
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
            val viewModel = ProductsViewModel(modulesApi = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(ProductsUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `every held module marks its own product held, and chat is always held`() =
        runTest(dispatcher) {
            val api = FakeModulesApi(result = ModulesResult.Loaded(listOf(module("calendar"), module("faq"))))
            val viewModel = ProductsViewModel(modulesApi = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val loaded = viewModel.state.value as ProductsUiState.Loaded
            assertEquals(
                listOf(
                    ProductHolding(Product.Chat, held = true),
                    ProductHolding(Product.Calendar, held = true),
                    ProductHolding(Product.Faq, held = true),
                ),
                loaded.products,
            )
            assertEquals(1, api.calls)
        }

    @Test
    fun `a module the workspace lacks is not held, but chat still is`() =
        runTest(dispatcher) {
            // Only calendar enabled: faq must read not-held (the console's own contact-note branch), while
            // chat stays held with no key of its own on the wire at all.
            val api = FakeModulesApi(result = ModulesResult.Loaded(listOf(module("calendar"))))
            val viewModel = ProductsViewModel(modulesApi = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val loaded = viewModel.state.value as ProductsUiState.Loaded
            assertTrue(loaded.products.single { it.product == Product.Chat }.held)
            assertTrue(loaded.products.single { it.product == Product.Calendar }.held)
            assertFalse(loaded.products.single { it.product == Product.Faq }.held)
        }

    @Test
    fun `an empty module list holds only chat`() =
        runTest(dispatcher) {
            val api = FakeModulesApi(result = ModulesResult.Loaded(emptyList()))
            val viewModel = ProductsViewModel(modulesApi = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val loaded = viewModel.state.value as ProductsUiState.Loaded
            assertEquals(
                setOf(Product.Chat),
                loaded.products
                    .filter { it.held }
                    .map { it.product }
                    .toSet(),
            )
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeModulesApi(result = ModulesResult.Failed(NetworkFailure.NoConnection))
            val viewModel = ProductsViewModel(modulesApi = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ProductsUiState.Failed(NetworkFailure.NoConnection), viewModel.state.value)
        }

    @Test
    fun `refresh repeats the read, the whole of retry`() =
        runTest(dispatcher) {
            val api = FakeModulesApi(result = ModulesResult.Failed(NetworkFailure.Unexpected))
            val viewModel = ProductsViewModel(modulesApi = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.calls)

            api.result = ModulesResult.Loaded(emptyList())
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(2, api.calls)
            assertTrue(viewModel.state.value is ProductsUiState.Loaded)
        }

    private fun module(key: String): EnabledModule =
        EnabledModule(
            moduleKey = key,
            triggerWords = emptyList(),
            entryPoint = "https://$key.example/entry",
            grantedByOwner = false,
            expiresAt = null,
        )

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
