package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.core.domain.bookings.ContactsResult
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
 * `26-52`: the plain one-shot read — the identical `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * [BookingsViewModelTest]/[ConfirmedBookingsViewModelTest] already establish for their own sibling view
 * models.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {
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
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(ContactsUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a loaded list passes straight through, unedited`() =
        runTest(dispatcher) {
            val contact =
                Contact(
                    customerId = "c1",
                    phone = "+7***5678",
                    masked = true,
                    displayName = "Анна",
                    noShowCount = 3,
                    phoneVerifiedAt = "2026-09-01T10:00:00Z",
                    phoneConfirmedByOperatorAt = null,
                )
            val api = FakeBookingsApi(result = ContactsResult.Loaded(listOf(contact)))
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ContactsUiState.Loaded(listOf(contact)), viewModel.state.value)
        }

    @Test
    fun `NotConfigured passes straight through`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ContactsResult.NotConfigured)
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ContactsUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ContactsResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ContactsUiState.Failed(BookingsQueueFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `refresh asks the server again`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = ContactsResult.Failed(BookingsQueueFailure.Unexpected))
            val viewModel = ContactsViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.contactsFetchCalls)

            api.result = ContactsResult.Loaded(emptyList())
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(2, api.contactsFetchCalls)
            assertEquals(ContactsUiState.Loaded(emptyList()), viewModel.state.value)
        }

    private class FakeBookingsApi(
        var result: ContactsResult = ContactsResult.NotConfigured,
        private val hangFetch: Boolean = false,
    ) : BookingsApi {
        var contactsFetchCalls: Int = 0
            private set

        // `26-52`'s own [ContactsViewModel] never calls either sibling read.
        override suspend fun fetchPendingQueue(): PendingBookingsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchConfirmedBookings(
            from: String,
            to: String,
        ): ConfirmedBookingsResult = throw UnsupportedOperationException("not used by this class")

        override suspend fun fetchContacts(): ContactsResult {
            contactsFetchCalls++
            if (hangFetch) awaitCancellation()
            return result
        }

        // `26-49` widened `BookingsApi` with three veto-write methods this class has no test of its own
        // for - `ContactsViewModel` reads Клиенты, never writes to Ожидают.
        override suspend fun rejectBooking(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun cancelBooking(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")

        override suspend fun markNoShow(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("not used by this class")
    }
}
