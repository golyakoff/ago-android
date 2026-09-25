package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingRevealSurface
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmedBooking
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.ContactsResult
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import ago.chat.android.core.domain.bookings.PhoneRevealsResult
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
            val viewModel = ConfirmedBookingsViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(ConfirmedBookingsUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a loaded range is grouped by day then worker, and today is selected`() =
        runTest(dispatcher) {
            val monday = booking(id = "b1", localDate = "2026-09-28", weekday = 1, workerId = "w1", workerName = "Ирина Соколова")
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Loaded(listOf(monday)))
            val viewModel = ConfirmedBookingsViewModel(api = api, ioDispatcher = dispatcher)

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
            val viewModel = ConfirmedBookingsViewModel(api = api, ioDispatcher = dispatcher)
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
    fun `NotConfigured passes straight through`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.NotConfigured)
            val viewModel = ConfirmedBookingsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ConfirmedBookingsUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = ConfirmedBookingsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ConfirmedBookingsUiState.Failed(BookingsQueueFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `refresh asks the server again`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ConfirmedBookingsResult.Failed(BookingsQueueFailure.Unexpected))
            val viewModel = ConfirmedBookingsViewModel(api = api, ioDispatcher = dispatcher)
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
            val viewModel = ConfirmedBookingsViewModel(api = api, ioDispatcher = dispatcher)
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
            val viewModel = ConfirmedBookingsViewModel(api = api, ioDispatcher = dispatcher)
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
            val viewModel = ConfirmedBookingsViewModel(api = api, ioDispatcher = dispatcher)
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
            val viewModel = ConfirmedBookingsViewModel(api = api, ioDispatcher = dispatcher)
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
        private val hangFetch: Boolean = false,
        var revealResult: RevealPhoneResult = RevealPhoneResult.Revealed("+79991234567"),
        private val hangReveal: Boolean = false,
    ) : BookingsApi {
        var confirmedFetchCalls: Int = 0
            private set
        val revealCalls: MutableList<Pair<String, String>> = mutableListOf()

        // `26-51`'s own [ConfirmedBookingsViewModel] never calls this - the sibling pending-queue read.
        override suspend fun fetchPendingQueue(): PendingBookingsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchConfirmedBookings(
            from: String,
            to: String,
        ): ConfirmedBookingsResult {
            confirmedFetchCalls++
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
    }
}
