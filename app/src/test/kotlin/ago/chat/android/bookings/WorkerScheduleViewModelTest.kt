package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.recut.RecutApi
import ago.chat.android.core.domain.recut.RecutBookingDecision
import ago.chat.android.core.domain.recut.RecutConfirmResult
import ago.chat.android.core.domain.recut.RecutPreview
import ago.chat.android.core.domain.recut.RecutPreviewResult
import ago.chat.android.core.domain.workerschedule.SaveWorkerScheduleResult
import ago.chat.android.core.domain.workerschedule.ScheduleKind
import ago.chat.android.core.domain.workerschedule.WorkerSchedule
import ago.chat.android.core.domain.workerschedule.WorkerScheduleApi
import ago.chat.android.core.domain.workerschedule.WorkerScheduleDraft
import ago.chat.android.core.domain.workerschedule.WorkerScheduleResult
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
 * `26-170` (`26-155` part 2): the «График» drill-down's own view model - a schedule that may not exist
 * yet ([WorkerScheduleResult.None]'s own "create" state), a save that refuses locally when a number does
 * not parse, and the minimal Q2 re-cut hook (preview only, never a decision or a confirm).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkerScheduleViewModelTest {
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
    fun `starts Loading before open is called`() =
        runTest(dispatcher) {
            val viewModel = WorkerScheduleViewModel(FakeWorkerScheduleApi(hangFetch = true), FakeRecutApi(), dispatcher)

            viewModel.open("w1")
            dispatcher.scheduler.runCurrent()

            assertEquals(WorkerScheduleUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `configuration no_schedule renders as an empty form, not a failure`() =
        runTest(dispatcher) {
            val viewModel = WorkerScheduleViewModel(FakeWorkerScheduleApi(schedule = null), FakeRecutApi(), dispatcher)

            viewModel.open("w1")
            advanceUntilIdle()

            val loaded = viewModel.state.value as WorkerScheduleUiState.Loaded
            assertNull(loaded.existing)
            assertEquals(ScheduleKind.Weekly, loaded.form.kind)
        }

    @Test
    fun `an existing schedule prefills the form from it`() =
        runTest(dispatcher) {
            val viewModel = WorkerScheduleViewModel(FakeWorkerScheduleApi(schedule = SCHEDULE), FakeRecutApi(), dispatcher)

            viewModel.open("w1")
            advanceUntilIdle()

            val loaded = viewModel.state.value as WorkerScheduleUiState.Loaded
            assertEquals(SCHEDULE, loaded.existing)
            assertEquals("45", loaded.form.slotMinutes)
            assertEquals("2026-09-01", loaded.form.materializeFrom)
        }

    @Test
    fun `a deployment with no calendar backend is NotConfigured, not a failure`() =
        runTest(dispatcher) {
            val viewModel = WorkerScheduleViewModel(FakeWorkerScheduleApi(notConfigured = true), FakeRecutApi(), dispatcher)

            viewModel.open("w1")
            advanceUntilIdle()

            assertEquals(WorkerScheduleUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `re-opening the same worker id does not re-fetch`() =
        runTest(dispatcher) {
            val api = FakeWorkerScheduleApi(schedule = SCHEDULE)
            val viewModel = WorkerScheduleViewModel(api, FakeRecutApi(), dispatcher)

            viewModel.open("w1")
            advanceUntilIdle()
            viewModel.open("w1")
            advanceUntilIdle()

            assertEquals(1, api.fetchCount)
        }

    @Test
    fun `opening a different worker id re-fetches for it`() =
        runTest(dispatcher) {
            val api = FakeWorkerScheduleApi(schedule = SCHEDULE)
            val viewModel = WorkerScheduleViewModel(api, FakeRecutApi(), dispatcher)

            viewModel.open("w1")
            advanceUntilIdle()
            viewModel.open("w2")
            advanceUntilIdle()

            assertEquals(2, api.fetchCount)
            assertEquals(listOf("w1", "w2"), api.fetchedWorkerIds)
        }

    @Test
    fun `a form whose numbers do not parse refuses locally - no request is sent`() =
        runTest(dispatcher) {
            val api = FakeWorkerScheduleApi(schedule = null)
            val viewModel = WorkerScheduleViewModel(api, FakeRecutApi(), dispatcher)
            viewModel.open("w1")
            advanceUntilIdle()

            val blankForm = (viewModel.state.value as WorkerScheduleUiState.Loaded).form
            viewModel.onFormChanged(blankForm.copy(slotMinutes = "not a number"))
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(0, api.saveCount)
        }

    @Test
    fun `saving sends every field and re-reads rather than trusting the write's own echo`() =
        runTest(dispatcher) {
            val api = FakeWorkerScheduleApi(schedule = null)
            val viewModel = WorkerScheduleViewModel(api, FakeRecutApi(), dispatcher)
            viewModel.open("w1")
            advanceUntilIdle()

            val form = (viewModel.state.value as WorkerScheduleUiState.Loaded).form
            viewModel.onFormChanged(form.copy(slotMinutes = "45", bufferMinutes = "5", horizonDays = "60"))
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(1, api.saveCount)
            assertEquals(45, api.lastDraft?.slotMinutes)
            // The authoritative answer is always the next GET - never the save's own echo.
            assertEquals(2, api.fetchCount)
        }

    @Test
    fun `a server refusal is shown verbatim and the typed form is kept, not blanked`() =
        runTest(dispatcher) {
            val api =
                FakeWorkerScheduleApi(
                    schedule = null,
                    saveResult = SaveWorkerScheduleResult.Refused("materializeFrom cannot move earlier."),
                )
            val viewModel = WorkerScheduleViewModel(api, FakeRecutApi(), dispatcher)
            viewModel.open("w1")
            advanceUntilIdle()

            val form = (viewModel.state.value as WorkerScheduleUiState.Loaded).form
            viewModel.onFormChanged(form.copy(slotMinutes = "45"))
            viewModel.submit()
            advanceUntilIdle()

            val loaded = viewModel.state.value as WorkerScheduleUiState.Loaded
            assertEquals(BookingActionErrorUi.ServerRefusal("materializeFrom cannot move earlier."), loaded.actionError)
            assertEquals("45", loaded.form.slotMinutes)
            assertEquals(false, loaded.formBusy)
            // A refusal never reloads.
            assertEquals(1, api.fetchCount)
        }

    @Test
    fun `a transport failure on save is not dressed up as a refusal`() =
        runTest(dispatcher) {
            val api =
                FakeWorkerScheduleApi(
                    schedule = null,
                    saveResult = SaveWorkerScheduleResult.Failed(BookingsQueueFailure.Transport),
                )
            val viewModel = WorkerScheduleViewModel(api, FakeRecutApi(), dispatcher)
            viewModel.open("w1")
            advanceUntilIdle()

            viewModel.submit()
            advanceUntilIdle()

            assertEquals(
                BookingActionErrorUi.Unavailable(BookingsQueueFailure.Transport),
                (viewModel.state.value as WorkerScheduleUiState.Loaded).actionError,
            )
        }

    @Test
    fun `a second submit while one is in flight is a no-op`() =
        runTest(dispatcher) {
            val api = FakeWorkerScheduleApi(schedule = null, hangSave = true)
            val viewModel = WorkerScheduleViewModel(api, FakeRecutApi(), dispatcher)
            viewModel.open("w1")
            advanceUntilIdle()

            viewModel.submit()
            dispatcher.scheduler.runCurrent()
            viewModel.submit()
            dispatcher.scheduler.runCurrent()

            assertEquals(1, api.saveCount)
            assertTrue((viewModel.state.value as WorkerScheduleUiState.Loaded).formBusy)
        }

    @Test
    fun `the minimal re-cut hook previews from the schedule's own cursor, read-only`() =
        runTest(dispatcher) {
            val preview = RecutPreview(days = emptyList(), fingerprint = "fp1")
            val recutApi = FakeRecutApi(previewResult = RecutPreviewResult.Loaded(preview))
            val viewModel = WorkerScheduleViewModel(FakeWorkerScheduleApi(schedule = SCHEDULE), recutApi, dispatcher)
            viewModel.open("w1")
            advanceUntilIdle()

            viewModel.previewRecut()
            advanceUntilIdle()

            assertEquals(SCHEDULE.materializeFrom, recutApi.lastFrom)
            assertEquals(RecutHookUiState.Loaded(preview), (viewModel.state.value as WorkerScheduleUiState.Loaded).recut)
            // The hook never decides or confirms anything on its own behalf.
            assertEquals(0, recutApi.confirmCount)
        }

    @Test
    fun `previewing with no saved schedule is a no-op - there is no cursor to preview from`() =
        runTest(dispatcher) {
            val recutApi = FakeRecutApi()
            val viewModel = WorkerScheduleViewModel(FakeWorkerScheduleApi(schedule = null), recutApi, dispatcher)
            viewModel.open("w1")
            advanceUntilIdle()

            viewModel.previewRecut()
            advanceUntilIdle()

            assertEquals(0, recutApi.previewCount)
        }

    @Test
    fun `a recut refusal is shown verbatim and dismissing clears it back to Idle`() =
        runTest(dispatcher) {
            val recutApi = FakeRecutApi(previewResult = RecutPreviewResult.Refused("Range starts before today.", "recut.from_before_today"))
            val viewModel = WorkerScheduleViewModel(FakeWorkerScheduleApi(schedule = SCHEDULE), recutApi, dispatcher)
            viewModel.open("w1")
            advanceUntilIdle()

            viewModel.previewRecut()
            advanceUntilIdle()

            assertEquals(
                RecutHookUiState.Refused("Range starts before today."),
                (viewModel.state.value as WorkerScheduleUiState.Loaded).recut,
            )

            viewModel.dismissRecutPreview()

            assertEquals(RecutHookUiState.Idle, (viewModel.state.value as WorkerScheduleUiState.Loaded).recut)
        }

    private companion object {
        val SCHEDULE =
            WorkerSchedule(
                scheduleId = "s1",
                workerId = "w1",
                kind = ScheduleKind.Weekly,
                cycleAnchor = null,
                cycleWorkingDays = null,
                cycleRestDays = null,
                cycleStartsAt = null,
                cycleEndsAt = null,
                slotMinutes = 45,
                bufferMinutes = 10,
                horizonDays = 30,
                materializeFrom = "2026-09-01",
                createdAt = "2026-08-01T00:00:00Z",
                updatedAt = "2026-08-01T00:00:00Z",
                buffersCountTowardServiceDuration = true,
            )
    }
}

/** The identical hand-written fake shape `FakeWorkingHoursApi` establishes - no mocking framework. */
private class FakeWorkerScheduleApi(
    private val schedule: WorkerSchedule? = null,
    private val notConfigured: Boolean = false,
    private val hangFetch: Boolean = false,
    private val hangSave: Boolean = false,
    private val saveResult: SaveWorkerScheduleResult? = null,
) : WorkerScheduleApi {
    var fetchCount: Int = 0
    var saveCount: Int = 0
    var lastDraft: WorkerScheduleDraft? = null
    val fetchedWorkerIds: MutableList<String> = mutableListOf()

    override suspend fun fetchSchedule(workerId: String): WorkerScheduleResult {
        fetchCount++
        fetchedWorkerIds += workerId
        if (hangFetch) awaitCancellation()
        if (notConfigured) return WorkerScheduleResult.NotConfigured
        return schedule?.let { WorkerScheduleResult.Loaded(it) } ?: WorkerScheduleResult.None
    }

    override suspend fun saveSchedule(
        workerId: String,
        draft: WorkerScheduleDraft,
    ): SaveWorkerScheduleResult {
        saveCount++
        lastDraft = draft
        if (hangSave) awaitCancellation()
        return saveResult ?: SaveWorkerScheduleResult.Saved(schedule ?: SCHEDULE_FOR_SAVE)
    }
}

/** A schedule saved when no fake `saveResult`/pre-existing schedule was configured - never asserted on
 * directly, since every test exercising the happy save path re-reads through [FakeWorkerScheduleApi]'s
 * own [FakeWorkerScheduleApi.fetchSchedule] instead of trusting this echo. */
private val SCHEDULE_FOR_SAVE =
    WorkerSchedule(
        scheduleId = "s1",
        workerId = "w1",
        kind = ScheduleKind.Weekly,
        cycleAnchor = null,
        cycleWorkingDays = null,
        cycleRestDays = null,
        cycleStartsAt = null,
        cycleEndsAt = null,
        slotMinutes = 30,
        bufferMinutes = 0,
        horizonDays = 30,
        materializeFrom = "2026-09-01",
        createdAt = "2026-08-01T00:00:00Z",
        updatedAt = "2026-08-01T00:00:00Z",
        buffersCountTowardServiceDuration = true,
    )

private class FakeRecutApi(
    private val previewResult: RecutPreviewResult = RecutPreviewResult.Loaded(RecutPreview(emptyList(), "fp")),
) : RecutApi {
    var previewCount: Int = 0
    var confirmCount: Int = 0
    var lastFrom: String? = null

    override suspend fun preview(
        workerId: String,
        from: String,
    ): RecutPreviewResult {
        previewCount++
        lastFrom = from
        return previewResult
    }

    override suspend fun confirm(
        workerId: String,
        from: String,
        fingerprint: String,
        decisions: List<RecutBookingDecision>,
    ): RecutConfirmResult {
        confirmCount++
        return RecutConfirmResult.NotConfigured
    }
}
