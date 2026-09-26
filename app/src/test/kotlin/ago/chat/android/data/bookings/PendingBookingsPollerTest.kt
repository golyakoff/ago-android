package ago.chat.android.data.bookings

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * `26-179`: [PendingBookingsPoller] is the one class that actually calls
 * [BookingsApi.fetchPendingQueue] on a timer - what [ago.chat.android.shell.AppShellViewModel] does with
 * the numbers this produces is `AppShellViewModelTest`'s own job (a plain `FakePendingBookingsCount`
 * stands in for this class there, the identical split `RoomConversationListCacheTest`/
 * `AppShellViewModelTest` already draw for [ago.chat.android.data.conversations.ConversationsUnreadTotal]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingBookingsPollerTest {
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
    fun `observeCount emits null before the first poll answers`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(hangFetch = true)
            val poller = PendingBookingsPoller(api, dispatcher)

            assertNull(poller.observeCount().first())
        }

    @Test
    fun `observeCount emits the loaded queue size once the first poll answers`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PendingBookingsResult.Loaded(listOf(booking("a"), booking("b"))))
            val poller = PendingBookingsPoller(api, dispatcher)

            val values = poller.observeCount().take(2).toList()

            assertEquals(listOf(null, 2), values)
        }

    @Test
    fun `NotConfigured is a stable, genuine zero - not a failure and not left unanswered`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PendingBookingsResult.NotConfigured)
            val poller = PendingBookingsPoller(api, dispatcher)

            val values = poller.observeCount().take(2).toList()

            assertEquals(listOf(null, 0), values)
        }

    @Test
    fun `a failed poll keeps the previous value, never resets to null`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PendingBookingsResult.Loaded(listOf(booking("a"), booking("b"), booking("c"))))
            val poller = PendingBookingsPoller(api, dispatcher)
            val collected = mutableListOf<Int?>()
            val job = launch { poller.observeCount().collect { collected.add(it) } }

            // The first emission (`null`) and the first successful poll's answer (`3`) both happen with
            // no virtual time passing at all - `runCurrent()` is enough to reach them.
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf(null, 3), collected)

            // The second poll, one interval later, is the one this test actually cares about - it fails,
            // and the badge must keep showing `3`, never fall back to "not loaded".
            api.result = PendingBookingsResult.Failed(BookingsQueueFailure.Transport)
            dispatcher.scheduler.advanceTimeBy(PendingBookingsPoller.POLL_INTERVAL_MILLIS)
            dispatcher.scheduler.runCurrent()

            job.cancel()

            assertEquals(listOf(null, 3, 3), collected)
        }

    @Test
    fun `polling continues past a failure - a later success is not stuck behind it`() =
        runTest(dispatcher) {
            val api = FakeBookingsApi(result = PendingBookingsResult.Failed(BookingsQueueFailure.Unexpected))
            val poller = PendingBookingsPoller(api, dispatcher)
            val collected = mutableListOf<Int?>()
            val job = launch { poller.observeCount().collect { collected.add(it) } }

            dispatcher.scheduler.runCurrent()
            assertEquals(listOf(null, null), collected)

            api.result = PendingBookingsResult.Loaded(listOf(booking("a")))
            dispatcher.scheduler.advanceTimeBy(PendingBookingsPoller.POLL_INTERVAL_MILLIS)
            dispatcher.scheduler.runCurrent()

            job.cancel()

            assertEquals(listOf(null, null, 1), collected)
        }

    private fun booking(id: String): PendingBooking =
        PendingBooking(
            bookingId = id,
            calendarId = "cal-1",
            workerId = "worker-1",
            workerDisplayName = "Мастер",
            serviceId = "service-1",
            serviceName = "Стрижка",
            customerId = "person-1",
            startsAt = "2026-09-22T09:00:00Z",
            endsAt = "2026-09-22T09:30:00Z",
            localDate = "2026-09-22",
            confirmationDeadline = "2026-09-22T10:00:00Z",
        )

    /** A minimal [BookingsApi] fake - only [fetchPendingQueue] is this class's own concern, so every
     * other method throws, the identical "this test double has no test of its own for these" shape
     * `BookingsViewModelTest`'s own `FakeBookingsApi` already establishes. */
    private class FakeBookingsApi(
        var result: PendingBookingsResult = PendingBookingsResult.NotConfigured,
        private val hangFetch: Boolean = false,
    ) : BookingsApi {
        override suspend fun fetchPendingQueue(): PendingBookingsResult {
            if (hangFetch) awaitCancellation()
            return result
        }

        override suspend fun fetchConfirmedBookings(
            from: String,
            to: String,
        ): ConfirmedBookingsResult = throw UnsupportedOperationException("PendingBookingsPoller never calls this")

        override suspend fun fetchContacts(): ContactsResult = throw UnsupportedOperationException("PendingBookingsPoller never calls this")

        override suspend fun rejectBooking(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("PendingBookingsPoller never calls this")

        override suspend fun cancelBooking(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("PendingBookingsPoller never calls this")

        override suspend fun markNoShow(bookingId: String): BookingActionResult =
            throw UnsupportedOperationException("PendingBookingsPoller never calls this")

        override suspend fun revealCustomerPhone(
            customerId: String,
            surface: String,
        ): RevealPhoneResult = throw UnsupportedOperationException("PendingBookingsPoller never calls this")

        override suspend fun updateService(
            serviceId: String,
            name: String,
            durationMinutes: Int,
            priceMinorUnits: Int?,
            priceIsFrom: Boolean,
            description: String?,
            isActive: Boolean,
        ): BookingActionResult = throw UnsupportedOperationException("PendingBookingsPoller never calls this")

        override suspend fun fetchServices(): ServicesResult = throw UnsupportedOperationException("PendingBookingsPoller never calls this")

        override suspend fun fetchPhoneReveals(
            before: String?,
            limit: Int?,
        ): PhoneRevealsResult = throw UnsupportedOperationException("PendingBookingsPoller never calls this")
    }
}
