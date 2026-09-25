package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfiguredService
import ago.chat.android.core.domain.workers.Worker
import ago.chat.android.core.domain.workers.WorkerCalendar
import ago.chat.android.core.domain.workers.WorkerDetailResult
import ago.chat.android.core.domain.workers.WorkerDraft
import ago.chat.android.core.domain.workers.WorkersApi
import ago.chat.android.core.domain.workers.WorkersResult
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
 * `26-140`: the worker dictionary's own read and its three write paths — the identical
 * `StandardTestDispatcher`/`Dispatchers.setMain` shape every sibling view-model test in this package
 * already establishes ([ServicesViewModelTest]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MastersViewModelTest {
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
            val api = FakeWorkersApi(hangFetch = true)
            val viewModel = MastersViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(MastersUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `the roster arrives with its calendars and services`() =
        runTest(dispatcher) {
            val api = FakeWorkersApi(result = loaded())
            val viewModel = MastersViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val state = viewModel.state.value as MastersUiState.Loaded
            assertEquals(1, state.workers.size)
            assertEquals(listOf(calendar()), state.calendars)
            assertEquals(listOf(service()), state.services)
        }

    @Test
    fun `NotConfigured passes straight through`() =
        runTest(dispatcher) {
            val api = FakeWorkersApi(result = WorkersResult.NotConfigured)
            val viewModel = MastersViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(MastersUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a failed read becomes a refusal carrying the adapter's own classification`() =
        runTest(dispatcher) {
            val api = FakeWorkersApi(result = WorkersResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = MastersViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(MastersUiState.Failed(BookingsQueueFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `editing prefills the form from the row, calendar and services and all`() =
        runTest(dispatcher) {
            val api = FakeWorkersApi(result = loaded())
            val viewModel = MastersViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.edit(worker())

            val form = (viewModel.state.value as MastersUiState.Loaded).editing!!
            assertEquals("w1", form.workerId)
            assertEquals("Ivanov", form.lastName)
            assertEquals("Ivanovich", form.middleName)
            assertEquals("c1", form.calendarId)
            assertEquals(setOf("s1"), form.serviceIds)
            assertFalse(form.isCreating)
        }

    @Test
    fun `startAdd opens a blank form with the first calendar pre-selected`() =
        runTest(dispatcher) {
            val api = FakeWorkersApi(result = loaded())
            val viewModel = MastersViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.startAdd()

            val form = (viewModel.state.value as MastersUiState.Loaded).editing!!
            assertTrue(form.isCreating)
            assertEquals("c1", form.calendarId)
            assertEquals("", form.lastName)
        }

    @Test
    fun `startAdd is a no-op when there is no calendar to put a worker on`() =
        runTest(dispatcher) {
            val api = FakeWorkersApi(result = WorkersResult.Loaded(listOf(worker()), emptyList(), listOf(service())))
            val viewModel = MastersViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.startAdd()

            assertNull((viewModel.state.value as MastersUiState.Loaded).editing)
        }

    @Test
    fun `creating sends the draft with its chosen calendar and re-reads`() =
        runTest(dispatcher) {
            val api = FakeWorkersApi(result = loaded())
            val viewModel = MastersViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.startAdd()
            val form = (viewModel.state.value as MastersUiState.Loaded).editing!!
            viewModel.submit(form.copy(lastName = "Petrov", firstName = "Petr", serviceIds = setOf("s1")))
            advanceUntilIdle()

            val draft = api.createCalls.single()
            assertEquals("Petrov", draft.lastName)
            assertEquals("Petr", draft.firstName)
            assertEquals("c1", draft.calendarId)
            assertEquals(listOf("s1"), draft.serviceIds)
            // The authoritative answer is always the next read - initial load plus this re-read.
            assertEquals(2, api.fetchCalls)
            assertNull((viewModel.state.value as MastersUiState.Loaded).editing)
        }

    @Test
    fun `creating with no calendar chosen sends no request at all`() =
        runTest(dispatcher) {
            val api = FakeWorkersApi(result = loaded())
            val viewModel = MastersViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.startAdd()
            val form = (viewModel.state.value as MastersUiState.Loaded).editing!!
            viewModel.submit(form.copy(calendarId = null, lastName = "Petrov", firstName = "Petr"))
            advanceUntilIdle()

            assertTrue(api.createCalls.isEmpty())
        }

    @Test
    fun `updating sends every field beside the workerId and re-reads`() =
        runTest(dispatcher) {
            val api = FakeWorkersApi(result = loaded())
            val viewModel = MastersViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.edit(worker())
            val form = (viewModel.state.value as MastersUiState.Loaded).editing!!
            viewModel.submit(form.copy(lastName = "Sidorov"))
            advanceUntilIdle()

            val (id, draft) = api.updateCalls.single()
            assertEquals("w1", id)
            assertEquals("Sidorov", draft.lastName)
            assertEquals(listOf("s1"), draft.serviceIds)
            assertEquals(2, api.fetchCalls)
        }

    /** «Снять с активных» is [MastersViewModel.toggleActive], a full replace with the flag flipped — so
     * the worker's own current name and services must still travel, because the endpoint replaces the
     * whole record. */
    @Test
    fun `deactivating sends the row's own current fields beside the flipped flag`() =
        runTest(dispatcher) {
            val api = FakeWorkersApi(result = loaded())
            val viewModel = MastersViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.toggleActive(worker())
            advanceUntilIdle()

            val (id, draft) = api.updateCalls.single()
            assertEquals("w1", id)
            assertFalse(draft.isActive)
            assertEquals("Ivanov", draft.lastName)
            assertEquals(listOf("s1"), draft.serviceIds)
            assertEquals(2, api.fetchCalls)
        }

    @Test
    fun `a refused delete keeps the row and shows the server's own words, without re-reading`() =
        runTest(dispatcher) {
            val api =
                FakeWorkersApi(
                    result = loaded(),
                    deleteResult = BookingActionResult.Refused("This master has bookings. Deactivate him instead."),
                )
            val viewModel = MastersViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.delete("w1")
            advanceUntilIdle()

            assertEquals(listOf("w1"), api.deleteCalls)
            // Only the initial read - a refusal never reloads.
            assertEquals(1, api.fetchCalls)
            val state = viewModel.state.value as MastersUiState.Loaded
            assertEquals(1, state.workers.size)
            assertEquals(
                BookingActionErrorUi.ServerRefusal("This master has bookings. Deactivate him instead."),
                state.actionError,
            )
        }

    @Test
    fun `a successful delete re-reads the roster`() =
        runTest(dispatcher) {
            val api = FakeWorkersApi(result = loaded())
            val viewModel = MastersViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.delete("w1")
            advanceUntilIdle()

            assertEquals(2, api.fetchCalls)
        }

    @Test
    fun `a second write on the same worker while one is in flight is a no-op`() =
        runTest(dispatcher) {
            val api = FakeWorkersApi(result = loaded(), hangWrite = true)
            val viewModel = MastersViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.delete("w1")
            dispatcher.scheduler.runCurrent()
            viewModel.delete("w1")
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.deleteCalls.size)
        }

    private fun worker() =
        Worker(
            workerId = "w1",
            lastName = "Ivanov",
            firstName = "Ivan",
            middleName = "Ivanovich",
            displayName = "Ivan Ivanov",
            isActive = true,
            serviceIds = listOf("s1"),
            calendarId = "c1",
        )

    private fun calendar() = WorkerCalendar(calendarId = "c1", name = "Main")

    private fun service() =
        ConfiguredService(
            serviceId = "s1",
            name = "Haircut",
            durationMinutes = 45,
            priceMinorUnits = null,
            priceCurrencyCode = null,
            priceIsFrom = false,
            description = null,
            isActive = true,
        )

    private fun loaded() = WorkersResult.Loaded(listOf(worker()), listOf(calendar()), listOf(service()))

    private class FakeWorkersApi(
        var result: WorkersResult = WorkersResult.NotConfigured,
        private val hangFetch: Boolean = false,
        var createResult: BookingActionResult = BookingActionResult.Succeeded,
        var updateResult: BookingActionResult = BookingActionResult.Succeeded,
        var deleteResult: BookingActionResult = BookingActionResult.Succeeded,
        private val hangWrite: Boolean = false,
    ) : WorkersApi {
        var fetchCalls: Int = 0
            private set
        val createCalls: MutableList<WorkerDraft> = mutableListOf()
        val updateCalls: MutableList<Pair<String, WorkerDraft>> = mutableListOf()
        val deleteCalls: MutableList<String> = mutableListOf()

        override suspend fun fetchWorkers(): WorkersResult {
            fetchCalls++
            if (hangFetch) awaitCancellation()
            return result
        }

        // The Masters screen re-reads the whole roster, never one worker by id - this arm is unused here.
        override suspend fun fetchWorker(workerId: String): WorkerDetailResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun createWorker(draft: WorkerDraft): BookingActionResult {
            createCalls.add(draft)
            if (hangWrite) awaitCancellation()
            return createResult
        }

        override suspend fun updateWorker(
            workerId: String,
            draft: WorkerDraft,
        ): BookingActionResult {
            updateCalls.add(workerId to draft)
            if (hangWrite) awaitCancellation()
            return updateResult
        }

        override suspend fun deleteWorker(workerId: String): BookingActionResult {
            deleteCalls.add(workerId)
            if (hangWrite) awaitCancellation()
            return deleteResult
        }
    }
}
