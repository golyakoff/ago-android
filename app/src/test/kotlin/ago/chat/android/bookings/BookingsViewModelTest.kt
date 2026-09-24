package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.ContactsResult
import ago.chat.android.core.domain.bookings.PendingBooking
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.bookings.ServicesResult
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

    @Test
    fun `rejecting a booking marks only that row busy, immediately, before the server answers`() =
        runTest(dispatcher) {
            val a = booking(id = "a", confirmationDeadline = "2026-09-22T10:00:00Z")
            val b = booking(id = "b", confirmationDeadline = "2026-09-22T12:00:00Z")
            val api = FakeBookingsApi(result = PendingBookingsResult.Loaded(listOf(a, b)), hangAction = true)
            val viewModel = BookingsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.reject("a")

            assertEquals(
                BookingsUiState.Loaded(listOf(a, b), busyBookingIds = setOf("a")),
                viewModel.state.value,
            )
        }

    @Test
    fun `rapidly tapping one row's action twice sends exactly one server call`() =
        runTest(dispatcher) {
            val a = booking(id = "a", confirmationDeadline = "2026-09-22T10:00:00Z")
            val api = FakeBookingsApi(result = PendingBookingsResult.Loaded(listOf(a)), hangAction = true)
            val viewModel = BookingsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.reject("a")
            // Lets the first tap's own coroutine actually reach the (hung) server call - the busy-set
            // check `act` makes is synchronous, before the launch, so the second tap below is blocked by
            // it either way; running the scheduler here only proves the first tap's own call really
            // happened, the same `hangFetch`/`runCurrent` shape this file's own first test establishes.
            dispatcher.scheduler.runCurrent()
            viewModel.reject("a")
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.rejectCalls.size)
        }

    @Test
    fun `a successful reject re-reads the queue and the row is gone`() =
        runTest(dispatcher) {
            val a = booking(id = "a", confirmationDeadline = "2026-09-22T10:00:00Z")
            val b = booking(id = "b", confirmationDeadline = "2026-09-22T12:00:00Z")
            val api =
                FakeBookingsApi(result = PendingBookingsResult.Loaded(listOf(a, b))).apply {
                    onAction = { result = PendingBookingsResult.Loaded(listOf(b)) }
                }
            val viewModel = BookingsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.reject("a")
            advanceUntilIdle()

            assertEquals(listOf("a"), api.rejectCalls)
            assertEquals(2, api.fetchCalls)
            assertEquals(BookingsUiState.Loaded(listOf(b)), viewModel.state.value)
        }

    @Test
    fun `a refusal re-reads the queue first, then shows the server's own detail`() =
        runTest(dispatcher) {
            val a = booking(id = "a", confirmationDeadline = "2026-09-22T10:00:00Z")
            val api =
                FakeBookingsApi(result = PendingBookingsResult.Loaded(listOf(a))).apply {
                    actionResult = BookingActionResult.Refused("Запись уже подтверждена сборщиком.")
                }
            val viewModel = BookingsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.cancel("a")
            advanceUntilIdle()

            assertEquals(listOf("a"), api.cancelCalls)
            // `docs/backlog/26-49-*.md`'s own Scope item 3: the queue is re-read (still one call ahead of
            // the reject/cancel/no-show call itself) before the refusal is ever shown.
            assertEquals(2, api.fetchCalls)
            assertEquals(
                BookingsUiState.Loaded(
                    listOf(a),
                    actionError = BookingActionErrorUi.ServerRefusal("Запись уже подтверждена сборщиком."),
                ),
                viewModel.state.value,
            )
        }

    @Test
    fun `a transport failure re-reads the queue and renders as Unavailable, never a fabricated detail`() =
        runTest(dispatcher) {
            val a = booking(id = "a", confirmationDeadline = "2026-09-22T10:00:00Z")
            val api =
                FakeBookingsApi(result = PendingBookingsResult.Loaded(listOf(a))).apply {
                    actionResult = BookingActionResult.Failed(BookingsQueueFailure.Transport)
                }
            val viewModel = BookingsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.markNoShow("a")
            advanceUntilIdle()

            assertEquals(listOf("a"), api.noShowCalls)
            assertEquals(
                BookingsUiState.Loaded(listOf(a), actionError = BookingActionErrorUi.Unavailable(BookingsQueueFailure.Transport)),
                viewModel.state.value,
            )
        }

    @Test
    fun `a successful action clears a previously shown action error`() =
        runTest(dispatcher) {
            val a = booking(id = "a", confirmationDeadline = "2026-09-22T10:00:00Z")
            val api = FakeBookingsApi(result = PendingBookingsResult.Loaded(listOf(a)))
            val viewModel = BookingsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            api.actionResult = BookingActionResult.Refused("на секунду опоздали")
            viewModel.cancel("a")
            advanceUntilIdle()
            assertEquals(
                BookingsUiState.Loaded(listOf(a), actionError = BookingActionErrorUi.ServerRefusal("на секунду опоздали")),
                viewModel.state.value,
            )

            api.actionResult = BookingActionResult.Succeeded
            viewModel.cancel("a")
            advanceUntilIdle()

            assertEquals(BookingsUiState.Loaded(listOf(a)), viewModel.state.value)
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
        var actionResult: BookingActionResult = BookingActionResult.Succeeded,
        private val hangAction: Boolean = false,
        /** Runs right before an action returns [actionResult] - the hook `a successful reject re-reads
         * the queue and the row is gone` uses to make [result] reflect the server's own side effect,
         * the same "the write happened, a fresh read is how it is observed" shape the real
         * `KtorBookingsApi` and `BookingsViewModel.act` both already establish. */
        var onAction: () -> Unit = {},
    ) : BookingsApi {
        var fetchCalls: Int = 0
            private set
        val rejectCalls: MutableList<String> = mutableListOf()
        val cancelCalls: MutableList<String> = mutableListOf()
        val noShowCalls: MutableList<String> = mutableListOf()

        override suspend fun fetchPendingQueue(): PendingBookingsResult {
            fetchCalls++
            if (hangFetch) awaitCancellation()
            return result
        }

        // `26-51`/`26-52` widened `BookingsApi` with two more methods this class has no test of its own
        // for - never called by `BookingsViewModel`, which only ever reads the pending queue.
        override suspend fun fetchConfirmedBookings(
            from: String,
            to: String,
        ): ConfirmedBookingsResult = throw UnsupportedOperationException("BookingsViewModel never calls this")

        override suspend fun fetchContacts(): ContactsResult = throw UnsupportedOperationException("BookingsViewModel never calls this")

        // `26-53` widened `BookingsApi` with a fifth method this class has no test of its own for -
        // `BookingsViewModel` never reveals a phone; that is `ContactsViewModel`'s own job.
        override suspend fun revealCustomerPhone(
            customerId: String,
            surface: String,
        ): RevealPhoneResult = throw UnsupportedOperationException("BookingsViewModel never calls this")

        override suspend fun rejectBooking(bookingId: String): BookingActionResult {
            rejectCalls.add(bookingId)
            return respondToAction()
        }

        override suspend fun cancelBooking(bookingId: String): BookingActionResult {
            cancelCalls.add(bookingId)
            return respondToAction()
        }

        override suspend fun markNoShow(bookingId: String): BookingActionResult {
            noShowCalls.add(bookingId)
            return respondToAction()
        }

        private suspend fun respondToAction(): BookingActionResult {
            if (hangAction) awaitCancellation()
            onAction()
            return actionResult
        }

        // `26-96` widened `BookingsApi` with the service dictionary and its edit - neither of which
        // this class reads or writes.
        override suspend fun fetchServices(): ServicesResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun updateService(
            serviceId: String,
            name: String,
            durationMinutes: Int,
            priceMinorUnits: Int?,
            priceIsFrom: Boolean,
            description: String?,
            isActive: Boolean,
        ): BookingActionResult = throw UnsupportedOperationException("not used by this class")
    }
}
