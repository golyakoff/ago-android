package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmPhoneResult
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.ContactsResult
import ago.chat.android.core.domain.bookings.ManualBookingResult
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import ago.chat.android.core.domain.bookings.PersonBookingsResult
import ago.chat.android.core.domain.bookings.PhoneCandidatesResult
import ago.chat.android.core.domain.bookings.PhoneRevealsResult
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.bookings.ServicesResult
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
import org.junit.Before
import org.junit.Test

/**
 * `26-209`/`adr/0187`: «Перенести оператором»'s own view model - the same-worker available-slot read
 * (filtered to [WorkerSlotStatus.Available] alone, [RescheduleBookingUiState.Loaded]'s own doc comment)
 * and the reschedule write's own `204`-or-refusal-or-failure three-way split.
 *
 * The two fakes below are nested, not top-level - `WorkerSlotsViewModelTest`'s own top-level
 * `FakeWorkerSlotsApi`/`FakeBookingsApi` already claim those exact simple names in this same package, and
 * a top-level `private` class is only *visibility*-scoped to its file, not *name*-scoped: two top-level
 * classes sharing a name in one package are a genuine JVM redeclaration regardless of `private`. Nesting
 * inside this test class (the identical shape `ConfirmedBookingsViewModelTest`/`ContactsViewModelTest`/
 * `BookingsViewModelTest`/`ServicesViewModelTest` already use for their own same-named fakes) sidesteps
 * that collision instead of inventing a fourth spelling of either name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RescheduleBookingViewModelTest {
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
            val viewModel = RescheduleBookingViewModel(FakeWorkerSlotsApi(hangFetch = true), FakeBookingsApi(), dispatcher)

            viewModel.open("b1", "w1")
            dispatcher.scheduler.runCurrent()

            assertEquals(RescheduleBookingUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `only Available slots are offered as reschedule targets`() =
        runTest(dispatcher) {
            val slots = listOf(SLOT_AVAILABLE, SLOT_BOOKED, SLOT_CANCELLED)
            val viewModel = RescheduleBookingViewModel(FakeWorkerSlotsApi(slots = slots), FakeBookingsApi(), dispatcher)

            viewModel.open("b1", "w1")
            advanceUntilIdle()

            val loaded = viewModel.state.value as RescheduleBookingUiState.Loaded
            assertEquals(listOf(SLOT_AVAILABLE), loaded.availableSlots)
        }

    @Test
    fun `a deployment with no calendar backend is NotConfigured, not a failure`() =
        runTest(dispatcher) {
            val viewModel = RescheduleBookingViewModel(FakeWorkerSlotsApi(notConfigured = true), FakeBookingsApi(), dispatcher)

            viewModel.open("b1", "w1")
            advanceUntilIdle()

            assertEquals(RescheduleBookingUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a genuine slot-range refusal is shown verbatim`() =
        runTest(dispatcher) {
            val api = FakeWorkerSlotsApi(refusal = "The range must end on or after it starts.")
            val viewModel = RescheduleBookingViewModel(api, FakeBookingsApi(), dispatcher)

            viewModel.open("b1", "w1")
            advanceUntilIdle()

            assertEquals(RescheduleBookingUiState.Refused("The range must end on or after it starts."), viewModel.state.value)
        }

    @Test
    fun `a slot-read failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeWorkerSlotsApi(fail = true)
            val viewModel = RescheduleBookingViewModel(api, FakeBookingsApi(), dispatcher)

            viewModel.open("b1", "w1")
            advanceUntilIdle()

            assertEquals(RescheduleBookingUiState.Failed(BookingsQueueFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `re-opening the same booking-worker pair does not re-fetch`() =
        runTest(dispatcher) {
            val api = FakeWorkerSlotsApi(slots = listOf(SLOT_AVAILABLE))
            val viewModel = RescheduleBookingViewModel(api, FakeBookingsApi(), dispatcher)

            viewModel.open("b1", "w1")
            advanceUntilIdle()
            viewModel.open("b1", "w1")
            advanceUntilIdle()

            assertEquals(1, api.fetchCount)
        }

    @Test
    fun `opening a different booking re-fetches`() =
        runTest(dispatcher) {
            val api = FakeWorkerSlotsApi(slots = listOf(SLOT_AVAILABLE))
            val viewModel = RescheduleBookingViewModel(api, FakeBookingsApi(), dispatcher)

            viewModel.open("b1", "w1")
            advanceUntilIdle()
            viewModel.open("b2", "w1")
            advanceUntilIdle()

            assertEquals(2, api.fetchCount)
        }

    // `26-209`'s own fails-before table (case 1/3): a `204` moves this sheet's state straight to `Saved`,
    // the signal `RescheduleBookingSheet` reads to close both sheets and trigger a fresh confirmed-range
    // read on the caller's side.
    @Test
    fun `a successful reschedule reaches Saved`() =
        runTest(dispatcher) {
            val bookingsApi = FakeBookingsApi(rescheduleResult = BookingActionResult.Succeeded)
            val viewModel = RescheduleBookingViewModel(FakeWorkerSlotsApi(slots = listOf(SLOT_AVAILABLE)), bookingsApi, dispatcher)
            viewModel.open("b1", "w1")
            advanceUntilIdle()

            viewModel.reschedule(SLOT_AVAILABLE.eventId)
            advanceUntilIdle()

            assertEquals(RescheduleBookingUiState.Saved, viewModel.state.value)
            assertEquals("b1" to SLOT_AVAILABLE.eventId, bookingsApi.lastReschedule)
        }

    // `26-209`'s own fails-before table (case 2/3): the slot-already-taken refusal
    // (`BookingLifecycleErrors.SlotNoLongerAvailable`) is shown to the operator exactly as the server
    // worded it, never a client-fabricated message, and the sheet stays open (not `Saved`) so a different
    // slot can be tried.
    @Test
    fun `a refusal (slot no longer available) is shown verbatim, and the sheet stays open`() =
        runTest(dispatcher) {
            val bookingsApi =
                FakeBookingsApi(rescheduleResult = BookingActionResult.Refused("Слот уже занят, выберите другое время."))
            val viewModel = RescheduleBookingViewModel(FakeWorkerSlotsApi(slots = listOf(SLOT_AVAILABLE)), bookingsApi, dispatcher)
            viewModel.open("b1", "w1")
            advanceUntilIdle()

            viewModel.reschedule(SLOT_AVAILABLE.eventId)
            advanceUntilIdle()

            val loaded = viewModel.state.value as RescheduleBookingUiState.Loaded
            assertEquals(BookingActionErrorUi.ServerRefusal("Слот уже занят, выберите другое время."), loaded.actionError)
            assertNull(loaded.reschedulingEventId)
        }

    // `26-209`'s own fails-before table (case 3/3): a non-refusal failure (transport, or a non-2xx with no
    // problem-details body) is [BookingActionErrorUi.Unavailable], never a fabricated detail string.
    @Test
    fun `a reschedule failure is shown as Unavailable, and the sheet stays open`() =
        runTest(dispatcher) {
            val bookingsApi = FakeBookingsApi(rescheduleResult = BookingActionResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = RescheduleBookingViewModel(FakeWorkerSlotsApi(slots = listOf(SLOT_AVAILABLE)), bookingsApi, dispatcher)
            viewModel.open("b1", "w1")
            advanceUntilIdle()

            viewModel.reschedule(SLOT_AVAILABLE.eventId)
            advanceUntilIdle()

            val loaded = viewModel.state.value as RescheduleBookingUiState.Loaded
            assertEquals(BookingActionErrorUi.Unavailable(BookingsQueueFailure.Transport), loaded.actionError)
            assertNull(loaded.reschedulingEventId)
        }

    @Test
    fun `tapping a slot while a reschedule is already in flight sends exactly one write`() =
        runTest(dispatcher) {
            val bookingsApi = FakeBookingsApi(hangReschedule = true)
            val viewModel = RescheduleBookingViewModel(FakeWorkerSlotsApi(slots = listOf(SLOT_AVAILABLE)), bookingsApi, dispatcher)
            viewModel.open("b1", "w1")
            advanceUntilIdle()

            viewModel.reschedule(SLOT_AVAILABLE.eventId)
            dispatcher.scheduler.runCurrent()
            viewModel.reschedule(SLOT_AVAILABLE.eventId)
            dispatcher.scheduler.runCurrent()

            assertEquals(1, bookingsApi.rescheduleCalls)
            val loaded = viewModel.state.value as RescheduleBookingUiState.Loaded
            assertEquals(SLOT_AVAILABLE.eventId, loaded.reschedulingEventId)
        }

    private class FakeWorkerSlotsApi(
        var slots: List<WorkerSlot> = emptyList(),
        private val notConfigured: Boolean = false,
        private val refusal: String? = null,
        var fail: Boolean = false,
        private val hangFetch: Boolean = false,
    ) : WorkerSlotsApi {
        var fetchCount: Int = 0

        override suspend fun fetchSlots(
            workerId: String,
            from: String,
            to: String,
        ): WorkerSlotsResult {
            fetchCount++
            if (hangFetch) awaitCancellation()
            if (notConfigured) return WorkerSlotsResult.NotConfigured
            if (fail) return WorkerSlotsResult.Failed(BookingsQueueFailure.Transport)
            refusal?.let { return WorkerSlotsResult.Refused(it) }
            return WorkerSlotsResult.Loaded(slots)
        }
    }

    /** The identical hand-written fake shape every sibling view model test in this package already uses -
     * only [rescheduleBooking] is exercised by [RescheduleBookingViewModelTest], so every other member of
     * [BookingsApi] this fake never gets called from throws to make an accidental call to it fail loudly. */
    private class FakeBookingsApi(
        private val rescheduleResult: BookingActionResult = BookingActionResult.Succeeded,
        private val hangReschedule: Boolean = false,
    ) : BookingsApi {
        var rescheduleCalls: Int = 0
            private set
        var lastReschedule: Pair<String, String>? = null
            private set

        override suspend fun rescheduleBooking(
            bookingId: String,
            newStartEventId: String,
        ): BookingActionResult {
            rescheduleCalls++
            lastReschedule = bookingId to newStartEventId
            if (hangReschedule) awaitCancellation()
            return rescheduleResult
        }

        override suspend fun fetchPendingQueue(): PendingBookingsResult = throw UnsupportedOperationException()

        override suspend fun fetchConfirmedBookings(
            from: String,
            to: String,
        ): ConfirmedBookingsResult = throw UnsupportedOperationException()

        override suspend fun fetchContacts(): ContactsResult = throw UnsupportedOperationException()

        override suspend fun fetchPersonBookings(personId: String): PersonBookingsResult = throw UnsupportedOperationException()

        override suspend fun confirmOperatorVerifiedPhone(personId: String): ConfirmPhoneResult = throw UnsupportedOperationException()

        override suspend fun fetchPhoneCandidates(phone: String): PhoneCandidatesResult = throw UnsupportedOperationException()

        override suspend fun createManualBooking(
            calendarId: String,
            serviceId: String,
            workerId: String,
            startEventId: String,
            name: String,
            phone: String,
            reusePersonId: String?,
            email: String?,
        ): ManualBookingResult = throw UnsupportedOperationException()

        override suspend fun rejectBooking(bookingId: String): BookingActionResult = throw UnsupportedOperationException()

        override suspend fun cancelBooking(bookingId: String): BookingActionResult = throw UnsupportedOperationException()

        override suspend fun markNoShow(bookingId: String): BookingActionResult = throw UnsupportedOperationException()

        override suspend fun revealCustomerPhone(
            customerId: String,
            surface: String,
        ): RevealPhoneResult = throw UnsupportedOperationException()

        override suspend fun fetchServices(): ServicesResult = throw UnsupportedOperationException()

        override suspend fun updateService(
            serviceId: String,
            name: String,
            durationMinutes: Int,
            priceMinorUnits: Int?,
            priceIsFrom: Boolean,
            description: String?,
            isActive: Boolean,
        ): BookingActionResult = throw UnsupportedOperationException()

        override suspend fun fetchPhoneReveals(
            before: String?,
            limit: Int?,
        ): PhoneRevealsResult = throw UnsupportedOperationException()
    }

    private companion object {
        val SLOT_AVAILABLE =
            WorkerSlot(
                eventId = "e1",
                localDate = "2026-09-27",
                weekday = 0,
                startsAt = "2026-09-27T09:00:00Z",
                endsAt = "2026-09-27T09:30:00Z",
                status = WorkerSlotStatus.Available,
                rawStatus = "Available",
                serviceId = null,
                serviceName = null,
                personId = null,
                phone = null,
                masked = false,
                bookingId = null,
            )
        val SLOT_BOOKED = SLOT_AVAILABLE.copy(eventId = "e2", status = WorkerSlotStatus.Booked, rawStatus = "Booked")
        val SLOT_CANCELLED = SLOT_AVAILABLE.copy(eventId = "e3", status = WorkerSlotStatus.Cancelled, rawStatus = "Cancelled")
    }
}
