package ago.chat.android.devices

import android.app.NotificationManager
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
import org.junit.Before
import org.junit.Test

/**
 * `26-19`: [NotificationSettingsViewModel]'s own two concerns, each proven with no Android
 * `NotificationManager` and no real `DataStore` file — the identical `SettingsViewModelTest` shape this
 * screen's own view model otherwise mirrors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsViewModelTest {
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
    fun `channelStates starts with exactly the two real channels, read from the reader`() =
        runTest(dispatcher) {
            val reader =
                FakeChannelStateReader(
                    mapOf(
                        PushNotificationChannel.Assignment.id to NotificationManager.IMPORTANCE_DEFAULT,
                        PushNotificationChannel.VisitorMessage.id to NotificationManager.IMPORTANCE_NONE,
                    ),
                )
            val viewModel = viewModelWith(channelStateReader = reader)
            advanceUntilIdle()

            assertEquals(
                mapOf(PushNotificationChannel.Assignment to true, PushNotificationChannel.VisitorMessage to false),
                viewModel.channelStates.value,
            )
        }

    @Test
    fun `refreshChannelStates re-reads the live system truth rather than trusting the cached value`() =
        runTest(dispatcher) {
            val reader =
                FakeChannelStateReader(
                    mapOf(
                        PushNotificationChannel.Assignment.id to NotificationManager.IMPORTANCE_DEFAULT,
                        PushNotificationChannel.VisitorMessage.id to NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            val viewModel = viewModelWith(channelStateReader = reader)
            advanceUntilIdle()
            assertEquals(true, viewModel.channelStates.value.getValue(PushNotificationChannel.Assignment))

            // The operator left for the system per-channel settings screen, turned Assignment off, and
            // came back - simulated here by flipping the fake's own answer and asking this class to look
            // again.
            reader.importances[PushNotificationChannel.Assignment.id] = NotificationManager.IMPORTANCE_NONE
            viewModel.refreshChannelStates()

            assertEquals(false, viewModel.channelStates.value.getValue(PushNotificationChannel.Assignment))
        }

    @Test
    fun `quietHours starts at whatever QuietHoursPreferences already holds`() =
        runTest(dispatcher) {
            val preferences = FakeQuietHoursPreferences(QuietHoursSettings(enabled = true, startMinuteOfDay = 60, endMinuteOfDay = 120))
            val viewModel = viewModelWith(quietHoursPreferences = preferences)
            advanceUntilIdle()

            assertEquals(QuietHoursSettings(enabled = true, startMinuteOfDay = 60, endMinuteOfDay = 120), viewModel.quietHours.value)
        }

    @Test
    fun `setQuietHoursEnabled writes through to QuietHoursPreferences, keeping the existing range`() =
        runTest(dispatcher) {
            val preferences = FakeQuietHoursPreferences(QuietHoursSettings(enabled = false, startMinuteOfDay = 60, endMinuteOfDay = 120))
            val viewModel = viewModelWith(quietHoursPreferences = preferences)
            advanceUntilIdle()

            viewModel.setQuietHoursEnabled(true)
            advanceUntilIdle()

            assertEquals(QuietHoursSettings(enabled = true, startMinuteOfDay = 60, endMinuteOfDay = 120), preferences.current.value)
        }

    @Test
    fun `setQuietHoursRange writes through both minutes, keeping the existing enabled flag`() =
        runTest(dispatcher) {
            val preferences = FakeQuietHoursPreferences(QuietHoursSettings(enabled = true, startMinuteOfDay = 0, endMinuteOfDay = 0))
            val viewModel = viewModelWith(quietHoursPreferences = preferences)
            advanceUntilIdle()

            viewModel.setQuietHoursRange(startMinuteOfDay = 22 * 60, endMinuteOfDay = 7 * 60)
            advanceUntilIdle()

            assertEquals(
                QuietHoursSettings(enabled = true, startMinuteOfDay = 22 * 60, endMinuteOfDay = 7 * 60),
                preferences.current.value,
            )
        }

    // ------------------------------------------------------------------------------------- fakes

    private fun viewModelWith(
        channelStateReader: NotificationChannelStateReader = FakeChannelStateReader(emptyMap()),
        quietHoursPreferences: QuietHoursPreferences = FakeQuietHoursPreferences(),
    ): NotificationSettingsViewModel =
        NotificationSettingsViewModel(
            channelStateReader = channelStateReader,
            quietHoursPreferences = quietHoursPreferences,
        )

    private class FakeChannelStateReader(
        initial: Map<String, Int>,
    ) : NotificationChannelStateReader {
        val importances: MutableMap<String, Int> = initial.toMutableMap()

        override fun importanceOf(channelId: String): Int = importances[channelId] ?: NotificationManager.IMPORTANCE_UNSPECIFIED
    }

    private class FakeQuietHoursPreferences(
        initial: QuietHoursSettings = QuietHoursSettings(),
    ) : QuietHoursPreferences {
        val current = MutableStateFlow(initial)
        override val settings: Flow<QuietHoursSettings> = current

        override suspend fun setSettings(settings: QuietHoursSettings) {
            current.value = settings
        }
    }
}
