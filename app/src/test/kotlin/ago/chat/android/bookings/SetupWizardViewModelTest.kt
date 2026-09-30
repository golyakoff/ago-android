package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.modules.EnabledModule
import ago.chat.android.core.domain.modules.ModulesApi
import ago.chat.android.core.domain.modules.ModulesResult
import ago.chat.android.core.domain.net.NetworkFailure
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
 * `26-332`: [SetupWizardViewModel]'s whole state machine - construction reads once (readiness + the module
 * list), folds the two into the single derived [SetupWizardStep], and [SetupWizardViewModel.refresh] repeats
 * both reads. The `StandardTestDispatcher`/`Dispatchers.setMain` shape [ReadinessViewModelTest] establishes.
 * The step-derivation logic itself is exhausted in [SetupWizardStepTest]; this suite proves the view model
 * wires the two reads onto it correctly - especially the "a failed module read is a closed trigger gate,
 * never an open one" rule (`26-329` decision 6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupWizardViewModelTest {
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
            val viewModel = viewModel(FakeReadiness(hangFetch = true), FakeModules())

            dispatcher.scheduler.runCurrent()

            assertEquals(SetupWizardUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `a tenant with no calendar lands on CreateCalendar with a null calendar name`() =
        runTest(dispatcher) {
            val readiness = FakeReadiness(BookingReadinessResult.Loaded(listOf(noCalendar())))
            val viewModel = viewModel(readiness, FakeModules())

            advanceUntilIdle()

            assertEquals(
                SetupWizardUiState.Loaded(SetupWizardStep.CreateCalendar, calendarName = null, triggerWord = "/записаться"),
                viewModel.state.value,
            )
        }

    @Test
    fun `everything met with a trigger word reaches Done, carrying that word through`() =
        runTest(dispatcher) {
            val readiness = FakeReadiness(BookingReadinessResult.Loaded(listOf(calendar(unmet = emptySet()))))
            val modules = FakeModules(ModulesResult.Loaded(listOf(calendarModule(triggerWords = listOf("/бронь")))))
            val viewModel = viewModel(readiness, modules)

            advanceUntilIdle()

            assertEquals(
                SetupWizardUiState.Loaded(SetupWizardStep.Done, calendarName = "Main", triggerWord = "/бронь"),
                viewModel.state.value,
            )
        }

    @Test
    fun `everything met but no trigger word set stops at BookingTrigger with the default word`() =
        runTest(dispatcher) {
            val readiness = FakeReadiness(BookingReadinessResult.Loaded(listOf(calendar(unmet = emptySet()))))
            val modules = FakeModules(ModulesResult.Loaded(listOf(calendarModule(triggerWords = emptyList()))))
            val viewModel = viewModel(readiness, modules)

            advanceUntilIdle()

            assertEquals(
                SetupWizardUiState.Loaded(SetupWizardStep.BookingTrigger, calendarName = "Main", triggerWord = "/записаться"),
                viewModel.state.value,
            )
        }

    /** `26-329` decision 6: a failed module read must never open the trigger gate - the safe default is the
     * closed gate, the same as "no trigger word set", so the wizard holds at BookingTrigger rather than
     * waving the tenant on to publish. The readiness read still succeeded, so the whole screen is Loaded, not
     * Failed - the identical "supplementary read degrades quietly" posture the console's own wizard states. */
    @Test
    fun `a failed module read is a closed trigger gate, not an open one`() =
        runTest(dispatcher) {
            val readiness = FakeReadiness(BookingReadinessResult.Loaded(listOf(calendar(unmet = emptySet()))))
            val modules = FakeModules(ModulesResult.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModel(readiness, modules)

            advanceUntilIdle()

            assertEquals(
                SetupWizardUiState.Loaded(SetupWizardStep.BookingTrigger, calendarName = "Main", triggerWord = "/записаться"),
                viewModel.state.value,
            )
        }

    @Test
    fun `NotConfigured lands on its own state, not Failed`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeReadiness(BookingReadinessResult.NotConfigured), FakeModules())

            advanceUntilIdle()

            assertEquals(SetupWizardUiState.NotConfigured, viewModel.state.value)
        }

    @Test
    fun `a readiness failure carries its own classification through, unedited`() =
        runTest(dispatcher) {
            val readiness = FakeReadiness(BookingReadinessResult.Failed(BookingsQueueFailure.Transport))
            val viewModel = viewModel(readiness, FakeModules())

            advanceUntilIdle()

            assertEquals(SetupWizardUiState.Failed(BookingsQueueFailure.Transport), viewModel.state.value)
        }

    @Test
    fun `refresh repeats both reads`() =
        runTest(dispatcher) {
            val readiness = FakeReadiness(BookingReadinessResult.Loaded(listOf(calendar(unmet = emptySet()))))
            val modules = FakeModules(ModulesResult.Loaded(listOf(calendarModule(triggerWords = listOf("/бронь")))))
            val viewModel = viewModel(readiness, modules)
            advanceUntilIdle()
            assertEquals(1, readiness.calls)
            assertEquals(1, modules.calls)

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(2, readiness.calls)
            assertEquals(2, modules.calls)
        }

    private fun viewModel(
        readiness: BookingReadinessApi,
        modules: ModulesApi,
    ) = SetupWizardViewModel(
        readinessApi = readiness,
        modulesApi = modules,
        ioDispatcher = dispatcher,
    )

    private fun noCalendar() = CalendarReadiness(calendarId = null, calendarName = null, isBookable = false, preconditions = emptyList())

    private fun calendar(unmet: Set<BookingPrecondition>): CalendarReadiness {
        val order =
            listOf(
                BookingPrecondition.WorkerOnCalendar,
                BookingPrecondition.ServiceOffered,
                BookingPrecondition.WorkingHoursConfigured,
                BookingPrecondition.ScheduleSaved,
                BookingPrecondition.SlotsMaterialized,
                BookingPrecondition.CalendarPublished,
            )
        return CalendarReadiness(
            calendarId = "cal-1",
            calendarName = "Main",
            isBookable = unmet.isEmpty(),
            preconditions = order.map { PreconditionState(it, it.name, isMet = it !in unmet) },
        )
    }

    private fun calendarModule(triggerWords: List<String>) =
        EnabledModule(
            moduleKey = "calendar",
            triggerWords = triggerWords,
            entryPoint = "https://calendar.example/entry",
            grantedByOwner = false,
            expiresAt = null,
        )

    private class FakeReadiness(
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

    private class FakeModules(
        var result: ModulesResult = ModulesResult.Loaded(emptyList()),
    ) : ModulesApi {
        var calls: Int = 0

        override suspend fun fetch(): ModulesResult {
            calls += 1
            return result
        }
    }
}
