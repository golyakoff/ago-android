package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.readiness.BookingPrecondition
import ago.chat.android.core.domain.readiness.BookingReadinessApi
import ago.chat.android.core.domain.readiness.BookingReadinessResult
import ago.chat.android.core.domain.readiness.CalendarReadiness
import ago.chat.android.core.domain.readiness.PreconditionState
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
 * `26-164`: [ReadinessViewModel]'s whole state machine - construction loads once, [ReadinessViewModel.refresh]
 * repeats the identical request on every call, and the three-way [BookingReadinessResult] maps onto the
 * matching [ReadinessUiState] arm unedited. The `StandardTestDispatcher`/`Dispatchers.setMain` shape
 * `SiteAnalyticsViewModelTest` already establishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadinessViewModelTest {
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
            val api = FakeBookingReadinessApi(hangFetch = true)
            val viewModel = ReadinessViewModel(api = api, ioDispatcher = dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(ReadinessUiState.Loading, viewModel.state.value)
            assertEquals(1, api.calls)
        }

    @Test
    fun `a loaded answer carries the calendars through unedited, on construction alone`() =
        runTest(dispatcher) {
            val calendars = listOf(calendarReadiness())
            val api = FakeBookingReadinessApi(result = BookingReadinessResult.Loaded(calendars))
            val viewModel = ReadinessViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ReadinessUiState.Loaded(calendars), viewModel.state.value)
            assertEquals(1, api.calls)
        }

    @Test
    fun `NotConfigured lands on its own state, not Failed`() =
        runTest(dispatcher) {
            val api = FakeBookingReadinessApi(result = BookingReadinessResult.NotConfigured)
            val viewModel = ReadinessViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ReadinessUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val api = FakeBookingReadinessApi(result = BookingReadinessResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = ReadinessViewModel(api = api, ioDispatcher = dispatcher)

            advanceUntilIdle()

            assertEquals(ReadinessUiState.Failed(BookingsQueueFailure.Transport), viewModel.state.value)
        }

    /** `refresh` is the one method both the retry button and `BookingsRoute`'s own "reopen the screen"
     * `LaunchedEffect` call - proved here by calling it directly, since the view model itself has no idea
     * which of the two triggered it. */
    @Test
    fun `refresh repeats the identical request, again`() =
        runTest(dispatcher) {
            val api = FakeBookingReadinessApi(result = BookingReadinessResult.Loaded(emptyList()))
            val viewModel = ReadinessViewModel(api = api, ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(1, api.calls)

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(2, api.calls)
            assertEquals(ReadinessUiState.Loaded(emptyList()), viewModel.state.value)
        }

    private fun calendarReadiness() =
        CalendarReadiness(
            calendarId = "cal1",
            calendarName = "Main",
            isBookable = false,
            preconditions =
                listOf(
                    PreconditionState(BookingPrecondition.WorkerOnCalendar, "WorkerOnCalendar", true),
                    PreconditionState(BookingPrecondition.ServiceOffered, "ServiceOffered", false),
                ),
        )

    private class FakeBookingReadinessApi(
        var result: BookingReadinessResult = BookingReadinessResult.Failed(BookingsQueueFailure.Unexpected),
        private val hangFetch: Boolean = false,
    ) : BookingReadinessApi {
        var calls: Int = 0

        override suspend fun fetchReadiness(): BookingReadinessResult {
            calls += 1
            if (hangFetch) awaitCancellation()
            return result
        }
    }
}
