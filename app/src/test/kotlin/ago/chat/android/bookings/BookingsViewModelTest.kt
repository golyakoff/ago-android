package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.ContactsResult
import ago.chat.android.core.domain.bookings.PendingBooking
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import ago.chat.android.core.domain.bookings.PhoneRevealsResult
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.bookings.ServicesResult
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.persons.PersonProfile
import ago.chat.android.core.domain.persons.PersonsApi
import ago.chat.android.core.domain.persons.PersonsResult
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
 *
 * `26-163`: the display-merge tests at the bottom mirror `ConfirmedBookingsViewModelTest`'s own for the
 * identical `PersonsApi` merge, restated for [PendingBooking]; the fixture's `personsApi` defaults to an
 * empty answer so every pre-existing test keeps asserting the bare, un-merged rows it always did.
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
            val viewModel = viewModel(api)

            dispatcher.scheduler.runCurrent()

            assertEquals(BookingsUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a loaded queue is sorted oldest-deadline-first`() =
        runTest(dispatcher) {
            val soon = booking(id = "soon", confirmationDeadline = "2026-09-22T10:00:00Z")
            val later = booking(id = "later", confirmationDeadline = "2026-09-22T12:00:00Z")
            val api = FakeBookingsApi(result = PendingBookingsResult.Loaded(listOf(later, soon)))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(BookingsUiState.Loaded(listOf(soon, later)), viewModel.state.value)
        }

    @Test
    fun `NotConfigured passes straight through`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PendingBookingsResult.NotConfigured)
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(BookingsUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PendingBookingsResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(BookingsUiState.Failed(BookingsQueueFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `refresh asks the server again`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PendingBookingsResult.Failed(BookingsQueueFailure.Unexpected))
            val viewModel = viewModel(api)
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
            val viewModel = viewModel(api)
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
            val viewModel = viewModel(api)
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
            val viewModel = viewModel(api)
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
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.cancel("a")
            advanceUntilIdle()

            assertEquals(listOf("a"), api.cancelCalls)
            // `docs/backlog/26-49-*.md`'s own Scope item 3: the queue is re-read (still one call ahead of
            // the reject/cancel call itself) before the refusal is ever shown.
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
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.reject("a")
            advanceUntilIdle()

            assertEquals(listOf("a"), api.rejectCalls)
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
            val viewModel = viewModel(api)
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

    // `26-163`/`adr/0184`: the chat-registry display-merge, restated from `ConfirmedBookingsViewModelTest`.

    @Test
    fun `a loaded queue asks the person registry once for every distinct person, and merges the names it knows`() =
        runTest(dispatcher) {
            val a = booking(id = "a", confirmationDeadline = "2026-09-22T10:00:00Z", customerId = "p1")
            val b = booking(id = "b", confirmationDeadline = "2026-09-22T11:00:00Z", customerId = "p2")
            val c = booking(id = "c", confirmationDeadline = "2026-09-22T12:00:00Z", customerId = "p1")
            val api = FakeBookingsApi(result = PendingBookingsResult.Loaded(listOf(a, b, c)))
            val personsApi =
                FakePersonsApi(
                    result =
                        PersonsResult.Loaded(
                            listOf(
                                PersonProfile(personId = "p1", displayName = "Анна Ковалёва"),
                                // A person with nobody's name recorded yet - left alone, never invented.
                                PersonProfile(personId = "p2", displayName = null),
                            ),
                        ),
                )
            val viewModel = viewModel(api, personsApi)

            advanceUntilIdle()

            assertEquals(1, personsApi.fetchCalls)
            assertEquals(listOf("p1", "p2"), personsApi.requestedIds)
            assertEquals(
                BookingsUiState.Loaded(
                    listOf(
                        a.copy(customerDisplayName = "Анна Ковалёва"),
                        b,
                        c.copy(customerDisplayName = "Анна Ковалёва"),
                    ),
                ),
                viewModel.state.value,
            )
        }

    @Test
    fun `an unreachable person registry leaves the queue loaded with bare rows, never an error`() =
        runTest(dispatcher) {
            val a = booking(id = "a", confirmationDeadline = "2026-09-22T10:00:00Z")
            val api = FakeBookingsApi(result = PendingBookingsResult.Loaded(listOf(a)))
            val viewModel = viewModel(api, FakePersonsApi(result = PersonsResult.Failed(NetworkFailure.NoConnection)))

            advanceUntilIdle()

            assertEquals(BookingsUiState.Loaded(listOf(a)), viewModel.state.value)
        }

    @Test
    fun `an empty queue never asks the person registry at all`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PendingBookingsResult.Loaded(emptyList()))
            val personsApi = FakePersonsApi()
            viewModel(api, personsApi)

            advanceUntilIdle()

            assertEquals(0, personsApi.fetchCalls)
        }

    @Test
    fun `the re-read after a veto is merged too, so a surviving row keeps its name`() =
        runTest(dispatcher) {
            val a = booking(id = "a", confirmationDeadline = "2026-09-22T10:00:00Z", customerId = "p1")
            val b = booking(id = "b", confirmationDeadline = "2026-09-22T12:00:00Z", customerId = "p2")
            val api =
                FakeBookingsApi(result = PendingBookingsResult.Loaded(listOf(a, b))).apply {
                    onAction = { result = PendingBookingsResult.Loaded(listOf(b)) }
                }
            val personsApi =
                FakePersonsApi(result = PersonsResult.Loaded(listOf(PersonProfile(personId = "p2", displayName = "Мария Орлова"))))
            val viewModel = viewModel(api, personsApi)
            advanceUntilIdle()

            viewModel.reject("a")
            advanceUntilIdle()

            assertEquals(2, personsApi.fetchCalls)
            assertEquals(BookingsUiState.Loaded(listOf(b.copy(customerDisplayName = "Мария Орлова"))), viewModel.state.value)
        }

    private fun viewModel(
        api: BookingsApi,
        personsApi: PersonsApi = FakePersonsApi(),
    ) = BookingsViewModel(api = api, personsApi = personsApi, ioDispatcher = dispatcher)

    private fun booking(
        id: String,
        confirmationDeadline: String,
        customerId: String = "person-$id",
    ) = PendingBooking(
        bookingId = id,
        calendarId = "calendar-$id",
        workerId = "worker-$id",
        workerDisplayName = "Ирина Соколова",
        serviceId = "service-$id",
        serviceName = "Стрижка",
        customerId = customerId,
        startsAt = "2026-09-22T09:00:00Z",
        endsAt = "2026-09-22T09:30:00Z",
        localDate = "2026-09-22",
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

        // `26-163`: `BookingsViewModel` no longer marks a no-show - `Event.MarkNoShow` accepts only a
        // `Booked` row, so a pending queue has nothing to aim this at. Throwing here is what proves it.
        override suspend fun markNoShow(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("a pending booking can never be a no-show")

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

        // `26-74` widened `BookingsApi` with the reveal audit trail - `BookingsViewModel` reads and
        // vetoes the pending queue alone; it never reads that trail.
        override suspend fun fetchPhoneReveals(
            before: String?,
            limit: Int?,
        ): PhoneRevealsResult = throw UnsupportedOperationException("not used by this class")
    }

    /** `26-163`: the identical fake `ConfirmedBookingsViewModelTest`/`ContactsViewModelTest` already
     * establish - a single canned answer, no server-shaped state to fake. Defaults to an empty
     * [PersonsResult.Loaded] so every pre-existing test above keeps asserting bare, un-merged rows. */
    private class FakePersonsApi(
        var result: PersonsResult = PersonsResult.Loaded(emptyList()),
    ) : PersonsApi {
        var fetchCalls: Int = 0
            private set
        var requestedIds: List<String> = emptyList()
            private set

        override suspend fun fetchPersons(personIds: List<String>): PersonsResult {
            fetchCalls++
            requestedIds = personIds
            return result
        }
    }
}
