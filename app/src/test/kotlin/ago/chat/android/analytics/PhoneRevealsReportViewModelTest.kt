package ago.chat.android.analytics

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.ContactsResult
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import ago.chat.android.core.domain.bookings.PhoneReveal
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
import org.junit.Before
import org.junit.Test

/**
 * `26-74`: the whole state machine for «Показы телефонов» — the first-page load, [PhoneRevealsReportUiState]'s
 * four arms, and keyset paging via [PhoneRevealsReportViewModel.loadMore]. The identical
 * `StandardTestDispatcher`/`Dispatchers.setMain` shape [ago.chat.android.bookings.ContactsViewModelTest]
 * already establishes for a different read on this same [BookingsApi] port.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneRevealsReportViewModelTest {
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
            val viewModel = PhoneRevealsReportViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(PhoneRevealsReportUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `asks for the first page on construction alone, with no cursor`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PhoneRevealsResult.Loaded(listOf(reveal("r1")), nextBefore = null))
            PhoneRevealsReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(1, api.fetchCalls.size)
            assertEquals(null to null, api.fetchCalls.single())
        }

    @Test
    fun `a loaded page passes straight through, unedited`() =
        runTest(dispatcher) {
            val page = listOf(reveal("r2"), reveal("r1"))
            val api = FakeBookingsApi(result = PhoneRevealsResult.Loaded(page, nextBefore = "r1"))
            val viewModel = PhoneRevealsReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(PhoneRevealsReportUiState.Loaded(reveals = page, nextBefore = "r1"), viewModel.state.value)
        }

    @Test
    fun `NotConfigured passes straight through`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PhoneRevealsResult.NotConfigured)
            val viewModel = PhoneRevealsReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(PhoneRevealsReportUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PhoneRevealsResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = PhoneRevealsReportViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(PhoneRevealsReportUiState.Failed(BookingsQueueFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `refresh asks for the first page again, discarding whatever cursor was already in hand`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PhoneRevealsResult.Loaded(listOf(reveal("r1")), nextBefore = "r0"))
            val viewModel = PhoneRevealsReportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            api.result = PhoneRevealsResult.Loaded(listOf(reveal("r2")), nextBefore = null)
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(listOf(null to null, null to null), api.fetchCalls)
            assertEquals(PhoneRevealsReportUiState.Loaded(listOf(reveal("r2")), nextBefore = null), viewModel.state.value)
        }

    @Test
    fun `load more is a no-op once the cursor is exhausted`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PhoneRevealsResult.Loaded(listOf(reveal("r1")), nextBefore = null))
            val viewModel = PhoneRevealsReportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(1, api.fetchCalls.size)
        }

    @Test
    fun `load more sends the current cursor and appends the next page to the end of the list`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PhoneRevealsResult.Loaded(listOf(reveal("r2")), nextBefore = "r2"))
            val viewModel = PhoneRevealsReportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            api.result = PhoneRevealsResult.Loaded(listOf(reveal("r1")), nextBefore = null)
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(listOf(null to null, "r2" to null), api.fetchCalls)
            assertEquals(
                PhoneRevealsReportUiState.Loaded(listOf(reveal("r2"), reveal("r1")), nextBefore = null),
                viewModel.state.value,
            )
        }

    @Test
    fun `load more marks loadingMore immediately, before the server answers`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PhoneRevealsResult.Loaded(listOf(reveal("r2")), nextBefore = "r2"), hangFetchMore = true)
            val viewModel = PhoneRevealsReportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.loadMore()
            dispatcher.scheduler.runCurrent()

            assertEquals(
                PhoneRevealsReportUiState.Loaded(listOf(reveal("r2")), nextBefore = "r2", loadingMore = true),
                viewModel.state.value,
            )
        }

    @Test
    fun `calling load more twice while the first page is in flight sends exactly one request`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PhoneRevealsResult.Loaded(listOf(reveal("r2")), nextBefore = "r2"), hangFetchMore = true)
            val viewModel = PhoneRevealsReportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            viewModel.loadMore()
            dispatcher.scheduler.runCurrent()
            viewModel.loadMore()
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.fetchCalls.count { it.first == "r2" })
        }

    @Test
    fun `a failed load more keeps the rows already on screen and reports its own reason`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PhoneRevealsResult.Loaded(listOf(reveal("r2")), nextBefore = "r2"))
            val viewModel = PhoneRevealsReportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()

            api.result = PhoneRevealsResult.Failed(BookingsQueueFailure.Transport)
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(
                PhoneRevealsReportUiState.Loaded(
                    listOf(reveal("r2")),
                    nextBefore = "r2",
                    loadMoreFailed = BookingsQueueFailure.Transport,
                ),
                viewModel.state.value,
            )
        }

    @Test
    fun `refreshing while a load more is still in flight discards the stale page once it lands`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PhoneRevealsResult.Loaded(listOf(reveal("r2")), nextBefore = "r2"), hangFetchMore = true)
            val viewModel = PhoneRevealsReportViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            viewModel.loadMore()
            dispatcher.scheduler.runCurrent()

            api.hangFetchMore = false
            api.result = PhoneRevealsResult.NotConfigured
            viewModel.refresh()
            advanceUntilIdle()

            // The stale "load more" call is still hung, but by the time this test ends the visible state
            // must be whatever `refresh` produced - never the earlier page's own paging fields spliced
            // onto it.
            assertEquals(PhoneRevealsReportUiState.NotConfigured, viewModel.state.value)
        }

    private fun reveal(id: String) =
        PhoneReveal(
            id = id,
            occurredAt = "2026-09-24T12:00:00Z",
            customerId = "c1",
            operatorId = "op1",
            surface = "AndroidContacts",
        )

    private class FakeBookingsApi(
        var result: PhoneRevealsResult = PhoneRevealsResult.NotConfigured,
        private val hangFetch: Boolean = false,
        var hangFetchMore: Boolean = false,
    ) : BookingsApi {
        /** One entry per [fetchPhoneReveals] call, as `before to limit` - `null to null` for the first
         * page this view model always asks for. */
        val fetchCalls: MutableList<Pair<String?, Int?>> = mutableListOf()

        override suspend fun fetchPhoneReveals(
            before: String?,
            limit: Int?,
        ): PhoneRevealsResult {
            fetchCalls.add(before to limit)
            if (before == null) {
                if (hangFetch) awaitCancellation()
            } else {
                if (hangFetchMore) awaitCancellation()
            }
            return result
        }

        // `PhoneRevealsReportViewModel` reads this one method alone - every other read and write on this
        // port belongs to a sibling screen's own view model.
        override suspend fun fetchPendingQueue(): PendingBookingsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchConfirmedBookings(
            from: String,
            to: String,
        ): ConfirmedBookingsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchContacts(): ContactsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun rejectBooking(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun cancelBooking(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun markNoShow(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun revealCustomerPhone(
            customerId: String,
            surface: String,
        ): RevealPhoneResult = throw UnsupportedOperationException("not used by this class")

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
