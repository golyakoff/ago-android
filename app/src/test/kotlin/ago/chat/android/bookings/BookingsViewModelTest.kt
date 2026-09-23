package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.PendingBooking
import ago.chat.android.core.domain.bookings.PendingBookingsResult
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

/**
 * `26-48`: the whole state machine — load once, land on one of the four [BookingsUiState] arms, and
 * retry on demand. The `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * `ConversationListViewModelTest` already establishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookingsViewModelTest {
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
            val api = FakeBookingsApi(hangFetch = true)
            val viewModel = BookingsViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(BookingsUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a loaded queue is sorted oldest-deadline-first`() =
        runTest(dispatcher) {
            val soon = booking(id = "soon", confirmationDeadline = "2026-09-22T10:00:00Z")
            val later = booking(id = "later", confirmationDeadline = "2026-09-22T12:00:00Z")
            val api = FakeBookingsApi(result = PendingBookingsResult.Loaded(listOf(later, soon)))
            val viewModel = BookingsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(BookingsUiState.Loaded(listOf(soon, later)), viewModel.state.value)
        }

    @Test
    fun `NotConfigured passes straight through`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PendingBookingsResult.NotConfigured)
            val viewModel = BookingsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(BookingsUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PendingBookingsResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = BookingsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(BookingsUiState.Failed(BookingsQueueFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `refresh asks the server again`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PendingBookingsResult.Failed(BookingsQueueFailure.Unexpected))
            val viewModel = BookingsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.fetchCalls)

            api.result = PendingBookingsResult.Loaded(emptyList())
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(2, api.fetchCalls)
            assertEquals(BookingsUiState.Loaded(emptyList()), viewModel.state.value)
        }

    private fun booking(
        id: String,
        confirmationDeadline: String,
    ) = PendingBooking(
        bookingId = id,
        calendarId = "calendar-$id",
        workerId = "worker-$id",
        serviceId = "service-$id",
        startsAt = "2026-09-22T09:00:00Z",
        endsAt = "2026-09-22T09:30:00Z",
        confirmationDeadline = confirmationDeadline,
    )

    private class FakeBookingsApi(
        var result: PendingBookingsResult = PendingBookingsResult.NotConfigured,
        private val hangFetch: Boolean = false,
    ) : BookingsApi {
        var fetchCalls: Int = 0
            private set

        override suspend fun fetchPendingQueue(): PendingBookingsResult {
            fetchCalls++
            if (hangFetch) awaitCancellation()
            return result
        }

        // `26-51` widened `BookingsApi` with a second method this class has no test of its own for -
        // never called by `BookingsViewModel`, which only ever reads the pending queue.
        override suspend fun fetchConfirmedBookings(
            from: String,
            to: String,
        ): ConfirmedBookingsResult = throw UnsupportedOperationException("BookingsViewModel never calls this")
    }
}
