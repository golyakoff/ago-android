package ago.chat.android.schedule

import ago.chat.android.bookings.BookingActionErrorUi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.schedule.WorkingHoursApi
import ago.chat.android.core.domain.schedule.WorkingHoursChangeResult
import ago.chat.android.core.domain.schedule.WorkingHoursReconciliation
import ago.chat.android.core.domain.schedule.WorkingHoursResult
import ago.chat.android.core.domain.schedule.WorkingHoursRule
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
 * `26-97`: the two writes this product did not have — and, above all, the item's own hard constraint:
 * a correction is always allowed, and it never leaves an already-booked slot silently unreconciled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkingHoursViewModelTest {
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
            val viewModel = WorkingHoursViewModel(FakeWorkingHoursApi(hangFetch = true), dispatcher)

            dispatcher.scheduler.runCurrent()

            assertEquals(WorkingHoursUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a loaded list passes straight through, unedited`() =
        runTest(dispatcher) {
            val viewModel = WorkingHoursViewModel(FakeWorkingHoursApi(rules = listOf(RULE)), dispatcher)

            advanceUntilIdle()

            assertEquals(listOf(RULE), (viewModel.state.value as WorkingHoursUiState.Loaded).rules)
        }

    @Test
    fun `a deployment with no calendar backend is NotConfigured, not a failure`() =
        runTest(dispatcher) {
            val viewModel = WorkingHoursViewModel(FakeWorkingHoursApi(notConfigured = true), dispatcher)

            advanceUntilIdle()

            assertEquals(WorkingHoursUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `saving sends the three fields a human types, and re-reads rather than patching`() =
        runTest(dispatcher) {
            val api = FakeWorkingHoursApi(rules = listOf(RULE))
            val viewModel = WorkingHoursViewModel(api, dispatcher)
            advanceUntilIdle()

            viewModel.save("r1", dayOfWeek = 3, startsAt = "10:00", endsAt = "19:00")
            advanceUntilIdle()

            assertEquals(listOf(Saved("r1", 3, "10:00", "19:00")), api.saved)
            // The authoritative answer is always the next GET - never the write's own echo.
            assertEquals(2, api.fetchCount)
        }

    /**
     * The item's own one hard constraint, at the level where it is decided: the write succeeds, and
     * what the server said about the days it could not reach is kept on screen rather than dropped.
     * A view model that discarded this would compile, pass every other test here, and leave an
     * operator believing three weeks of already-cut days had been corrected.
     */
    @Test
    fun `a correction that could not reach already-cut days keeps the server's own reconciliation`() =
        runTest(dispatcher) {
            val reconciliation =
                WorkingHoursReconciliation(
                    recutFrom = "2026-09-28",
                    alreadyCutDays = listOf("2026-09-28", "2026-10-05"),
                    liveBookingCount = 2,
                )
            val api = FakeWorkingHoursApi(rules = listOf(RULE), change = WorkingHoursChangeResult.Changed(reconciliation))
            val viewModel = WorkingHoursViewModel(api, dispatcher)
            advanceUntilIdle()

            viewModel.save("r1", dayOfWeek = 1, startsAt = "09:00", endsAt = "19:00")
            advanceUntilIdle()

            assertEquals(reconciliation, (viewModel.state.value as WorkingHoursUiState.Loaded).notice)
        }

    @Test
    fun `a correction that reached everything shows no notice at all`() =
        runTest(dispatcher) {
            // The ordinary, happy case - the cursor has nothing behind it. A notice drawn anyway would
            // be trained-away noise, which is how a real warning stops being read.
            val api = FakeWorkingHoursApi(rules = listOf(RULE))
            val viewModel = WorkingHoursViewModel(api, dispatcher)
            advanceUntilIdle()

            viewModel.save("r1", dayOfWeek = 1, startsAt = "09:00", endsAt = "19:00")
            advanceUntilIdle()

            assertNull((viewModel.state.value as WorkingHoursUiState.Loaded).notice)
        }

    @Test
    fun `deleting removes the rule and re-reads`() =
        runTest(dispatcher) {
            val api = FakeWorkingHoursApi(rules = listOf(RULE))
            val viewModel = WorkingHoursViewModel(api, dispatcher)
            advanceUntilIdle()

            viewModel.delete("r1")
            advanceUntilIdle()

            assertEquals(listOf("r1"), api.deleted)
            assertEquals(2, api.fetchCount)
        }

    @Test
    fun `a server refusal is shown verbatim and the list is left exactly as it was`() =
        runTest(dispatcher) {
            val api =
                FakeWorkingHoursApi(
                    rules = listOf(RULE),
                    change = WorkingHoursChangeResult.Refused("Working hours must end after they start."),
                )
            val viewModel = WorkingHoursViewModel(api, dispatcher)
            advanceUntilIdle()

            viewModel.save("r1", dayOfWeek = 1, startsAt = "22:00", endsAt = "02:00")
            advanceUntilIdle()

            val loaded = viewModel.state.value as WorkingHoursUiState.Loaded
            assertEquals(
                BookingActionErrorUi.ServerRefusal("Working hours must end after they start."),
                loaded.actionError,
            )
            assertEquals(listOf(RULE), loaded.rules)
            // A refusal never reloads: the rows the operator was looking at stay put.
            assertEquals(1, api.fetchCount)
        }

    @Test
    fun `a transport failure on a write is not dressed up as a refusal`() =
        runTest(dispatcher) {
            val api =
                FakeWorkingHoursApi(
                    rules = listOf(RULE),
                    change = WorkingHoursChangeResult.Failed(BookingsQueueFailure.Transport),
                )
            val viewModel = WorkingHoursViewModel(api, dispatcher)
            advanceUntilIdle()

            viewModel.delete("r1")
            advanceUntilIdle()

            assertEquals(
                BookingActionErrorUi.Unavailable(BookingsQueueFailure.Transport),
                (viewModel.state.value as WorkingHoursUiState.Loaded).actionError,
            )
        }

    @Test
    fun `a second write on the same rule while one is in flight is a no-op`() =
        runTest(dispatcher) {
            val api = FakeWorkingHoursApi(rules = listOf(RULE), hangWrite = true)
            val viewModel = WorkingHoursViewModel(api, dispatcher)
            advanceUntilIdle()

            viewModel.save("r1", dayOfWeek = 1, startsAt = "09:00", endsAt = "19:00")
            dispatcher.scheduler.runCurrent()
            viewModel.save("r1", dayOfWeek = 2, startsAt = "08:00", endsAt = "17:00")
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.writeAttempts)
            assertTrue("r1" in (viewModel.state.value as WorkingHoursUiState.Loaded).busyRuleIds)
        }

    @Test
    fun `a refresh clears the previous write's notice`() =
        runTest(dispatcher) {
            // A reconciliation describes one particular write. Carrying it across a deliberate reload
            // would make it look like a property of the list.
            val api =
                FakeWorkingHoursApi(
                    rules = listOf(RULE),
                    change =
                        WorkingHoursChangeResult.Changed(
                            WorkingHoursReconciliation("2026-09-28", listOf("2026-09-28"), 1),
                        ),
                )
            val viewModel = WorkingHoursViewModel(api, dispatcher)
            advanceUntilIdle()
            viewModel.save("r1", dayOfWeek = 1, startsAt = "09:00", endsAt = "19:00")
            advanceUntilIdle()

            viewModel.refresh()
            advanceUntilIdle()

            assertNull((viewModel.state.value as WorkingHoursUiState.Loaded).notice)
        }

    private companion object {
        val RULE =
            WorkingHoursRule(
                ruleId = "r1",
                workerId = "w1",
                workerName = "Alex",
                calendarName = "Main",
                dayOfWeek = 1,
                startsAt = "09:00",
                endsAt = "09:30",
            )
    }
}

