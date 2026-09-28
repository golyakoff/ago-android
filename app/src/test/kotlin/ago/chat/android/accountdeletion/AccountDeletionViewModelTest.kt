package ago.chat.android.accountdeletion

import ago.chat.android.core.domain.accountdeletion.AccountDeletionApi
import ago.chat.android.core.domain.accountdeletion.EraseAccountResult
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
 * `26-252`: the «Удалить аккаунт» screen's own state machine — and above all the confirmation gate: the erase
 * call is reachable **only** through a deliberately opened confirmation, never from a fresh screen or a
 * dismissed dialog. The identical `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * [ago.chat.android.siteexport.SiteExportViewModelTest] establishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionViewModelTest {
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
    fun `starts on the warning panel, dialog closed, nothing deleted`() =
        runTest(dispatcher) {
            val api = FakeAccountDeletionApi()
            val viewModel = AccountDeletionViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val ready = viewModel.state.value as AccountDeletionUiState.Ready
            assertFalse(ready.confirming)
            assertEquals("opening the screen must not touch the erase endpoint", 0, api.eraseCalls)
        }

    @Test
    fun `asking for confirmation opens the dialog but deletes nothing`() =
        runTest(dispatcher) {
            val api = FakeAccountDeletionApi()
            val viewModel = AccountDeletionViewModel(api = api, ioDispatcher = dispatcher)

            viewModel.askConfirmation()
            advanceUntilIdle()

            val ready = viewModel.state.value as AccountDeletionUiState.Ready
            assertTrue("the dialog is now open", ready.confirming)
            assertEquals("merely asking to confirm must delete nothing", 0, api.eraseCalls)
        }

    @Test
    fun `the gate - confirmDeletion is a no-op without an open confirmation`() =
        runTest(dispatcher) {
            val api = FakeAccountDeletionApi()
            val viewModel = AccountDeletionViewModel(api = api, ioDispatcher = dispatcher)

            // Never opened the dialog: the irreversible call must not fire.
            viewModel.confirmDeletion()
            advanceUntilIdle()

            assertEquals("deletion without an explicit confirm must be a no-op", 0, api.eraseCalls)
            assertTrue(viewModel.state.value is AccountDeletionUiState.Ready)

            // Opened then dismissed: still no confirmation standing, so still no erase.
            viewModel.askConfirmation()
            viewModel.dismissConfirmation()
            viewModel.confirmDeletion()
            advanceUntilIdle()

            assertEquals("deletion after a dismissed dialog must be a no-op too", 0, api.eraseCalls)
        }

    @Test
    fun `a confirmed deletion fires the erase exactly once and enters the erasing state`() =
        runTest(dispatcher) {
            val api = FakeAccountDeletionApi(result = EraseAccountResult.Requested)
            val viewModel = AccountDeletionViewModel(api = api, ioDispatcher = dispatcher)

            viewModel.askConfirmation()
            viewModel.confirmDeletion()
            advanceUntilIdle()

            assertEquals(1, api.eraseCalls)
            assertEquals(AccountDeletionUiState.Erasing, viewModel.state.value)
        }

    @Test
    fun `a second confirm while one is in flight does not fire a second erase`() =
        runTest(dispatcher) {
            val api = FakeAccountDeletionApi(hang = true)
            val viewModel = AccountDeletionViewModel(api = api, ioDispatcher = dispatcher)

            viewModel.askConfirmation()
            viewModel.confirmDeletion()
            viewModel.confirmDeletion()
            advanceUntilIdle()

            assertEquals("submitting guards against a double-fire", 1, api.eraseCalls)
        }

    @Test
    fun `a refused deletion returns to the warning panel with the detail inline`() =
        runTest(dispatcher) {
            val detail = "У вас нет права удалить этот аккаунт."
            val api = FakeAccountDeletionApi(result = EraseAccountResult.Refused(detail))
            val viewModel = AccountDeletionViewModel(api = api, ioDispatcher = dispatcher)

            viewModel.askConfirmation()
            viewModel.confirmDeletion()
            advanceUntilIdle()

            val ready = viewModel.state.value as AccountDeletionUiState.Ready
            assertEquals(detail, ready.refusal)
            assertNull(ready.submitError)
            assertFalse(ready.confirming)
            assertFalse(ready.submitting)
        }

    @Test
    fun `a failed deletion returns to the warning panel with the transport failure inline`() =
        runTest(dispatcher) {
            val api = FakeAccountDeletionApi(result = EraseAccountResult.Failed(NetworkFailure.NoConnection))
            val viewModel = AccountDeletionViewModel(api = api, ioDispatcher = dispatcher)

            viewModel.askConfirmation()
            viewModel.confirmDeletion()
            advanceUntilIdle()

            val ready = viewModel.state.value as AccountDeletionUiState.Ready
            assertEquals(NetworkFailure.NoConnection, ready.submitError)
            assertNull(ready.refusal)
            assertFalse(ready.confirming)
            assertFalse(ready.submitting)
        }

    private class FakeAccountDeletionApi(
        private val result: EraseAccountResult = EraseAccountResult.Requested,
        private val hang: Boolean = false,
    ) : AccountDeletionApi {
        var eraseCalls: Int = 0

        override suspend fun eraseAccount(): EraseAccountResult {
            eraseCalls++
            if (hang) awaitCancellation()
            return result
        }
    }
}
