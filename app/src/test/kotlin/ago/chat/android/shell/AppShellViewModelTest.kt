package ago.chat.android.shell

import ago.chat.android.core.domain.identity.ProbeFailure
import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.OperatorPermissionsApi
import ago.chat.android.core.domain.permissions.PermissionsFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * `26-16`: the "not loaded yet" vs "loaded and holds nothing" distinction this item's own Done-when
 * names, proven at the one class that actually reads the session's permission set. A plain JVM test,
 * the same `StandardTestDispatcher` + `Dispatchers.setMain` shape `ConversationListViewModelTest`/
 * `SignInViewModelTest` already establish.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppShellViewModelTest {
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
    fun `before the fetch answers, permissions is Unknown, never an invented empty grant set`() =
        runTest(dispatcher) {
            val viewModel = viewModelWith(FakeOperatorPermissionsApi(hang = true))

            dispatcher.scheduler.runCurrent()

            assertEquals(OperatorPermissions.Unknown, viewModel.permissions.value)
            assertNull(viewModel.loadError.value)
        }

    @Test
    fun `a successful fetch with an empty grant set is Known, not Unknown - the whole point of the distinction`() =
        runTest(dispatcher) {
            val viewModel = viewModelWith(FakeOperatorPermissionsApi(result = PermissionsFetch.Loaded(emptySet())))

            advanceUntilIdle()

            assertEquals(OperatorPermissions.Known(emptySet()), viewModel.permissions.value)
        }

    @Test
    fun `a successful fetch carries exactly the granted permissions, verbatim`() =
        runTest(dispatcher) {
            val granted = setOf("calendar:configure", "customer:read")
            val viewModel = viewModelWith(FakeOperatorPermissionsApi(result = PermissionsFetch.Loaded(granted)))

            advanceUntilIdle()

            assertEquals(OperatorPermissions.Known(granted), viewModel.permissions.value)
        }

    @Test
    fun `a failed fetch stays Unknown and surfaces a retry, never a guessed grant set`() =
        runTest(dispatcher) {
            val viewModel =
                viewModelWith(FakeOperatorPermissionsApi(result = PermissionsFetch.Failed(ProbeFailure.UnexpectedStatus(503))))

            advanceUntilIdle()

            assertEquals(OperatorPermissions.Unknown, viewModel.permissions.value)
            assertEquals("HTTP 503", viewModel.loadError.value)
        }

    @Test
    fun `retry re-asks and can recover from a failure into a real answer`() =
        runTest(dispatcher) {
            val api = FakeOperatorPermissionsApi(result = PermissionsFetch.Failed(ProbeFailure.Transport("timeout")))
            val viewModel = viewModelWith(api)
            advanceUntilIdle()
            assertEquals(OperatorPermissions.Unknown, viewModel.permissions.value)

            api.result = PermissionsFetch.Loaded(setOf("site:configure"))
            viewModel.retry()
            advanceUntilIdle()

            assertEquals(OperatorPermissions.Known(setOf("site:configure")), viewModel.permissions.value)
            assertNull(viewModel.loadError.value)
        }

    private fun viewModelWith(api: OperatorPermissionsApi): AppShellViewModel = AppShellViewModel(api = api, ioDispatcher = dispatcher)

    private class FakeOperatorPermissionsApi(
        var result: PermissionsFetch = PermissionsFetch.Loaded(emptySet()),
        private val hang: Boolean = false,
    ) : OperatorPermissionsApi {
        override suspend fun fetchMyPermissions(): PermissionsFetch {
            if (hang) kotlinx.coroutines.awaitCancellation()
            return result
        }
    }
}
