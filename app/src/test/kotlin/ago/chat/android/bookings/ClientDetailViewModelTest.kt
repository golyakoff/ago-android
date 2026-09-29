package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingRevealSurface
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmPhoneResult
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.core.domain.bookings.ContactsResult
import ago.chat.android.core.domain.bookings.DeleteClientResult
import ago.chat.android.core.domain.bookings.ManualBookingResult
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import ago.chat.android.core.domain.bookings.PersonBooking
import ago.chat.android.core.domain.bookings.PersonBookingStatus
import ago.chat.android.core.domain.bookings.PersonBookingsResult
import ago.chat.android.core.domain.bookings.PhoneCandidatesResult
import ago.chat.android.core.domain.bookings.PhoneRevealsResult
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.bookings.ServicesResult
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.persons.PersonConversation
import ago.chat.android.core.domain.persons.PersonConversationsResult
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * `26-269`: the client-detail hub's own view model — the identical `StandardTestDispatcher`/
 * `Dispatchers.setMain` shape every sibling view model test in this package already establishes
 * ([ContactsViewModelTest]'s own doc comment).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClientDetailViewModelTest {
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
            val viewModel = ClientDetailViewModel(FakeBookingsApi(hangFetch = true), FakePersonsApi(), dispatcher)

            viewModel.open(contact())
            dispatcher.scheduler.runCurrent()

            assertEquals(ClientDetailUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a loaded history is split into upcoming and past, and the active conversation is picked`() =
        runTest(dispatcher) {
            val upcoming = booking(id = "b1", startsAt = "2026-12-01T10:00:00Z")
            val past = booking(id = "b2", startsAt = "2026-01-01T10:00:00Z")
            val bookingsApi = FakeBookingsApi(result = PersonBookingsResult.Loaded(listOf(past, upcoming)))
            val personsApi =
                FakePersonsApi(
                    conversationsResult =
                        PersonConversationsResult.Loaded(
                            listOf(conversation(id = "active-conv"), conversation(id = "old-conv")),
                        ),
                )
            val viewModel = ClientDetailViewModel(bookingsApi, personsApi, dispatcher)

            viewModel.open(contact())
            advanceUntilIdle()

            val loaded = viewModel.state.value as ClientDetailUiState.Loaded
            assertEquals(listOf(upcoming), loaded.upcoming)
            assertEquals(listOf(past), loaded.past)
            // `26-269`: `conversations.firstOrNull()` is the one to open - the server's own
            // active-else-most-recent ordering, never re-sorted client-side.
            assertEquals("active-conv", loaded.dialogConversationId)
            assertEquals(true, loaded.hasDialog)
        }

    @Test
    fun `no conversations at all degrades to no dialog, never a failure`() =
        runTest(dispatcher) {
            val bookingsApi = FakeBookingsApi(result = PersonBookingsResult.Loaded(emptyList()))
            val personsApi = FakePersonsApi(conversationsResult = PersonConversationsResult.Loaded(emptyList()))
            val viewModel = ClientDetailViewModel(bookingsApi, personsApi, dispatcher)

            viewModel.open(contact())
            advanceUntilIdle()

            val loaded = viewModel.state.value as ClientDetailUiState.Loaded
            assertNull(loaded.dialogConversationId)
            assertEquals(false, loaded.hasDialog)
        }

    @Test
    fun `a failed conversations read degrades to no dialog, and never fails the whole hub`() =
        runTest(dispatcher) {
            val bookingsApi = FakeBookingsApi(result = PersonBookingsResult.Loaded(emptyList()))
            val personsApi = FakePersonsApi(conversationsResult = PersonConversationsResult.Failed(NetworkFailure.NoConnection))
            val viewModel = ClientDetailViewModel(bookingsApi, personsApi, dispatcher)

            viewModel.open(contact())
            advanceUntilIdle()

            val loaded = viewModel.state.value as ClientDetailUiState.Loaded
            assertNull(loaded.dialogConversationId)
        }

    @Test
    fun `NotConfigured passes straight through`() =
        runTest(dispatcher) {
            val viewModel =
                ClientDetailViewModel(FakeBookingsApi(result = PersonBookingsResult.NotConfigured), FakePersonsApi(), dispatcher)

            viewModel.open(contact())
            advanceUntilIdle()

            assertEquals(ClientDetailUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a failed bookings read fails the whole hub`() =
        runTest(dispatcher) {
            val viewModel =
                ClientDetailViewModel(
                    FakeBookingsApi(result = PersonBookingsResult.Failed(BookingsQueueFailure.Unexpected)),
                    FakePersonsApi(),
                    dispatcher,
                )

            viewModel.open(contact())
            advanceUntilIdle()

            assertEquals(ClientDetailUiState.Failed(BookingsQueueFailure.Unexpected), viewModel.state.value)
        }

    @Test
    fun `retry re-reads for the last opened contact`() =
        runTest(dispatcher) {
            val bookingsApi = FakeBookingsApi(result = PersonBookingsResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = ClientDetailViewModel(bookingsApi, FakePersonsApi(), dispatcher)
            viewModel.open(contact(id = "c1"))
            advanceUntilIdle()

            bookingsApi.result = PersonBookingsResult.Loaded(emptyList())
            viewModel.retry()
            advanceUntilIdle()

            assertEquals(listOf("c1", "c1"), bookingsApi.requestedIds)
            assertEquals(ClientDetailUiState.Loaded(contact("c1"), emptyList(), emptyList(), null), viewModel.state.value)
        }

    @Test
    fun `onSegmentSelected switches which list visibleBookings reads`() =
        runTest(dispatcher) {
            val upcoming = booking(id = "b1", startsAt = "2026-12-01T10:00:00Z")
            val past = booking(id = "b2", startsAt = "2026-01-01T10:00:00Z")
            val bookingsApi = FakeBookingsApi(result = PersonBookingsResult.Loaded(listOf(upcoming, past)))
            val viewModel = ClientDetailViewModel(bookingsApi, FakePersonsApi(), dispatcher)
            viewModel.open(contact())
            advanceUntilIdle()

            assertEquals(listOf(upcoming), (viewModel.state.value as ClientDetailUiState.Loaded).visibleBookings)

            viewModel.onSegmentSelected(ClientDetailSegment.Past)

            assertEquals(listOf(past), (viewModel.state.value as ClientDetailUiState.Loaded).visibleBookings)
        }

    @Test
    fun `revealing unmasks the held contact's own phone`() =
        runTest(dispatcher) {
            val bookingsApi =
                FakeBookingsApi(
                    result = PersonBookingsResult.Loaded(emptyList()),
                    revealResult = RevealPhoneResult.Revealed("+79991234567"),
                )
            val viewModel = ClientDetailViewModel(bookingsApi, FakePersonsApi(), dispatcher)
            viewModel.open(contact())
            advanceUntilIdle()

            viewModel.reveal()
            advanceUntilIdle()

            val loaded = viewModel.state.value as ClientDetailUiState.Loaded
            assertEquals("+79991234567", loaded.contact.phone)
            assertEquals(false, loaded.contact.masked)
            assertEquals(BookingRevealSurface.ANDROID_CLIENT_DETAIL, bookingsApi.lastRevealSurface)
        }

    @Test
    fun `a reveal refusal is shown and the masked value is left alone`() =
        runTest(dispatcher) {
            val bookingsApi =
                FakeBookingsApi(
                    result = PersonBookingsResult.Loaded(emptyList()),
                    revealResult = RevealPhoneResult.Refused("Недостаточно прав."),
                )
            val viewModel = ClientDetailViewModel(bookingsApi, FakePersonsApi(), dispatcher)
            viewModel.open(contact())
            advanceUntilIdle()

            viewModel.reveal()
            advanceUntilIdle()

            val loaded = viewModel.state.value as ClientDetailUiState.Loaded
            assertEquals("+7***5678", loaded.contact.phone)
            assertEquals(BookingActionErrorUi.ServerRefusal("Недостаточно прав."), loaded.actionError)
        }

    @Test
    fun `confirming the phone records the server's own confirmedAt, never a client clock reading`() =
        runTest(dispatcher) {
            val bookingsApi =
                FakeBookingsApi(
                    result = PersonBookingsResult.Loaded(emptyList()),
                    confirmResult = ConfirmPhoneResult.Confirmed("2026-09-29T10:00:00Z"),
                )
            val viewModel = ClientDetailViewModel(bookingsApi, FakePersonsApi(), dispatcher)
            viewModel.open(contact(phoneConfirmedByOperatorAt = null))
            advanceUntilIdle()

            viewModel.confirmPhone()
            advanceUntilIdle()

            val loaded = viewModel.state.value as ClientDetailUiState.Loaded
            assertEquals("2026-09-29T10:00:00Z", loaded.contact.phoneConfirmedByOperatorAt)
            assertEquals(false, loaded.confirmingPhone)
        }

    @Test
    fun `a confirm-phone refusal is shown, and the fact stays unconfirmed`() =
        runTest(dispatcher) {
            val bookingsApi =
                FakeBookingsApi(
                    result = PersonBookingsResult.Loaded(emptyList()),
                    confirmResult = ConfirmPhoneResult.Refused("Недостаточно прав."),
                )
            val viewModel = ClientDetailViewModel(bookingsApi, FakePersonsApi(), dispatcher)
            viewModel.open(contact(phoneConfirmedByOperatorAt = null))
            advanceUntilIdle()

            viewModel.confirmPhone()
            advanceUntilIdle()

            val loaded = viewModel.state.value as ClientDetailUiState.Loaded
            assertNull(loaded.contact.phoneConfirmedByOperatorAt)
            assertEquals(BookingActionErrorUi.ServerRefusal("Недостаточно прав."), loaded.actionError)
        }

    @Test
    fun `cancelling an upcoming booking removes it from the hub on success`() =
        runTest(dispatcher) {
            val upcoming = booking(id = "b1", startsAt = "2026-12-01T10:00:00Z")
            val bookingsApi =
                FakeBookingsApi(
                    result = PersonBookingsResult.Loaded(listOf(upcoming)),
                    cancelResult = BookingActionResult.Succeeded,
                )
            val viewModel = ClientDetailViewModel(bookingsApi, FakePersonsApi(), dispatcher)
            viewModel.open(contact())
            advanceUntilIdle()

            viewModel.cancelBooking("b1")
            advanceUntilIdle()

            assertEquals(listOf("b1"), bookingsApi.cancelCalls)
            val loaded = viewModel.state.value as ClientDetailUiState.Loaded
            assertEquals(emptyList<PersonBooking>(), loaded.upcoming)
            assertNull(loaded.actionError)
        }

    @Test
    fun `cancelling the same booking twice sends exactly one server call`() =
        runTest(dispatcher) {
            val upcoming = booking(id = "b1", startsAt = "2026-12-01T10:00:00Z")
            val bookingsApi =
                FakeBookingsApi(result = PersonBookingsResult.Loaded(listOf(upcoming)), hangCancel = true)
            val viewModel = ClientDetailViewModel(bookingsApi, FakePersonsApi(), dispatcher)
            viewModel.open(contact())
            advanceUntilIdle()

            viewModel.cancelBooking("b1")
            dispatcher.scheduler.runCurrent()
            viewModel.cancelBooking("b1")
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf("b1"), bookingsApi.cancelCalls)
        }

    @Test
    fun `a cancel refusal is shown and the booking stays in upcoming`() =
        runTest(dispatcher) {
            val upcoming = booking(id = "b1", startsAt = "2026-12-01T10:00:00Z")
            val bookingsApi =
                FakeBookingsApi(
                    result = PersonBookingsResult.Loaded(listOf(upcoming)),
                    cancelResult = BookingActionResult.Refused("Слишком поздно для отмены."),
                )
            val viewModel = ClientDetailViewModel(bookingsApi, FakePersonsApi(), dispatcher)
            viewModel.open(contact())
            advanceUntilIdle()

            viewModel.cancelBooking("b1")
            advanceUntilIdle()

            val loaded = viewModel.state.value as ClientDetailUiState.Loaded
            assertEquals(listOf(upcoming), loaded.upcoming)
            assertEquals(BookingActionErrorUi.ServerRefusal("Слишком поздно для отмены."), loaded.actionError)
        }

    @Test
    fun `a cancel transport failure is shown and the booking stays in upcoming`() =
        runTest(dispatcher) {
            val upcoming = booking(id = "b1", startsAt = "2026-12-01T10:00:00Z")
            val bookingsApi =
                FakeBookingsApi(
                    result = PersonBookingsResult.Loaded(listOf(upcoming)),
                    cancelResult = BookingActionResult.Failed(BookingsQueueFailure.Transport),
                )
            val viewModel = ClientDetailViewModel(bookingsApi, FakePersonsApi(), dispatcher)
            viewModel.open(contact())
            advanceUntilIdle()

            viewModel.cancelBooking("b1")
            advanceUntilIdle()

            val loaded = viewModel.state.value as ClientDetailUiState.Loaded
            assertEquals(listOf(upcoming), loaded.upcoming)
            assertEquals(BookingActionErrorUi.Unavailable(BookingsQueueFailure.Transport), loaded.actionError)
        }

    private fun contact(
        id: String = "c1",
        phoneConfirmedByOperatorAt: String? = "2026-09-01T10:00:00Z",
    ) = Contact(
        customerId = id,
        phone = "+7***5678",
        masked = true,
        displayName = "Анна",
        noShowCount = 0,
        phoneVerifiedAt = null,
        phoneConfirmedByOperatorAt = phoneConfirmedByOperatorAt,
    )

    private fun booking(
        id: String,
        startsAt: String,
        status: PersonBookingStatus = PersonBookingStatus.Booked,
    ) = PersonBooking(
        bookingId = id,
        calendarId = "cal1",
        workerId = "w1",
        workerDisplayName = "Ирина",
        serviceId = "s1",
        serviceName = "Стрижка",
        startsAt = startsAt,
        endsAt = startsAt,
        localDate = "2026-09-29",
        weekday = 2,
        phone = "+7***5678",
        masked = true,
        originConversationId = null,
        status = status,
    )

    private fun conversation(id: String) =
        PersonConversation(
            conversationId = id,
            state = "Assigned",
            isActive = true,
            startedAt = "2026-09-01T10:00:00Z",
            closedAt = null,
            lastActivityAt = "2026-09-01T10:05:00Z",
        )

    private class FakeBookingsApi(
        var result: PersonBookingsResult = PersonBookingsResult.Loaded(emptyList()),
        private val hangFetch: Boolean = false,
        var revealResult: RevealPhoneResult = RevealPhoneResult.Revealed("+79991234567"),
        var confirmResult: ConfirmPhoneResult = ConfirmPhoneResult.Confirmed("2026-09-29T10:00:00Z"),
        var cancelResult: BookingActionResult = BookingActionResult.Succeeded,
        private val hangCancel: Boolean = false,
    ) : BookingsApi {
        val requestedIds: MutableList<String> = mutableListOf()
        var lastRevealSurface: String? = null
        val cancelCalls: MutableList<String> = mutableListOf()

        override suspend fun fetchPersonBookings(personId: String): PersonBookingsResult {
            requestedIds.add(personId)
            if (hangFetch) awaitCancellation()
            return result
        }

        override suspend fun confirmOperatorVerifiedPhone(personId: String): ConfirmPhoneResult = confirmResult

        override suspend fun fetchPhoneCandidates(phone: String): PhoneCandidatesResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun createManualBooking(
            calendarId: String,
            serviceId: String,
            workerId: String,
            startEventId: String,
            name: String,
            phone: String,
            reusePersonId: String?,
            email: String?,
        ): ManualBookingResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun revealCustomerPhone(
            customerId: String,
            surface: String,
        ): RevealPhoneResult {
            lastRevealSurface = surface
            return revealResult
        }

        // `ClientDetailViewModel` reads a client's own bookings, reveals, and confirms a phone - never
        // the pending queue, the confirmed range, the customer base, a veto write, or the service
        // dictionary.
        override suspend fun fetchPendingQueue(): PendingBookingsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchConfirmedBookings(
            from: String,
            to: String,
        ): ConfirmedBookingsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchContacts(): ContactsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun rejectBooking(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun cancelBooking(bookingId: String): BookingActionResult {
            cancelCalls.add(bookingId)
            if (hangCancel) awaitCancellation()
            return cancelResult
        }

        override suspend fun markNoShow(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

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

        override suspend fun fetchPhoneReveals(
            before: String?,
            limit: Int?,
        ): PhoneRevealsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun rescheduleBooking(
            bookingId: String,
            newStartEventId: String,
        ): BookingActionResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun deleteClient(personId: String): DeleteClientResult =
            throw UnsupportedOperationException("not used by this class")
    }

    private class FakePersonsApi(
        private val conversationsResult: PersonConversationsResult = PersonConversationsResult.Loaded(emptyList()),
    ) : PersonsApi {
        override suspend fun fetchPersonConversations(personId: String): PersonConversationsResult = conversationsResult

        // `ClientDetailViewModel` never reads the person-name batch - it is handed an already-merged
        // [Contact] by its caller.
        override suspend fun fetchPersons(personIds: List<String>): PersonsResult =
            throw UnsupportedOperationException("not used by this class")
    }
}
