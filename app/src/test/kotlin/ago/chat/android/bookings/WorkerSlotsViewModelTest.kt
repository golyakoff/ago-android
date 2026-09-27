package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.persons.PersonProfile
import ago.chat.android.core.domain.persons.PersonsApi
import ago.chat.android.core.domain.persons.PersonsResult
import ago.chat.android.core.domain.workerslots.WorkerSlot
import ago.chat.android.core.domain.workerslots.WorkerSlotStatus
import ago.chat.android.core.domain.workerslots.WorkerSlotsApi
import ago.chat.android.core.domain.workerslots.WorkerSlotsResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `26-171` (`26-155` part 3): the «Слоты» drill-down's own view model - a fixed default range
 * (Q5, today..+14, never asserted on the fake since the range is computed inside the view model), every
 * status kept including `Cancelled` (Q6), and the shared, audited phone reveal keyed by `personId`
 * rather than by row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkerSlotsViewModelTest {
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
    fun `starts Loading before open is called`() =
        runTest(dispatcher) {
            val viewModel =
                WorkerSlotsViewModel(FakeWorkerSlotsApi(hangFetch = true), FakeBookingsApi(), FakeWorkerSlotsPersonsApi(), dispatcher)

            viewModel.open("w1")
            dispatcher.scheduler.runCurrent()

            assertEquals(WorkerSlotsUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `every slot from the read is kept, including Cancelled ones`() =
        runTest(dispatcher) {
            val slots = listOf(SLOT_AVAILABLE, SLOT_CANCELLED)
            val viewModel =
                WorkerSlotsViewModel(FakeWorkerSlotsApi(slots = slots), FakeBookingsApi(), FakeWorkerSlotsPersonsApi(), dispatcher)

            viewModel.open("w1")
            advanceUntilIdle()

            val loaded = viewModel.state.value as WorkerSlotsUiState.Loaded
            assertEquals(slots, loaded.slots)
        }

    @Test
    fun `a deployment with no calendar backend is NotConfigured, not a failure`() =
        runTest(dispatcher) {
            val viewModel =
                WorkerSlotsViewModel(FakeWorkerSlotsApi(notConfigured = true), FakeBookingsApi(), FakeWorkerSlotsPersonsApi(), dispatcher)

            viewModel.open("w1")
            advanceUntilIdle()

            assertEquals(WorkerSlotsUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a genuine server refusal is shown verbatim`() =
        runTest(dispatcher) {
            val api = FakeWorkerSlotsApi(refusal = "The range must end on or after it starts.")
            val viewModel = WorkerSlotsViewModel(api, FakeBookingsApi(), FakeWorkerSlotsPersonsApi(), dispatcher)

            viewModel.open("w1")
            advanceUntilIdle()

            assertEquals(WorkerSlotsUiState.Refused("The range must end on or after it starts."), viewModel.state.value)
        }

    @Test
    fun `re-opening the same worker id does not re-fetch`() =
        runTest(dispatcher) {
            val api = FakeWorkerSlotsApi(slots = listOf(SLOT_AVAILABLE))
            val viewModel = WorkerSlotsViewModel(api, FakeBookingsApi(), FakeWorkerSlotsPersonsApi(), dispatcher)

            viewModel.open("w1")
            advanceUntilIdle()
            viewModel.open("w1")
            advanceUntilIdle()

            assertEquals(1, api.fetchCount)
        }

    @Test
    fun `opening a different worker id re-fetches for it`() =
        runTest(dispatcher) {
            val api = FakeWorkerSlotsApi(slots = listOf(SLOT_AVAILABLE))
            val viewModel = WorkerSlotsViewModel(api, FakeBookingsApi(), FakeWorkerSlotsPersonsApi(), dispatcher)

            viewModel.open("w1")
            advanceUntilIdle()
            viewModel.open("w2")
            advanceUntilIdle()

            assertEquals(2, api.fetchCount)
            assertEquals(listOf("w1", "w2"), api.fetchedWorkerIds)
        }

    @Test
    fun `revealing unmasks every slot sharing the same personId, in place`() =
        runTest(dispatcher) {
            // Two slots of one multi-slot booking run, sharing both `bookingId` and `personId`
            // (`WorkerSlot.bookingId`'s own doc comment) - a third, unrelated slot must stay untouched.
            val runSlotOne = SLOT_AVAILABLE.copy(eventId = "e2", bookingId = "b1", personId = "p1", phone = "***1234", masked = true)
            val runSlotTwo = SLOT_AVAILABLE.copy(eventId = "e3", bookingId = "b1", personId = "p1", phone = "***1234", masked = true)
            val unrelatedSlot = SLOT_AVAILABLE.copy(eventId = "e4")
            val api = FakeWorkerSlotsApi(slots = listOf(runSlotOne, runSlotTwo, unrelatedSlot))
            val bookingsApi = FakeBookingsApi(revealResult = RevealPhoneResult.Revealed("+70001234567"))
            val viewModel = WorkerSlotsViewModel(api, bookingsApi, FakeWorkerSlotsPersonsApi(), dispatcher)
            viewModel.open("w1")
            advanceUntilIdle()

            viewModel.reveal("p1")
            advanceUntilIdle()

            val loaded = viewModel.state.value as WorkerSlotsUiState.Loaded
            assertTrue(loaded.slots.filter { it.personId == "p1" }.all { it.phone == "+70001234567" && !it.masked })
            assertNull(loaded.slots.single { it.eventId == "e4" }.phone)
            assertEquals("p1", bookingsApi.lastCustomerId)
            assertEquals("AndroidWorkerSlots", bookingsApi.lastSurface)
        }

    @Test
    fun `a reveal refusal is shown verbatim and the masked value stays on screen`() =
        runTest(dispatcher) {
            val api = FakeWorkerSlotsApi(slots = listOf(SLOT_AVAILABLE.copy(personId = "p1", phone = "***1234", masked = true)))
            val bookingsApi = FakeBookingsApi(revealResult = RevealPhoneResult.Refused("Not entitled to this customer's phone."))
            val viewModel = WorkerSlotsViewModel(api, bookingsApi, FakeWorkerSlotsPersonsApi(), dispatcher)
            viewModel.open("w1")
            advanceUntilIdle()

            viewModel.reveal("p1")
            advanceUntilIdle()

            val loaded = viewModel.state.value as WorkerSlotsUiState.Loaded
            assertEquals(
                BookingActionErrorUi.ServerRefusal("Not entitled to this customer's phone."),
                loaded.actionError,
            )
            assertEquals("***1234", loaded.slots.single().phone)
            assertTrue(loaded.slots.single().masked)
        }

    @Test
    fun `refresh re-reads the same worker id, retry after a failure`() =
        runTest(dispatcher) {
            val api = FakeWorkerSlotsApi(fail = true)
            val viewModel = WorkerSlotsViewModel(api, FakeBookingsApi(), FakeWorkerSlotsPersonsApi(), dispatcher)
            viewModel.open("w1")
            advanceUntilIdle()
            assertEquals(WorkerSlotsUiState.Failed(BookingsQueueFailure.Transport), viewModel.state.value)

            api.fail = false
            api.slots = listOf(SLOT_AVAILABLE)
            viewModel.refresh()
            advanceUntilIdle()

            assertNull((viewModel.state.value as WorkerSlotsUiState.Loaded).actionError)
            assertEquals(2, api.fetchCount)
        }

    @Test
    fun `26-203 the stored emoji pair chat's person registry answers with is merged onto every slot sharing that personId`() =
        runTest(dispatcher) {
            val slotOne = SLOT_AVAILABLE.copy(eventId = "e1", personId = "p1")
            val slotTwo = SLOT_AVAILABLE.copy(eventId = "e2", personId = "p1")
            val unrelated = SLOT_AVAILABLE.copy(eventId = "e3", personId = null)
            val api = FakeWorkerSlotsApi(slots = listOf(slotOne, slotTwo, unrelated))
            val persons =
                FakeWorkerSlotsPersonsApi(
                    result =
                        PersonsResult.Loaded(
                            listOf(PersonProfile(personId = "p1", displayName = null, emojiCreature = "🦉", emojiFood = "🍓")),
                        ),
                )
            val viewModel = WorkerSlotsViewModel(api, FakeBookingsApi(), persons, dispatcher)

            viewModel.open("w1")
            advanceUntilIdle()

            assertEquals(listOf("p1"), persons.requestedIds)
            val loaded = viewModel.state.value as WorkerSlotsUiState.Loaded
            assertTrue(loaded.slots.filter { it.personId == "p1" }.all { it.emojiCreature == "🦉" && it.emojiFood == "🍓" })
            assertNull(loaded.slots.single { it.eventId == "e3" }.emojiCreature)
        }

    @Test
    fun `26-203 an unreachable person registry leaves every slot without a pair, never fails the screen`() =
        runTest(dispatcher) {
            val slot = SLOT_AVAILABLE.copy(personId = "p1")
            val api = FakeWorkerSlotsApi(slots = listOf(slot))
            val persons = FakeWorkerSlotsPersonsApi(result = PersonsResult.Failed(NetworkFailure.NoConnection))
            val viewModel = WorkerSlotsViewModel(api, FakeBookingsApi(), persons, dispatcher)

            viewModel.open("w1")
            advanceUntilIdle()

            assertEquals(WorkerSlotsUiState.Loaded(slots = listOf(slot)), viewModel.state.value)
        }

    @Test
    fun `26-203 a worker with no slot carrying a personId asks the person registry for nothing`() =
        runTest(dispatcher) {
            val api = FakeWorkerSlotsApi(slots = listOf(SLOT_AVAILABLE.copy(personId = null)))
            val persons = FakeWorkerSlotsPersonsApi()
            val viewModel = WorkerSlotsViewModel(api, FakeBookingsApi(), persons, dispatcher)

            viewModel.open("w1")
            advanceUntilIdle()

            assertEquals(0, persons.fetchCalls)
        }

    private companion object {
        val SLOT_AVAILABLE =
            WorkerSlot(
                eventId = "e1",
                localDate = "2026-09-26",
                weekday = 6,
                startsAt = "2026-09-26T09:00:00Z",
                endsAt = "2026-09-26T09:30:00Z",
                status = WorkerSlotStatus.Available,
                rawStatus = "Available",
                serviceId = null,
                serviceName = null,
                personId = null,
                phone = null,
                masked = false,
                bookingId = null,
            )
        val SLOT_CANCELLED = SLOT_AVAILABLE.copy(eventId = "e2", status = WorkerSlotStatus.Cancelled, rawStatus = "Cancelled")
    }
}

/** `26-203`: the identical fake shape [ContactsViewModelTest]'s own `FakePersonsApi` already establishes
 * for [PersonsApi] - named differently here only because a `private` top-level class name must be unique
 * within its package's compiled module, not merely within its own file, the identical reason
 * `FakeRecutBookingsApi` gives for its own name. */
private class FakeWorkerSlotsPersonsApi(
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

private class FakeWorkerSlotsApi(
    var slots: List<WorkerSlot> = emptyList(),
    private val notConfigured: Boolean = false,
    private val refusal: String? = null,
    var fail: Boolean = false,
    private val hangFetch: Boolean = false,
) : WorkerSlotsApi {
    var fetchCount: Int = 0
    val fetchedWorkerIds: MutableList<String> = mutableListOf()

    override suspend fun fetchSlots(
        workerId: String,
        from: String,
        to: String,
    ): WorkerSlotsResult {
        fetchCount++
        fetchedWorkerIds += workerId
        if (hangFetch) awaitCancellation()
        if (notConfigured) return WorkerSlotsResult.NotConfigured
        if (fail) return WorkerSlotsResult.Failed(BookingsQueueFailure.Transport)
        refusal?.let { return WorkerSlotsResult.Refused(it) }
        return WorkerSlotsResult.Loaded(slots)
    }
}

/** The identical hand-written fake shape every sibling view model test in this package already uses -
 * only [revealCustomerPhone] is exercised by [WorkerSlotsViewModelTest], so every other member of
 * [BookingsApi] this fake never gets called from throws to make an accidental call to it fail loudly. */
private class FakeBookingsApi(
    private val revealResult: RevealPhoneResult = RevealPhoneResult.Revealed("+70000000000"),
) : BookingsApi {
    var lastCustomerId: String? = null
    var lastSurface: String? = null

    override suspend fun revealCustomerPhone(
        customerId: String,
        surface: String,
    ): RevealPhoneResult {
        lastCustomerId = customerId
        lastSurface = surface
        return revealResult
    }

    override suspend fun fetchPendingQueue() = throw UnsupportedOperationException()

    override suspend fun fetchConfirmedBookings(
        from: String,
        to: String,
    ) = throw UnsupportedOperationException()

    override suspend fun fetchContacts() = throw UnsupportedOperationException()

    override suspend fun rejectBooking(bookingId: String) = throw UnsupportedOperationException()

    override suspend fun cancelBooking(bookingId: String) = throw UnsupportedOperationException()

    override suspend fun markNoShow(bookingId: String) = throw UnsupportedOperationException()

    override suspend fun fetchServices() = throw UnsupportedOperationException()

    override suspend fun updateService(
        serviceId: String,
        name: String,
        durationMinutes: Int,
        priceMinorUnits: Int?,
        priceIsFrom: Boolean,
        description: String?,
        isActive: Boolean,
    ) = throw UnsupportedOperationException()

    override suspend fun fetchPhoneReveals(
        before: String?,
        limit: Int?,
    ) = throw UnsupportedOperationException()

    override suspend fun rescheduleBooking(
        bookingId: String,
        newStartEventId: String,
    ) = throw UnsupportedOperationException()
}