private data class Saved(
    val ruleId: String,
    val dayOfWeek: Int,
    val startsAt: String,
    val endsAt: String,
)

/** The identical hand-written fake shape `FakeBookingsApi` establishes - no mocking framework, so the
 * "what was actually sent" assertions read off plain fields rather than a verification DSL. */
private class FakeWorkingHoursApi(
    private val rules: List<WorkingHoursRule> = emptyList(),
    private val notConfigured: Boolean = false,
    private val hangFetch: Boolean = false,
    private val hangWrite: Boolean = false,
    private val change: WorkingHoursChangeResult =
        WorkingHoursChangeResult.Changed(WorkingHoursReconciliation(null, emptyList(), 0)),
) : WorkingHoursApi {
    var fetchCount: Int = 0
    var writeAttempts: Int = 0
    val saved: MutableList<Saved> = mutableListOf()
    val deleted: MutableList<String> = mutableListOf()

    override suspend fun fetchWorkingHours(): WorkingHoursResult {
        fetchCount++
        if (hangFetch) awaitCancellation()
        return if (notConfigured) WorkingHoursResult.NotConfigured else WorkingHoursResult.Loaded(rules)
    }

    override suspend fun updateWorkingHoursRule(
        ruleId: String,
        dayOfWeek: Int,
        startsAt: String,
        endsAt: String,
    ): WorkingHoursChangeResult {
        writeAttempts++
        saved += Saved(ruleId, dayOfWeek, startsAt, endsAt)
        if (hangWrite) awaitCancellation()
        return change
    }

    override suspend fun deleteWorkingHoursRule(ruleId: String): WorkingHoursChangeResult {
        writeAttempts++
        deleted += ruleId
        if (hangWrite) awaitCancellation()
        return change
    }
}
