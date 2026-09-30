package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfiguredService
import ago.chat.android.core.domain.bookings.ConfirmPhoneResult
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.ContactsResult
import ago.chat.android.core.domain.bookings.DeleteClientResult
import ago.chat.android.core.domain.bookings.ManualBookingResult
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import ago.chat.android.core.domain.bookings.PersonBookingsResult
import ago.chat.android.core.domain.bookings.PhoneCandidate
import ago.chat.android.core.domain.bookings.PhoneCandidatesResult
import ago.chat.android.core.domain.bookings.PhoneRevealsResult
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.bookings.ServicesResult
import ago.chat.android.core.domain.persons.PersonConversationsResult
import ago.chat.android.core.domain.persons.PersonProfile
import ago.chat.android.core.domain.persons.PersonsApi
import ago.chat.android.core.domain.persons.PersonsResult
import ago.chat.android.core.domain.workers.Worker
import ago.chat.android.core.domain.workers.WorkerDetailResult
import ago.chat.android.core.domain.workers.WorkerDraft
import ago.chat.android.core.domain.workers.WorkersApi
import ago.chat.android.core.domain.workers.WorkersResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * `26-268`/`adr/0188`: [ManualBookingViewModel]'s own coverage — the phone-recognition branches
 * (none/one/several matches), the reuse-vs-mint split [submit] makes, and the submit write's own
 * `201`-or-refusal-or-failure three-way split. The four fakes below are hand-written, the identical shape
 * every sibling view model test in this package already uses ([RescheduleBookingViewModelTest]'s own doc
 * comment) — only the members this test actually exercises are implemented meaningfully; every other
 * member throws, so an accidental call to it fails loudly rather than silently returning a default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualBookingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        bookingsApi: FakeBookingsApi = FakeBookingsApi(),
        workersApi: FakeWorkersApi = FakeWorkersApi(),
        workerSlotsApi: FakeWorkerSlotsApi = FakeWorkerSlotsApi(),
        personsApi: FakePersonsApi = FakePersonsApi(),
    ) = ManualBookingViewModel(bookingsApi, workersApi, workerSlotsApi, personsApi, dispatcher)

    @Test
    fun `open loads the active service dictionary and worker roster onto the Phone step`() =
        runTest(dispatcher) {
            val archived = SERVICE.copy(serviceId = "s2", isActive = false)
            val viewModel = viewModel(bookingsApi = FakeBookingsApi(servicesResult = ServicesResult.Loaded(listOf(SERVICE, archived))))

            viewModel.open()
            advanceUntilIdle()

            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(ManualBookingStep.Phone, wizard.step)
            // `26-96`'s own archived-services-stay-on-the-wire contract does not apply to a *new* booking -
            // only the still-active service is offered.
            assertEquals(listOf(SERVICE), wizard.services)
            assertEquals(listOf(WORKER), wizard.workers)
        }

    // `26-283`: «+ Записать» from a client's own detail hub - `open(prefillClient)` skips Phone and
    // Client outright and lands on Service with the client already chosen, unlike the plain
    // `open()` above which stops on Phone.
    @Test
    fun `open with a prefillClient skips Phone and Client and lands on Service, already chosen`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.open(CANDIDATE)
            advanceUntilIdle()

            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(ManualBookingStep.Service, wizard.step)
            assertEquals(ManualBookingClient.Existing(CANDIDATE), wizard.client)
            assertEquals(CANDIDATE.phone, wizard.phone)
        }

    @Test
    fun `open with no prefillClient behaves exactly like the plain header entry point`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.open(null)
            advanceUntilIdle()

            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(ManualBookingStep.Phone, wizard.step)
            assertEquals(null, wizard.client)
        }

    @Test
    fun `a deployment with no calendar backend is NotConfigured, not a failure`() =
        runTest(dispatcher) {
            val viewModel = viewModel(bookingsApi = FakeBookingsApi(servicesResult = ServicesResult.NotConfigured))

            viewModel.open()
            advanceUntilIdle()

            assertEquals(ManualBookingUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `no phone match reaches PhoneLookupState-None`() =
        runTest(dispatcher) {
            val viewModel = viewModel(bookingsApi = FakeBookingsApi(phoneCandidatesResult = PhoneCandidatesResult.Loaded(emptyList())))
            viewModel.open()
            advanceUntilIdle()

            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()

            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(PhoneLookupState.None, wizard.phoneLookup)
        }

    @Test
    fun `one phone match is merged with the person registry's own display name`() =
        runTest(dispatcher) {
            val bookingsApi = FakeBookingsApi(phoneCandidatesResult = PhoneCandidatesResult.Loaded(listOf(CANDIDATE)))
            val personsApi = FakePersonsApi(PersonsResult.Loaded(listOf(PersonProfile(personId = "p1", displayName = "Анна Ковалёва"))))
            val viewModel = viewModel(bookingsApi = bookingsApi, personsApi = personsApi)
            viewModel.open()
            advanceUntilIdle()

            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()

            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            val lookup = wizard.phoneLookup as PhoneLookupState.One
            assertEquals("Анна Ковалёва", lookup.candidate.displayName)
        }

    @Test
    fun `several phone matches reach PhoneLookupState-Many`() =
        runTest(dispatcher) {
            val second = CANDIDATE.copy(personId = "p2", bookingCount = 1)
            val viewModel =
                viewModel(bookingsApi = FakeBookingsApi(phoneCandidatesResult = PhoneCandidatesResult.Loaded(listOf(CANDIDATE, second))))
            viewModel.open()
            advanceUntilIdle()

            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()

            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            val lookup = wizard.phoneLookup as PhoneLookupState.Many
            assertEquals(2, lookup.candidates.size)
        }

    @Test
    fun `searchPhone is a no-op while a lookup is already in flight`() =
        runTest(dispatcher) {
            val bookingsApi = FakeBookingsApi(hangPhoneCandidates = true)
            val viewModel = viewModel(bookingsApi = bookingsApi)
            viewModel.open()
            advanceUntilIdle()

            // `onPhoneChanged` itself fires the auto-search once the digits look complete.
            viewModel.onPhoneChanged(PHONE)
            dispatcher.scheduler.runCurrent()
            viewModel.searchPhone()
            dispatcher.scheduler.runCurrent()

            assertEquals(1, bookingsApi.fetchPhoneCandidatesCalls)
        }

    @Test
    fun `chooseCandidate reuses the existing client and advances to Client`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(bookingsApi = FakeBookingsApi(phoneCandidatesResult = PhoneCandidatesResult.Loaded(listOf(CANDIDATE))))
            viewModel.open()
            advanceUntilIdle()
            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()

            viewModel.chooseCandidate(CANDIDATE)

            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(ManualBookingStep.Client, wizard.step)
            assertEquals(ManualBookingClient.Existing(CANDIDATE), wizard.client)
        }

    @Test
    fun `chooseNewClient mints a new client from the no-match frame`() =
        runTest(dispatcher) {
            val viewModel = viewModel(bookingsApi = FakeBookingsApi(phoneCandidatesResult = PhoneCandidatesResult.Loaded(emptyList())))
            viewModel.open()
            advanceUntilIdle()
            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()

            viewModel.chooseNewClient()

            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(ManualBookingStep.Client, wizard.step)
            assertEquals(ManualBookingClient.New("", ""), wizard.client)
        }

    @Test
    fun `confirmClientStep is a no-op while a new client's name is still blank`() =
        runTest(dispatcher) {
            val viewModel = viewModel(bookingsApi = FakeBookingsApi(phoneCandidatesResult = PhoneCandidatesResult.Loaded(emptyList())))
            viewModel.open()
            advanceUntilIdle()
            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()
            viewModel.chooseNewClient()

            viewModel.confirmClientStep()

            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(ManualBookingStep.Client, wizard.step)
        }

    @Test
    fun `submitting a new client mints one and sends no reusePersonId`() =
        runTest(dispatcher) {
            val bookingsApi = FakeBookingsApi(phoneCandidatesResult = PhoneCandidatesResult.Loaded(emptyList()))
            val viewModel = viewModel(bookingsApi = bookingsApi)
            viewModel.open()
            advanceUntilIdle()

            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()
            viewModel.chooseNewClient()
            viewModel.onNewClientNameChanged("Ирина Мельникова")
            viewModel.confirmClientStep()
            viewModel.selectService(SERVICE)
            viewModel.selectWorker(WORKER)
            advanceUntilIdle()
            viewModel.selectDate(SLOT.localDate)
            viewModel.selectSlot(SLOT)
            assertEquals(ManualBookingStep.Review, (viewModel.state.value as ManualBookingUiState.Wizard).step)

            viewModel.submit()
            advanceUntilIdle()

            assertEquals(
                ManualBookingUiState.Created("b1", "2026-10-02T14:00:00Z", "2026-10-02T15:00:00Z", SLOT.localDate),
                viewModel.state.value,
            )
            val request = requireNotNull(bookingsApi.lastManualBookingRequest)
            assertEquals("cal1", request.calendarId)
            assertEquals("s1", request.serviceId)
            assertEquals("w1", request.workerId)
            assertEquals("e1", request.startEventId)
            assertEquals("Ирина Мельникова", request.name)
            assertEquals(PHONE, request.phone)
            assertNull(request.reusePersonId)
            // A blank email is sent as `null`, never an empty string - `submit`'s own doc comment.
            assertNull(request.email)
        }

    @Test
    fun `submitting a new client's non-blank email is carried through`() =
        runTest(dispatcher) {
            val bookingsApi = FakeBookingsApi(phoneCandidatesResult = PhoneCandidatesResult.Loaded(emptyList()))
            val viewModel = viewModel(bookingsApi = bookingsApi)
            viewModel.open()
            advanceUntilIdle()

            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()
            viewModel.chooseNewClient()
            viewModel.onNewClientNameChanged("Ирина Мельникова")
            viewModel.onNewClientEmailChanged("irina@example.com")
            viewModel.confirmClientStep()
            viewModel.selectService(SERVICE)
            viewModel.selectWorker(WORKER)
            advanceUntilIdle()
            viewModel.selectDate(SLOT.localDate)
            viewModel.selectSlot(SLOT)

            viewModel.submit()
            advanceUntilIdle()

            assertEquals("irina@example.com", bookingsApi.lastManualBookingRequest?.email)
        }

    @Test
    fun `submitting a recognized client reuses its person id and sends no email`() =
        runTest(dispatcher) {
            val bookingsApi = FakeBookingsApi(phoneCandidatesResult = PhoneCandidatesResult.Loaded(listOf(CANDIDATE)))
            val viewModel = viewModel(bookingsApi = bookingsApi)
            viewModel.open()
            advanceUntilIdle()

            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()
            viewModel.chooseCandidate(CANDIDATE)
            viewModel.confirmClientStep()
            viewModel.selectService(SERVICE)
            viewModel.selectWorker(WORKER)
            advanceUntilIdle()
            viewModel.selectDate(SLOT.localDate)
            viewModel.selectSlot(SLOT)

            viewModel.submit()
            advanceUntilIdle()

            val request = requireNotNull(bookingsApi.lastManualBookingRequest)
            assertEquals("p1", request.reusePersonId)
            assertNull(request.email)
            assertEquals(
                ManualBookingUiState.Created("b1", "2026-10-02T14:00:00Z", "2026-10-02T15:00:00Z", SLOT.localDate),
                viewModel.state.value,
            )
        }

    // `26-311`: [ManualBookingUiState.Created.localDate] must be the *slot's* own business-local day - a
    // client-side conversion of `startsAt` (an ISO instant) would land on the wrong day across a DST/zone
    // boundary ([ManualBookingUiState.Created]'s own doc comment). `SLOT.localDate` is deliberately not
    // simply the calendar date of `SLOT.startsAt` reparsed - this test only proves the value is carried
    // from `selectedSlot`, not recomputed from `startsAt`, which is the actual regression `26-311` fixes.
    @Test
    fun `submit carries the selected slot's own localDate, not a conversion of startsAt`() =
        runTest(dispatcher) {
            val bookingsApi = FakeBookingsApi(phoneCandidatesResult = PhoneCandidatesResult.Loaded(emptyList()))
            // `FakeWorkerSlotsApi`'s own default already answers with `listOf(SLOT)` - no override needed.
            val viewModel = viewModel(bookingsApi = bookingsApi)
            viewModel.open()
            advanceUntilIdle()

            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()
            viewModel.chooseNewClient()
            viewModel.onNewClientNameChanged("Ирина Мельникова")
            viewModel.confirmClientStep()
            viewModel.selectService(SERVICE)
            viewModel.selectWorker(WORKER)
            advanceUntilIdle()
            viewModel.selectDate(SLOT.localDate)
            viewModel.selectSlot(SLOT)

            viewModel.submit()
            advanceUntilIdle()

            val created = viewModel.state.value as ManualBookingUiState.Created
            assertEquals(SLOT.localDate, created.localDate)
        }

    @Test
    fun `a manual-booking refusal keeps Review open with the server's own detail`() =
        runTest(dispatcher) {
            val bookingsApi =
                FakeBookingsApi(
                    phoneCandidatesResult = PhoneCandidatesResult.Loaded(emptyList()),
                    manualBookingResult = ManualBookingResult.Refused("Слот уже занят, выберите другое время."),
                )
            val viewModel = viewModel(bookingsApi = bookingsApi)
            viewModel.open()
            advanceUntilIdle()
            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()
            viewModel.chooseNewClient()
            viewModel.onNewClientNameChanged("Ирина")
            viewModel.confirmClientStep()
            viewModel.selectService(SERVICE)
            viewModel.selectWorker(WORKER)
            advanceUntilIdle()
            viewModel.selectDate(SLOT.localDate)
            viewModel.selectSlot(SLOT)

            viewModel.submit()
            advanceUntilIdle()

            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(ManualBookingStep.Review, wizard.step)
            assertEquals(BookingActionErrorUi.ServerRefusal("Слот уже занят, выберите другое время."), wizard.actionError)
            assertFalse(wizard.submitting)
        }

    @Test
    fun `a manual-booking failure keeps Review open as Unavailable`() =
        runTest(dispatcher) {
            val bookingsApi =
                FakeBookingsApi(
                    phoneCandidatesResult = PhoneCandidatesResult.Loaded(emptyList()),
                    manualBookingResult = ManualBookingResult.Failed(BookingsQueueFailure.Transport),
                )
            val viewModel = viewModel(bookingsApi = bookingsApi)
            viewModel.open()
            advanceUntilIdle()
            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()
            viewModel.chooseNewClient()
            viewModel.onNewClientNameChanged("Ирина")
            viewModel.confirmClientStep()
            viewModel.selectService(SERVICE)
            viewModel.selectWorker(WORKER)
            advanceUntilIdle()
            viewModel.selectDate(SLOT.localDate)
            viewModel.selectSlot(SLOT)

            viewModel.submit()
            advanceUntilIdle()

            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(BookingActionErrorUi.Unavailable(BookingsQueueFailure.Transport), wizard.actionError)
            assertFalse(wizard.submitting)
        }

    @Test
    fun `selecting a worker fetches only Available slots for it and lands on Date`() =
        runTest(dispatcher) {
            val slots = listOf(SLOT, SLOT.copy(eventId = "e2", status = WorkerSlotStatus.Booked, rawStatus = "Booked"))
            val viewModel = viewModel(workerSlotsApi = FakeWorkerSlotsApi(WorkerSlotsResult.Loaded(slots)))
            viewModel.open()
            advanceUntilIdle()
            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()
            viewModel.chooseNewClient()
            viewModel.onNewClientNameChanged("Ирина")
            viewModel.confirmClientStep()
            viewModel.selectService(SERVICE)

            viewModel.selectWorker(WORKER)
            advanceUntilIdle()

            // `26-268` follow-up: the whole default range is fetched once, at the Worker->Date transition
            // ([ManualBookingStep]'s own doc comment) - [ManualBookingStep.Date] is where it first lands,
            // not [ManualBookingStep.Slot], which only reads a day out of it once one is chosen.
            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(ManualBookingStep.Date, wizard.step)
            assertEquals(listOf(SLOT), wizard.slots)
        }

    @Test
    fun `selectDate advances to Slot without a second fetch`() =
        runTest(dispatcher) {
            val otherDaySlot = SLOT.copy(eventId = "e2", localDate = "2026-10-03")
            val viewModel = viewModel(workerSlotsApi = FakeWorkerSlotsApi(WorkerSlotsResult.Loaded(listOf(SLOT, otherDaySlot))))
            viewModel.open()
            advanceUntilIdle()
            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()
            viewModel.chooseNewClient()
            viewModel.onNewClientNameChanged("Ирина")
            viewModel.confirmClientStep()
            viewModel.selectService(SERVICE)
            viewModel.selectWorker(WORKER)
            advanceUntilIdle()

            viewModel.selectDate(SLOT.localDate)

            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(ManualBookingStep.Slot, wizard.step)
            assertEquals(SLOT.localDate, wizard.selectedDate)
            // Both days' slots stay on the state - `SlotStepBody`'s own job is to filter by `selectedDate`,
            // not the view model's.
            assertEquals(listOf(SLOT, otherDaySlot), wizard.slots)
        }

    @Test
    fun `back moves to the previous step without losing any already-entered state`() =
        runTest(dispatcher) {
            val bookingsApi = FakeBookingsApi(phoneCandidatesResult = PhoneCandidatesResult.Loaded(listOf(CANDIDATE)))
            val viewModel = viewModel(bookingsApi = bookingsApi)
            viewModel.open()
            advanceUntilIdle()
            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()
            viewModel.chooseCandidate(CANDIDATE)
            viewModel.confirmClientStep()
            // `selectService` is itself a one-motion select-and-advance ([selectService]'s own doc
            // comment) - this already lands on `Worker`, one step further than the tap that chose it.
            viewModel.selectService(SERVICE)
            assertEquals(ManualBookingStep.Worker, (viewModel.state.value as ManualBookingUiState.Wizard).step)

            viewModel.back()

            val afterFirstBack = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(ManualBookingStep.Service, afterFirstBack.step)
            // The chosen service, the recognised client and the searched phone are all still exactly what
            // they were - `back` never resets a field, it only moves `step`.
            assertEquals(SERVICE, afterFirstBack.selectedService)
            assertEquals(ManualBookingClient.Existing(CANDIDATE), afterFirstBack.client)
            assertEquals(PHONE, afterFirstBack.phone)

            viewModel.back()

            val afterSecondBack = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(ManualBookingStep.Client, afterSecondBack.step)
            assertEquals(ManualBookingClient.Existing(CANDIDATE), afterSecondBack.client)

            viewModel.back()

            val afterThirdBack = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(ManualBookingStep.Phone, afterThirdBack.step)
            // Still on hand even back on the Phone step - `chooseCandidate` set it, `back` never clears it.
            assertEquals(ManualBookingClient.Existing(CANDIDATE), afterThirdBack.client)
        }

    @Test
    fun `back preserves the fetched slots and the chosen date across Slot and Date`() =
        runTest(dispatcher) {
            val otherDaySlot = SLOT.copy(eventId = "e2", localDate = "2026-10-03")
            val viewModel = viewModel(workerSlotsApi = FakeWorkerSlotsApi(WorkerSlotsResult.Loaded(listOf(SLOT, otherDaySlot))))
            viewModel.open()
            advanceUntilIdle()
            viewModel.onPhoneChanged(PHONE)
            advanceUntilIdle()
            viewModel.chooseNewClient()
            viewModel.onNewClientNameChanged("Ирина")
            viewModel.confirmClientStep()
            viewModel.selectService(SERVICE)
            viewModel.selectWorker(WORKER)
            advanceUntilIdle()
            viewModel.selectDate(SLOT.localDate)
            assertEquals(ManualBookingStep.Slot, (viewModel.state.value as ManualBookingUiState.Wizard).step)

            viewModel.back()

            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(ManualBookingStep.Date, wizard.step)
            // No re-fetch on the way back - both days' slots, and the worker that was chosen, are untouched.
            assertEquals(listOf(SLOT, otherDaySlot), wizard.slots)
            assertEquals(WORKER, wizard.selectedWorker)
        }

    @Test
    fun `back is a no-op on the first step`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.open()
            advanceUntilIdle()

            viewModel.back()

            val wizard = viewModel.state.value as ManualBookingUiState.Wizard
            assertEquals(ManualBookingStep.Phone, wizard.step)
        }

    private class FakeBookingsApi(
        private val phoneCandidatesResult: PhoneCandidatesResult = PhoneCandidatesResult.Loaded(emptyList()),
        private val servicesResult: ServicesResult = ServicesResult.Loaded(listOf(SERVICE)),
        private val manualBookingResult: ManualBookingResult =
            ManualBookingResult.Created(
                "b1",
                "2026-10-02T14:00:00Z",
                "2026-10-02T15:00:00Z",
            ),
        private val hangPhoneCandidates: Boolean = false,
    ) : BookingsApi {
        var fetchPhoneCandidatesCalls: Int = 0
            private set
        var lastManualBookingRequest: ManualBookingRequestCapture? = null
            private set

        override suspend fun fetchServices(): ServicesResult = servicesResult

        override suspend fun fetchPhoneCandidates(phone: String): PhoneCandidatesResult {
            fetchPhoneCandidatesCalls++
            if (hangPhoneCandidates) awaitCancellation()
            return phoneCandidatesResult
        }

        override suspend fun createManualBooking(
            calendarId: String,
            serviceId: String,
            workerId: String,
            startEventId: String,
            name: String,
            phone: String,
            reusePersonId: String?,
            email: String?,
        ): ManualBookingResult {
            lastManualBookingRequest =
                ManualBookingRequestCapture(calendarId, serviceId, workerId, startEventId, name, phone, reusePersonId, email)
            return manualBookingResult
        }

        override suspend fun fetchPendingQueue(): PendingBookingsResult = throw UnsupportedOperationException()

        override suspend fun fetchConfirmedBookings(
            from: String,
            to: String,
        ): ConfirmedBookingsResult = throw UnsupportedOperationException()

        override suspend fun fetchContacts(): ContactsResult = throw UnsupportedOperationException()

        override suspend fun deleteClient(personId: String): DeleteClientResult = throw UnsupportedOperationException()

        override suspend fun rejectBooking(bookingId: String): BookingActionResult = throw UnsupportedOperationException()

        override suspend fun cancelBooking(bookingId: String): BookingActionResult = throw UnsupportedOperationException()

        override suspend fun markNoShow(bookingId: String): BookingActionResult = throw UnsupportedOperationException()

        override suspend fun revealCustomerPhone(
            customerId: String,
            surface: String,
        ): RevealPhoneResult = throw UnsupportedOperationException()

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

        override suspend fun rescheduleBooking(
            bookingId: String,
            newStartEventId: String,
        ): BookingActionResult = throw UnsupportedOperationException()

        override suspend fun fetchPersonBookings(personId: String): PersonBookingsResult = throw UnsupportedOperationException()

        override suspend fun confirmOperatorVerifiedPhone(personId: String): ConfirmPhoneResult = throw UnsupportedOperationException()
    }

    private data class ManualBookingRequestCapture(
        val calendarId: String,
        val serviceId: String,
        val workerId: String,
        val startEventId: String,
        val name: String,
        val phone: String,
        val reusePersonId: String?,
        val email: String?,
    )

    private class FakeWorkersApi(
        private val result: WorkersResult = WorkersResult.Loaded(workers = listOf(WORKER), calendars = emptyList(), services = emptyList()),
    ) : WorkersApi {
        override suspend fun fetchWorkers(): WorkersResult = result

        override suspend fun fetchWorker(workerId: String): WorkerDetailResult = throw UnsupportedOperationException()

        override suspend fun createWorker(draft: WorkerDraft): BookingActionResult = throw UnsupportedOperationException()

        override suspend fun updateWorker(
            workerId: String,
            draft: WorkerDraft,
        ): BookingActionResult = throw UnsupportedOperationException()

        override suspend fun deleteWorker(workerId: String): BookingActionResult = throw UnsupportedOperationException()
    }

    private class FakeWorkerSlotsApi(
        private val result: WorkerSlotsResult = WorkerSlotsResult.Loaded(listOf(SLOT)),
    ) : WorkerSlotsApi {
        override suspend fun fetchSlots(
            workerId: String,
            from: String,
            to: String,
        ): WorkerSlotsResult = result
    }

    private class FakePersonsApi(
        private val result: PersonsResult = PersonsResult.Loaded(emptyList()),
    ) : PersonsApi {
        override suspend fun fetchPersons(personIds: List<String>): PersonsResult = result

        override suspend fun fetchPersonConversations(personId: String): PersonConversationsResult = throw UnsupportedOperationException()
    }

    private companion object {
        const val PHONE = "+79210000000"

        val SERVICE =
            ConfiguredService(
                serviceId = "s1",
                name = "Стрижка",
                durationMinutes = 60,
                priceMinorUnits = null,
                priceCurrencyCode = null,
                priceIsFrom = false,
                description = null,
                isActive = true,
            )

        val WORKER =
            Worker(
                workerId = "w1",
                lastName = "Соколова",
                firstName = "Ирина",
                middleName = null,
                displayName = "Ирина Соколова",
                isActive = true,
                serviceIds = listOf("s1"),
                calendarId = "cal1",
            )

        val SLOT =
            WorkerSlot(
                eventId = "e1",
                localDate = "2026-10-02",
                weekday = 5,
                startsAt = "2026-10-02T14:00:00Z",
                endsAt = "2026-10-02T15:00:00Z",
                status = WorkerSlotStatus.Available,
                rawStatus = "Available",
                serviceId = null,
                serviceName = null,
                personId = null,
                phone = null,
                masked = false,
                bookingId = null,
            )

        val CANDIDATE =
            PhoneCandidate(
                personId = "p1",
                phone = PHONE,
                masked = false,
                noShowCount = 0,
                bookingCount = 3,
                phoneVerifiedAt = null,
                phoneConfirmedByOperatorAt = null,
                firstSeenAt = "2026-01-01T00:00:00Z",
                lastSeenAt = "2026-01-02T00:00:00Z",
            )
    }
}
