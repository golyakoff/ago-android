package ago.chat.android.shell

import ago.chat.android.devices.AutostartAdvisor
import ago.chat.android.devices.AutostartSettingsTarget
import ago.chat.android.devices.BatteryAwarenessPromptPreferences
import ago.chat.android.devices.DeviceModeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * `26-128`: the first-launch sheet's own "have I been dismissed" question - see
 * [BatteryAwarenessViewModel]'s own doc comment for why [dismiss] only ever persists when the checkbox
 * was checked, and why [BatteryAwarenessViewModel.autostartTarget] is read once rather than collected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatteryAwarenessViewModelTest {
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
    fun `visible is true when the prompt has never been dismissed`() =
        runTest(dispatcher) {
            val viewModel = viewModelWith(preferences = FakePromptPreferences(dismissed = false))
            advanceUntilIdle()

            assertTrue(viewModel.visible.value)
        }

    @Test
    fun `visible is false once the prompt has already been dismissed with the checkbox checked`() =
        runTest(dispatcher) {
            val viewModel = viewModelWith(preferences = FakePromptPreferences(dismissed = true))
            advanceUntilIdle()

            assertEquals(false, viewModel.visible.value)
        }

    @Test
    fun `dismiss without the checkbox hides the sheet but never persists`() =
        runTest(dispatcher) {
            val preferences = FakePromptPreferences(dismissed = false)
            val viewModel = viewModelWith(preferences = preferences)
            advanceUntilIdle()

            viewModel.dismiss(dontShowAgain = false)
            advanceUntilIdle()

            assertEquals(false, viewModel.visible.value)
            assertEquals("closing without the checkbox must never write the flag", 0, preferences.writes)
        }

    @Test
    fun `dismiss with the checkbox checked persists the flag`() =
        runTest(dispatcher) {
            val preferences = FakePromptPreferences(dismissed = false)
            val viewModel = viewModelWith(preferences = preferences)
            advanceUntilIdle()

            viewModel.dismiss(dontShowAgain = true)
            advanceUntilIdle()

            assertEquals(false, viewModel.visible.value)
            assertEquals(1, preferences.writes)
            assertTrue(preferences.current.value)
        }

    @Test
    fun `autostartTarget relays AutostartAdvisor's own answer`() =
        runTest(dispatcher) {
            val target = AutostartSettingsTarget.OemComponent("com.example.oem", "com.example.oem.AutostartActivity")
            val viewModel = viewModelWith(advisor = FakeAdvisor(target = target))
            advanceUntilIdle()

            assertEquals(target, viewModel.autostartTarget)
        }

    private fun viewModelWith(
        preferences: BatteryAwarenessPromptPreferences = FakePromptPreferences(),
        advisor: AutostartAdvisor = FakeAdvisor(),
    ): BatteryAwarenessViewModel = BatteryAwarenessViewModel(promptPreferences = preferences, autostartAdvisor = advisor)

    private class FakePromptPreferences(
        dismissed: Boolean = false,
    ) : BatteryAwarenessPromptPreferences {
        val current = MutableStateFlow(dismissed)
        override val dismissed: Flow<Boolean> = current

        var writes: Int = 0
            private set

        override suspend fun setDismissed(dismissed: Boolean) {
            current.value = dismissed
            writes++
        }
    }

    private class FakeAdvisor(
        private val status: DeviceModeStatus = DeviceModeStatus.Ok,
        private val target: AutostartSettingsTarget = AutostartSettingsTarget.None,
    ) : AutostartAdvisor {
        override fun recommendation(): DeviceModeStatus = status

        override fun settingsTarget(): AutostartSettingsTarget = target
    }
}
