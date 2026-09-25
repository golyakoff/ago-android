package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.calendarsetup.CalendarDraft
import ago.chat.android.core.domain.calendarsetup.CalendarSetupApi
import ago.chat.android.core.domain.calendarsetup.ConfiguredCalendar
import ago.chat.android.core.domain.calendarsetup.TenantSetup
import ago.chat.android.core.domain.calendarsetup.TenantSetupResult
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
 * `26-142`: the Настройка / Календари screen's own read and its calendar-roster write surface — the
 * identical `StandardTestDispatcher`/`Dispatchers.setMain` shape every sibling view-model test in this
 * package already establishes ([MastersViewModelTest]).
 *
 * `26-158`: the embed-snippet and allowed-origins assertions this class used to carry are gone with those
 * surfaces themselves — they are now a chat/channel setting covered by `InstallWidgetViewModelTest`, not
 * this calendar screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarSetupViewModelTest {
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
            val api = FakeCalendarSetupApi(hangFetch = true)
            val viewModel = viewModel(api)

            dispatcher.scheduler.runCurrent()

            assertEquals(CalendarSetupUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `the setup arrives and the calendar roster is seeded`() =
        runTest(dispatcher) {
            val api = FakeCalendarSetupApi(result = loaded())
            val viewModel = viewModel(api)

            advanceUntilIdle()

            val state = viewModel.state.value as CalendarSetupUiState.Loaded
            assertEquals(listOf(calendar()), state.calendars)
        }

    @Test
    fun `NotConfigured passes straight through`() =
        runTest(dispatcher) {
            val api = FakeCalendarSetupApi(result = TenantSetupResult.NotConfigured)
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(CalendarSetupUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a failed read becomes a refusal carrying the adapter's own classification`() =
        runTest(dispatcher) {
            val api = FakeCalendarSetupApi(result = TenantSetupResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = viewModel(api)

            advanceUntilIdle()

            assertEquals(CalendarSetupUiState.Failed(BookingsQueueFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `creating a calendar sends its chosen timezone and re-reads`() =
        runTest(dispatcher) {
            val api = FakeCalendarSetupApi(result = loaded())
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.startAddCalendar()
            val form = (viewModel.state.value as CalendarSetupUiState.Loaded).calendarForm!!
            assertTrue(form.isCreating)
            assertEquals(DEFAULT_CALENDAR_TIME_ZONE, form.timeZoneId)
            viewModel.submitCalendar(form.copy(name = "Chair 2", timeZoneId = "Asia/Omsk", published = true))
            advanceUntilIdle()

            val (draft, zone) = api.createCalls.single()
            assertEquals("Chair 2", draft.name)
            assertTrue(draft.published)
            assertEquals("Asia/Omsk", zone)
            assertEquals(2, api.fetchCalls)
            assertNull((viewModel.state.value as CalendarSetupUiState.Loaded).calendarForm)
        }

    @Test
    fun `editing a calendar sends name and published only, never a timezone`() =
        runTest(dispatcher) {
            val api = FakeCalendarSetupApi(result = loaded())
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.editCalendar(calendar())
            val form = (viewModel.state.value as CalendarSetupUiState.Loaded).calendarForm!!
            assertTrue(!form.isCreating)
            viewModel.submitCalendar(form.copy(name = "Renamed", published = false))
            advanceUntilIdle()

            val (id, draft) = api.updateCalls.single()
            assertEquals("cal1", id)
            assertEquals("Renamed", draft.name)
            assertTrue(!draft.published)
            assertEquals(2, api.fetchCalls)
        }

    @Test
    fun `a refused calendar write keeps the form open and shows the server's own words`() =
        runTest(dispatcher) {
            val api =
                FakeCalendarSetupApi(
                    result = loaded(),
                    createResult = BookingActionResult.Refused("A calendar name is required."),
                )
            val viewModel = viewModel(api)
            advanceUntilIdle()

            viewModel.startAddCalendar()
            val form = (viewModel.state.value as CalendarSetupUiState.Loaded).calendarForm!!
            viewModel.submitCalendar(form)
            advanceUntilIdle()

            val state = viewModel.state.value as CalendarSetupUiState.Loaded
            // The form stays open with what was typed; only the initial read ran (a refusal never reloads).
            assertTrue(state.calendarForm != null)
            assertEquals(1, api.fetchCalls)
            assertEquals(BookingActionErrorUi.ServerRefusal("A calendar name is required."), state.actionError)
        }

    private fun viewModel(api: FakeCalendarSetupApi) = CalendarSetupViewModel(api = api, ioDispatcher = dispatcher)

    private fun calendar() = ConfiguredCalendar(id = "cal1", name = "Chair 1", timeZone = "Europe/Moscow", published = true)

    private fun setup() =
        TenantSetup(
            tenantName = "Shop",
            publicKey = "pk_1",
            allowedOrigins = listOf("https://a.example", "https://b.example"),
            calendars = listOf(calendar()),
        )

    private fun loaded() = TenantSetupResult.Loaded(setup())

    private class FakeCalendarSetupApi(
        var result: TenantSetupResult = TenantSetupResult.NotConfigured,
        private val hangFetch: Boolean = false,
        var createResult: BookingActionResult = BookingActionResult.Succeeded,
        var updateResult: BookingActionResult = BookingActionResult.Succeeded,
    ) : CalendarSetupApi {
        var fetchCalls: Int = 0
            private set
        val createCalls: MutableList<Pair<CalendarDraft, String>> = mutableListOf()
        val updateCalls: MutableList<Pair<String, CalendarDraft>> = mutableListOf()

        override suspend fun fetchSetup(): TenantSetupResult {
            fetchCalls++
            if (hangFetch) awaitCancellation()
            return result
        }

        // `26-158`: still part of the port (the console keeps this write), but no longer driven from this
        // calendar screen - the app's origins editing moved to «Установка виджета» (`26-159`).
        override suspend fun saveAllowedOrigins(origins: List<String>): BookingActionResult = BookingActionResult.Succeeded

        override suspend fun createCalendar(
            draft: CalendarDraft,
            timeZone: String,
        ): BookingActionResult {
            createCalls.add(draft to timeZone)
            return createResult
        }

        override suspend fun updateCalendar(
            calendarId: String,
            draft: CalendarDraft,
        ): BookingActionResult {
            updateCalls.add(calendarId to draft)
            return updateResult
        }
    }
}
