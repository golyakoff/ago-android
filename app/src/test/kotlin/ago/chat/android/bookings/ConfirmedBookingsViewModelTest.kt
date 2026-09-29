package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingRevealSurface
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmPhoneResult
import ago.chat.android.core.domain.bookings.ConfirmedBooking
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.ContactsResult
import ago.chat.android.core.domain.bookings.ManualBookingResult
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import ago.chat.android.core.domain.bookings.PersonBookingsResult
import ago.chat.android.core.domain.bookings.PhoneCandidatesResult
import ago.chat.android.core.domain.bookings.PhoneRevealsResult
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.bookings.ServicesResult
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.persons.PersonConversationsResult
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * `26-51`: the range read, grouped once and sliced per selected day — the identical
 * `StandardTestDispatcher`/`Dispatchers.setMain` shape [BookingsViewModelTest] already establishes for
 * the sibling view model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmedBookingsViewModelTest {
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
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(ConfirmedBookingsUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a loaded range is grouped by day then worker, and today is selected`() =
        runTest(dispatcher) {
            val monday = booking(id = "b1", localDate = "2026-09-28", weekday = 1, workerId = "w1", workerName = "Ирина Соколова")
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(listOf(monday)))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)

            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state is ConfirmedBookingsUiState.Loaded)
            state as ConfirmedBookingsUiState.Loaded
            // The range read starts from `LocalDate.now(ZoneOffset.UTC)` - today's own UTC date is what
            // `selectedDate` lands on until the strip is clicked.
            assertEquals(state.strip.first().date, state.selectedDate)
            assertEquals(1, state.days.size)
            assertEquals("2026-09-28", state.days.single().localDate)
        }

    @Test
    fun `selecting a day changes selectedDate without a second network call`() =
        runTest(dispatcher) {
            val monday = booking(id = "b1", localDate = "2026-09-28", weekday = 1, workerId = "w1", workerName = "Ирина Соколова")
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(listOf(monday)))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.confirmedFetchCalls)

            viewModel.onDaySelected("2026-09-28")

            val state = viewModel.state.value as ConfirmedBookingsUiState.Loaded
            assertEquals("2026-09-28", state.selectedDate)
            assertEquals(
                "Ирина Соколова",
                state.selectedDay
                    ?.workers
                    ?.single()
                    ?.workerDisplayName,
            )
            assertEquals(1, api.confirmedFetchCalls)
        }

    @Test
    fun `26-212 picking a date re-fetches an entirely new range anchored there, not today`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(emptyList()))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.confirmedFetchCalls)

            viewModel.onDatePicked("2026-10-15")
            advanceUntilIdle()

            assertEquals(2, api.confirmedFetchCalls)
            assertEquals("2026-10-15" to "2026-10-21", api.lastFetchedRange)
            val state = viewModel.state.value as ConfirmedBookingsUiState.Loaded
            // The picked day is what the strip now opens on, the identical "selectedDate == range.from"
            // rule the initial today-anchored load already follows.
            assertEquals("2026-10-15", state.selectedDate)
            assertEquals("2026-10-15", state.strip.first().date)
        }

    @Test
    fun `26-212 a retry after a jump re-reads the picked window, not today`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(emptyList()))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()
            viewModel.onDatePicked("2026-10-15")
            advanceUntilIdle()

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(3, api.confirmedFetchCalls)
            assertEquals("2026-10-15" to "2026-10-21", api.lastFetchedRange)
        }

    @Test
    fun `26-212 a malformed picked date is ignored, no crash, no re-fetch`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(emptyList()))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.confirmedFetchCalls)

            viewModel.onDatePicked("not-a-date")
            advanceUntilIdle()

            assertEquals(1, api.confirmedFetchCalls)
        }

    @Test
    fun `26-233 pulling to load the next week re-anchors past the current window and snaps to its first day`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(emptyList()))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()
            val today = (viewModel.state.value as ConfirmedBookingsUiState.Loaded).selectedDate
            assertEquals(1, api.confirmedFetchCalls)

            viewModel.onPullToLoadWeek(DateStripEdgeLoad.Next)
            advanceUntilIdle()

            assertEquals(2, api.confirmedFetchCalls)
            val state = viewModel.state.value as ConfirmedBookingsUiState.Loaded
            val expectedAnchor = LocalDate.parse(today).plusDays(7).toString()
            assertEquals(expectedAnchor, state.selectedDate)
            assertEquals(expectedAnchor, state.strip.first().date)
            assertEquals(null, state.edgeLoading)
        }

    @Test
    fun `26-233 pulling to load the previous week re-anchors before the current window and snaps to its first day`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(emptyList()))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()
            val today = (viewModel.state.value as ConfirmedBookingsUiState.Loaded).selectedDate

            viewModel.onPullToLoadWeek(DateStripEdgeLoad.Previous)
            advanceUntilIdle()

            assertEquals(2, api.confirmedFetchCalls)
            val state = viewModel.state.value as ConfirmedBookingsUiState.Loaded
            val expectedAnchor = LocalDate.parse(today).minusDays(7).toString()
            assertEquals(expectedAnchor, state.selectedDate)
            assertEquals(expectedAnchor, state.strip.first().date)
        }

    @Test
    fun `26-233 a pull marks the strip's own edgeLoading before the week answers back`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(emptyList()))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()

            api.hangFetch = true
            viewModel.onPullToLoadWeek(DateStripEdgeLoad.Previous)
            dispatcher.scheduler.runCurrent()

            val state = viewModel.state.value
            assertTrue(state is ConfirmedBookingsUiState.Loaded)
            assertEquals(DateStripEdgeLoad.Previous, (state as ConfirmedBookingsUiState.Loaded).edgeLoading)
        }

    @Test
    fun `26-233 a pull is ignored while another edge load is already in flight`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(emptyList()))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.confirmedFetchCalls)

            api.hangFetch = true
            viewModel.onPullToLoadWeek(DateStripEdgeLoad.Previous)
            dispatcher.scheduler.runCurrent()
            // A pull on the far edge, before the first one has answered - still a no-op, since only one
            // edge can be loading at a time.
            viewModel.onPullToLoadWeek(DateStripEdgeLoad.Next)
            dispatcher.scheduler.runCurrent()

            assertEquals(2, api.confirmedFetchCalls)
        }

    @Test
    fun `26-233 a failed pull clears edgeLoading, surfaces the failure, and leaves the window unmoved`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(emptyList()))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()
            val loadedBefore = viewModel.state.value as ConfirmedBookingsUiState.Loaded

            api.result = ConfirmedBookingsResult.Failed(BookingsQueueFailure.Transport)
            viewModel.onPullToLoadWeek(DateStripEdgeLoad.Next)
            advanceUntilIdle()

            val state = viewModel.state.value as ConfirmedBookingsUiState.Loaded
            assertEquals(null, state.edgeLoading)
            assertEquals(BookingActionErrorUi.Unavailable(BookingsQueueFailure.Transport), state.actionError)
            // The window itself never moved - the anchor only advances once a fetch for the requested
            // week actually succeeds ([ConfirmedBookingsViewModel.onPullToLoadWeek]'s own doc comment).
            assertEquals(loadedBefore.selectedDate, state.selectedDate)
            assertEquals(loadedBefore.strip, state.strip)
        }

    @Test
    fun `26-233 retrying after a failed pull asks for the identical week again, not a week further`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(emptyList()))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()

            api.result = ConfirmedBookingsResult.Failed(BookingsQueueFailure.Transport)
            viewModel.onPullToLoadWeek(DateStripEdgeLoad.Next)
            advanceUntilIdle()
            val failedRange = api.lastFetchedRange

            api.result = ConfirmedBookingsResult.Loaded(emptyList())
            viewModel.onPullToLoadWeek(DateStripEdgeLoad.Next)
            advanceUntilIdle()

            assertEquals(failedRange, api.lastFetchedRange)
            val state = viewModel.state.value as ConfirmedBookingsUiState.Loaded
            assertEquals(failedRange?.first, state.selectedDate)
        }

    @Test
    fun `26-233 today jumps the anchor back to today's own UTC date and reloads`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(emptyList()))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()
            val today = (viewModel.state.value as ConfirmedBookingsUiState.Loaded).selectedDate

            viewModel.onDatePicked("2026-10-15")
            advanceUntilIdle()
            assertEquals("2026-10-15", (viewModel.state.value as ConfirmedBookingsUiState.Loaded).selectedDate)

            viewModel.jumpToToday()
            advanceUntilIdle()

            val state = viewModel.state.value as ConfirmedBookingsUiState.Loaded
            assertEquals(today, state.selectedDate)
            assertEquals(today, state.strip.first().date)
        }

    @Test
    fun `NotConfigured passes straight through`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.NotConfigured)
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ConfirmedBookingsUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ConfirmedBookingsUiState.Failed(BookingsQueueFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `refresh asks the server again`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Failed(BookingsQueueFailure.Unexpected))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.confirmedFetchCalls)

            api.result = ConfirmedBookingsResult.Loaded(emptyList())
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(2, api.confirmedFetchCalls)
            assertTrue(viewModel.state.value is ConfirmedBookingsUiState.Loaded)
        }

    @Test
    fun `revealing a customer marks it, immediately, before the server answers`() =
        runTest(dispatcher) {
            val monday = booking(id = "b1", localDate = "2026-09-28", weekday = 1, workerId = "w1", workerName = "Ирина Соколова")
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(listOf(monday)), hangReveal = true)
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.reveal(monday.customerId)

            val state = viewModel.state.value as ConfirmedBookingsUiState.Loaded
            assertEquals(setOf(monday.customerId), state.revealingCustomerIds)
        }

    @Test
    fun `revealing the same customer twice sends exactly one server call`() =
        runTest(dispatcher) {
            val monday = booking(id = "b1", localDate = "2026-09-28", weekday = 1, workerId = "w1", workerName = "Ирина Соколова")
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(listOf(monday)), hangReveal = true)
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.reveal(monday.customerId)
            dispatcher.scheduler.runCurrent()
            viewModel.reveal(monday.customerId)
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.revealCalls.size)
        }

    @Test
    fun `a successful reveal unmasks every row across every day and worker sharing that customer id`() =
        runTest(dispatcher) {
            // Two bookings for the identical customer, on two different days with two different masters -
            // the shape `ConfirmedBookingsViewModel.replacePhone` has to walk both nesting levels for.
            val monday =
                booking(id = "b1", localDate = "2026-09-28", weekday = 1, workerId = "w1", workerName = "Ирина Соколова", customerId = "c1")
            val tuesday =
                booking(id = "b2", localDate = "2026-09-29", weekday = 2, workerId = "w2", workerName = "Пётр Иванов", customerId = "c1")
            val api =
                FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(listOf(monday, tuesday))).apply {
                    revealResult = RevealPhoneResult.Revealed("+79991234567")
                }
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.reveal("c1")
            advanceUntilIdle()

            assertEquals(listOf("c1" to BookingRevealSurface.ANDROID_BOOKINGS), api.revealCalls)
            val state = viewModel.state.value as ConfirmedBookingsUiState.Loaded
            val allRows = state.days.flatMap { it.workers }.flatMap { it.rows }
            assertEquals(2, allRows.size)
            allRows.forEach { row ->
                assertEquals("+79991234567", row.phone)
                assertEquals(false, row.masked)
            }
            assertEquals(emptySet<String>(), state.revealingCustomerIds)
        }

    @Test
    fun `a refusal leaves the masked value in place and shows the server's own detail`() =
        runTest(dispatcher) {
            val monday = booking(id = "b1", localDate = "2026-09-28", weekday = 1, workerId = "w1", workerName = "Ирина Соколова")
            val api =
                FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(listOf(monday))).apply {
                    revealResult = RevealPhoneResult.Refused("Недостаточно прав для просмотра номера.")
                }
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = FakePersonsApi(), ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.reveal(monday.customerId)
            advanceUntilIdle()

            val state = viewModel.state.value as ConfirmedBookingsUiState.Loaded
            assertEquals(
                BookingActionErrorUi.ServerRefusal("Недостаточно прав для просмотра номера."),
                state.actionError,
            )
            // The masked value is untouched - a refusal never guesses at an unmasked number.
            val row =
                state.days
                    .single()
                    .workers
                    .single()
                    .rows
                    .single()
            assertEquals(monday.phone, row.phone)
        }

    @Test
    fun `26-162 a name chat's person registry answers with is merged onto the matching booking`() =
        runTest(dispatcher) {
            val monday =
                booking(id = "b1", localDate = "2026-09-28", weekday = 1, workerId = "w1", workerName = "Ирина Соколова", customerId = "c1")
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(listOf(monday)))
            val persons = FakePersonsApi(result = PersonsResult.Loaded(listOf(PersonProfile(personId = "c1", displayName = "Анна"))))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = persons, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(listOf("c1"), persons.requestedIds)
            val state = viewModel.state.value as ConfirmedBookingsUiState.Loaded
            val row =
                state.days
                    .single()
                    .workers
                    .single()
                    .rows
                    .single()
            assertEquals("Анна", row.customerDisplayName)
        }

    @Test
    fun `26-162 an unreachable person registry leaves the booking as it was, never fails the screen`() =
        runTest(dispatcher) {
            val monday = booking(id = "b1", localDate = "2026-09-28", weekday = 1, workerId = "w1", workerName = "Ирина Соколова")
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(listOf(monday)))
            val persons = FakePersonsApi(result = PersonsResult.Failed(NetworkFailure.NoConnection))
            val viewModel = ConfirmedBookingsViewModel(api = api, personsApi = persons, ioDispatcher = dispatcher)

            advanceUntilIdle()

            val state = viewModel.state.value as ConfirmedBookingsUiState.Loaded
            val row =
                state.days
                    .single()
                    .workers
                    .single()
                    .rows
                    .single()
            assertEquals(null, row.customerDisplayName)
        }

    private fun booking(
        id: String,
        localDate: String,
        weekday: Int,
        workerId: String,
        workerName: String,
        customerId: String = "customer-$id",
    ) = ConfirmedBooking(
        bookingId = id,
        calendarId = "calendar-1",
        workerId = workerId,
        workerDisplayName = workerName,
        serviceId = "service-1",
        serviceName = "Стрижка",
        customerId = customerId,
        customerDisplayName = null,
        startsAt = "${localDate}T09:00:00Z",
        endsAt = "${localDate}T09:30:00Z",
        localDate = localDate,
        weekday = weekday,
        phone = "+7***5678",
        masked = true,
    )

    private class FakeBookingsApi(
        var result: ConfirmedBookingsResult = ConfirmedBookingsResult.NotConfigured,
        // `26-233`: a `var`, not the original `val` - the edge-pull tests need a fetch to hang only from a
        // point *after* the initial load already answered (toggled mid-test), unlike every earlier test's
        // own constructor-time-only need.
        var hangFetch: Boolean = false,
        var revealResult: RevealPhoneResult = RevealPhoneResult.Revealed("+79991234567"),
        private val hangReveal: Boolean = false,
    ) : BookingsApi {
        var confirmedFetchCalls: Int = 0
            private set

        // `26-212`: the `from`/`to` of the most recent fetch - what proves a date-picker jump
        // (`onDatePicked`) actually asked the server for the *picked* window, not just re-asked for
        // today's.
        var lastFetchedRange: Pair<String, String>? = null
            private set
        val revealCalls: MutableList<Pair<String, String>> = mutableListOf()

        // `26-51`'s own [ConfirmedBookingsViewModel] never calls this - the sibling pending-queue read.
        override suspend fun fetchPendingQueue(): PendingBookingsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchConfirmedBookings(
            from: String,
            to: String,
        ): ConfirmedBookingsResult {
            confirmedFetchCalls++
            lastFetchedRange = from to to
            if (hangFetch) awaitCancellation()
            return result
        }

        // `26-52` widened `BookingsApi` with a third method this class has no test of its own for -
        // never called by `ConfirmedBookingsViewModel`, which only ever reads the confirmed range.
        override suspend fun fetchContacts(): ContactsResult = throw UnsupportedOperationException("not used by this class")

        // `26-49` widened `BookingsApi` with three veto-write methods this class has no test of its own
        // for - `ConfirmedBookingsViewModel` reads Утверждены, never writes to Ожидают.
        override suspend fun rejectBooking(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun cancelBooking(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun markNoShow(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        // `26-117`: [ConfirmedBookingsViewModel.reveal]'s own call - the identical fake shape
        // `ContactsViewModelTest`'s own `FakeBookingsApi` already establishes for the sibling screen.
        override suspend fun revealCustomerPhone(
            customerId: String,
            surface: String,
        ): RevealPhoneResult {
            revealCalls.add(customerId to surface)
            if (hangReveal) awaitCancellation()
            return revealResult
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

        // `26-74` widened `BookingsApi` with the reveal audit trail - neither read nor written by this
        // class, which only ever reads the confirmed range.
        override suspend fun fetchPhoneReveals(
            before: String?,
            limit: Int?,
        ): PhoneRevealsResult = throw UnsupportedOperationException("not used by this class")

        // `26-209` widened `BookingsApi` with the operator reschedule write - it is
        // `RescheduleBookingViewModel`'s own call, never `ConfirmedBookingsViewModel`'s, which only ever
        // reads and reveals on the confirmed range (`RescheduleBookingViewModelTest` covers this method).
        override suspend fun rescheduleBooking(
            bookingId: String,
            newStartEventId: String,
        ): BookingActionResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchPersonBookings(personId: String): PersonBookingsResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun confirmOperatorVerifiedPhone(personId: String): ConfirmPhoneResult =
            throw UnsupportedOperationException("not used by this class")

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
    }

    /** `26-162`: the identical fake `ContactsViewModelTest`'s own `FakePersonsApi` already establishes,
     * restated here for the same reason `FakeBookingsApi` above is restated rather than shared - a
     * single canned answer, no server-shaped state to fake. Defaults to an empty [PersonsResult.Loaded]
     * so every pre-existing test above (none of which cares about the merge itself) keeps asserting a
     * `null` [ConfirmedBooking.customerDisplayName], exactly as every fixture already supplies. */
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

        override suspend fun fetchPersonConversations(personId: String): PersonConversationsResult =
            throw UnsupportedOperationException("not used by this class")
    }
}
