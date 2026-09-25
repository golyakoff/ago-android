package ago.chat.android.shell

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.identity.IdentityApi
import ago.chat.android.core.domain.identity.ProbeOutcome
import ago.chat.android.core.domain.identity.Tenancy
import ago.chat.android.core.domain.identity.TenancyListing
import ago.chat.android.core.network.realtime.ConversationAssignedDto
import ago.chat.android.core.network.realtime.HistoryPage
import ago.chat.android.core.network.realtime.MessageDeliveredDto
import ago.chat.android.core.network.realtime.MessageDto
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.core.network.realtime.SendMessageResult
import ago.chat.android.core.network.realtime.TeamHistoryPage
import ago.chat.android.core.network.realtime.TeamMessageDto
import ago.chat.android.devices.AutostartAdvisor
import ago.chat.android.devices.AutostartSettingsTarget
import ago.chat.android.devices.BatteryOptimizationChecker
import ago.chat.android.devices.DeviceModeStatus
import ago.chat.android.devices.DeviceRegistrar
import ago.chat.android.devices.NotificationPermissionChecker
import ago.chat.android.devices.PushAvailability
import ago.chat.android.devices.PushUnavailableReason
import ago.chat.android.ui.language.AppLanguage
import ago.chat.android.ui.language.AppLanguagePreferences
import ago.chat.android.ui.theme.ThemeMode
import ago.chat.android.ui.theme.ThemePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
 * `26-17`: [SettingsViewModel]'s own three concerns, each proven with no Android, no network and no real
 * hub connection — the identical `SignInViewModelTest`/`ConversationListViewModelTest` shape.
 *
 * The active-site switch is the one this file's own "fails-before" case is about:
 * `switchSite writes the REST header AND reconnects the hub - not one or the other` fails against an
 * implementation of [SettingsViewModel.switchSite] that only calls [ActiveSiteSelection.select] — the
 * shape it would be easy to stop at, since the REST header alone is enough to make a manual smoke test
 * of the switcher *look* like it worked — because it separately asserts on [FakeHubEvents.reconnectCalls].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val siteA = Tenancy("11111111-1111-1111-1111-111111111111", "Кофейня")
    private val siteB = Tenancy("22222222-2222-2222-2222-222222222222", "Ярмарка")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------------------------- theme

    @Test
    fun `themeMode starts at whatever ThemePreferences already holds`() =
        runTest(dispatcher) {
            val preferences = FakeThemePreferences(ThemeMode.Dark)
            val viewModel = viewModelWith(themePreferences = preferences)
            advanceUntilIdle()

            assertEquals(ThemeMode.Dark, viewModel.themeMode.value)
        }

    @Test
    fun `setThemeMode writes through to ThemePreferences`() =
        runTest(dispatcher) {
            val preferences = FakeThemePreferences(ThemeMode.System)
            val viewModel = viewModelWith(themePreferences = preferences)
            advanceUntilIdle()

            viewModel.setThemeMode(ThemeMode.Light)
            advanceUntilIdle()

            assertEquals(ThemeMode.Light, preferences.current.value)
        }

    // ---------------------------------------------------------------------------------- language

    @Test
    fun `language starts at whatever AppLanguagePreferences already holds`() =
        runTest(dispatcher) {
            val preferences = FakeAppLanguagePreferences(AppLanguage.English)
            val viewModel = viewModelWith(languagePreferences = preferences)
            advanceUntilIdle()

            assertEquals(AppLanguage.English, viewModel.language.value)
        }

    @Test
    fun `setLanguage writes through to AppLanguagePreferences`() =
        runTest(dispatcher) {
            val preferences = FakeAppLanguagePreferences(AppLanguage.System)
            val viewModel = viewModelWith(languagePreferences = preferences)
            advanceUntilIdle()

            viewModel.setLanguage(AppLanguage.Russian)
            advanceUntilIdle()

            assertEquals(AppLanguage.Russian, preferences.current.value)
        }

    /**
     * `26-92`'s own load-bearing ordering guarantee: [SettingsRoute] calls
     * [ago.chat.android.ui.language.applyAppLanguage] (which, below API 33, recreates the current
     * `Activity`) only once [SettingsViewModel.languageApplied] fires - and this proves that fire never
     * happens before the write it is supposed to be reporting has actually landed. [FakeAppLanguagePreferences]
     * makes [AppLanguagePreferences.setLanguage] suspend past a `writes` marker this test flips *after* the
     * write completes; if `languageApplied` could fire first, this test would see it collected while
     * `writes` was still `0`.
     */
    @Test
    fun `setLanguage emits on languageApplied only after the preference write lands`() =
        runTest(dispatcher) {
            val preferences = FakeAppLanguagePreferences(AppLanguage.System)
            val viewModel = viewModelWith(languagePreferences = preferences)
            advanceUntilIdle()

            var writesSeenWhenApplied: Int? = null
            val collecting =
                CoroutineScope(dispatcher).launch {
                    viewModel.languageApplied.collect { writesSeenWhenApplied = preferences.writes }
                }

            viewModel.setLanguage(AppLanguage.English)
            advanceUntilIdle()

            assertEquals(1, writesSeenWhenApplied)
            collecting.cancel()
        }

    // ------------------------------------------------------------------------------ site switching

    @Test
    fun `switchSite writes the REST header AND reconnects the hub - not one or the other`() =
        runTest(dispatcher) {
            val activeSite = InMemoryActiveSite(siteId = siteA.siteId)
            val hubEvents = FakeHubEvents()
            val viewModel =
                viewModelWith(
                    identity = FakeIdentityApi(TenancyListing.Known(listOf(siteA, siteB))),
                    activeSite = activeSite,
                    hubEvents = hubEvents,
                )
            advanceUntilIdle()

            viewModel.switchSite(siteB.siteId)
            advanceUntilIdle()

            assertEquals("the REST header's own source of truth must move", siteB.siteId, activeSite.currentSiteId())
            assertEquals("the hub connection must reconnect exactly once", 1, hubEvents.reconnectCalls)
        }

    @Test
    fun `switchSite carries the new site id on siteSwitched, after both writes land`() =
        runTest(dispatcher) {
            val hubEvents = FakeHubEvents()
            val viewModel =
                viewModelWith(
                    identity = FakeIdentityApi(TenancyListing.Known(listOf(siteA, siteB))),
                    activeSite = InMemoryActiveSite(siteId = siteA.siteId),
                    hubEvents = hubEvents,
                )
            advanceUntilIdle()

            var delivered: String? = null
            val collecting = CoroutineScope(dispatcher).launch { viewModel.siteSwitched.collect { delivered = it } }

            viewModel.switchSite(siteB.siteId)
            advanceUntilIdle()

            assertEquals(siteB.siteId, delivered)
            assertEquals(siteB.siteId, viewModel.currentSiteId.value)
            collecting.cancel()
        }

    @Test
    fun `switching to the already-active site is a no-op`() =
        runTest(dispatcher) {
            val activeSite = InMemoryActiveSite(siteId = siteA.siteId)
            val hubEvents = FakeHubEvents()
            val viewModel =
                viewModelWith(
                    identity = FakeIdentityApi(TenancyListing.Known(listOf(siteA, siteB))),
                    activeSite = activeSite,
                    hubEvents = hubEvents,
                )
            advanceUntilIdle()

            viewModel.switchSite(siteA.siteId)
            advanceUntilIdle()

            assertEquals(0, hubEvents.reconnectCalls)
        }

    @Test
    fun `tenancies reflects whatever IdentityApi answers`() =
        runTest(dispatcher) {
            val viewModel = viewModelWith(identity = FakeIdentityApi(TenancyListing.Known(listOf(siteA, siteB))))
            advanceUntilIdle()

            assertEquals(TenancyListing.Known(listOf(siteA, siteB)), viewModel.tenancies.value)
        }

    @Test
    fun `currentSiteId starts at whatever ActiveSiteSelection already reports`() =
        runTest(dispatcher) {
            val viewModel = viewModelWith(activeSite = InMemoryActiveSite(siteId = siteA.siteId))
            advanceUntilIdle()

            assertEquals(siteA.siteId, viewModel.currentSiteId.value)
        }

    @Test
    fun `pushAvailability relays DeviceRegistrar's own value, Unavailable included`() =
        runTest(dispatcher) {
            val registrar =
                FakeSettingsDeviceRegistrar(availability = PushAvailability.Unavailable(PushUnavailableReason.HostAppNotInstalled))
            val viewModel = viewModelWith(deviceRegistrar = registrar)
            advanceUntilIdle()

            assertEquals(
                PushAvailability.Unavailable(PushUnavailableReason.HostAppNotInstalled),
                viewModel.pushAvailability.value,
            )
        }

    @Test
    fun `notificationsEnabled starts at the checker's own live answer`() =
        runTest(dispatcher) {
            val checker = FakeNotificationPermissionChecker(initial = false)
            val viewModel = viewModelWith(notificationPermissionChecker = checker)
            advanceUntilIdle()

            assertEquals(false, viewModel.notificationsEnabled.value)
        }

    @Test
    fun `refreshNotificationPermission re-reads the live system truth rather than trusting the cached value`() =
        runTest(dispatcher) {
            val checker = FakeNotificationPermissionChecker(initial = true)
            val viewModel = viewModelWith(notificationPermissionChecker = checker)
            advanceUntilIdle()
            assertEquals(true, viewModel.notificationsEnabled.value)

            // The operator left this screen, disabled notifications in system Settings, and came back -
            // simulated here by flipping the fake's own answer and asking this class to look again.
            checker.answer = false
            viewModel.refreshNotificationPermission()

            assertEquals(false, viewModel.notificationsEnabled.value)
        }

    // -------------------------------------------------------------------------------- battery mode

    @Test
    fun `batteryUnrestricted starts at the checker's own live answer`() =
        runTest(dispatcher) {
            val checker = FakeBatteryOptimizationChecker(initial = false)
            val viewModel = viewModelWith(batteryOptimizationChecker = checker)
            advanceUntilIdle()

            assertEquals(false, viewModel.batteryUnrestricted.value)
        }

    @Test
    fun `refreshBatteryOptimization re-reads the live system truth rather than trusting the cached value`() =
        runTest(dispatcher) {
            val checker = FakeBatteryOptimizationChecker(initial = true)
            val viewModel = viewModelWith(batteryOptimizationChecker = checker)
            advanceUntilIdle()
            assertEquals(true, viewModel.batteryUnrestricted.value)

            // The operator left this screen, opened «Настройки батареи», turned the mode off, and came
            // back - simulated here by flipping the fake's own answer and asking this class to look again.
            checker.answer = false
            viewModel.refreshBatteryOptimization()

            assertEquals(false, viewModel.batteryUnrestricted.value)
        }

    // ----------------------------------------------------------------------------------- autostart

    @Test
    fun `autostartStatus and autostartSettingsTarget relay AutostartAdvisor's own answers`() =
        runTest(dispatcher) {
            val advisor =
                FakeAutostartAdvisor(
                    status = DeviceModeStatus.NeedsAttention,
                    target = AutostartSettingsTarget.OemComponent("com.example.oem", "com.example.oem.AutostartActivity"),
                )
            val viewModel = viewModelWith(autostartAdvisor = advisor)
            advanceUntilIdle()

            assertEquals(DeviceModeStatus.NeedsAttention, viewModel.autostartStatus)
            assertEquals(
                AutostartSettingsTarget.OemComponent("com.example.oem", "com.example.oem.AutostartActivity"),
                viewModel.autostartSettingsTarget,
            )
        }

    // ------------------------------------------------------------------------------------- fakes

    private fun viewModelWith(
        identity: IdentityApi = FakeIdentityApi(TenancyListing.Known(emptyList())),
        activeSite: ActiveSiteSelection = InMemoryActiveSite(),
        hubEvents: OperatorHubEvents = FakeHubEvents(),
        themePreferences: ThemePreferences = FakeThemePreferences(),
        languagePreferences: AppLanguagePreferences = FakeAppLanguagePreferences(),
        deviceRegistrar: DeviceRegistrar = FakeSettingsDeviceRegistrar(),
        notificationPermissionChecker: NotificationPermissionChecker = FakeNotificationPermissionChecker(),
        batteryOptimizationChecker: BatteryOptimizationChecker = FakeBatteryOptimizationChecker(),
        autostartAdvisor: AutostartAdvisor = FakeAutostartAdvisor(),
    ): SettingsViewModel =
        SettingsViewModel(
            identity = identity,
            activeSite = activeSite,
            hubConnection = hubEvents,
            themePreferences = themePreferences,
            languagePreferences = languagePreferences,
            deviceRegistrar = deviceRegistrar,
            notificationPermissionChecker = notificationPermissionChecker,
            batteryOptimizationChecker = batteryOptimizationChecker,
            autostartAdvisor = autostartAdvisor,
            ioDispatcher = dispatcher,
        )

    /** [FakeNotificationPermissionChecker]'s own shape, restated for [BatteryOptimizationChecker] -
     * starts at whatever [initial] says, and only ever changes when [SettingsViewModel
     * .refreshBatteryOptimization] asks again. */
    private class FakeBatteryOptimizationChecker(
        var initial: Boolean = true,
    ) : BatteryOptimizationChecker {
        var answer: Boolean = initial

        override fun isIgnoringBatteryOptimizations(): Boolean = answer
    }

    /** A fixed answer for both of [AutostartAdvisor]'s methods - this class is a manufacturer-based
     * guess in production ([ManufacturerAutostartAdvisor][ago.chat.android.devices.ManufacturerAutostartAdvisor]),
     * never re-read for the life of a process, so this fake has no reason to change its answer either. */
    private class FakeAutostartAdvisor(
        private val status: DeviceModeStatus = DeviceModeStatus.Ok,
        private val target: AutostartSettingsTarget = AutostartSettingsTarget.None,
    ) : AutostartAdvisor {
        override fun recommendation(): DeviceModeStatus = status

        override fun settingsTarget(): AutostartSettingsTarget = target
    }

    /** `26-18`: [SignInViewModelTest][ago.chat.android.signin.SignInViewModelTest]'s own
     * `FakeDeviceRegistrar`, restated - this file's own name for it, since a `private class` cannot be
     * shared across two test files without widening its visibility for no other reason. */
    private class FakeSettingsDeviceRegistrar(
        availability: PushAvailability? = null,
    ) : DeviceRegistrar {
        override val pushAvailability = MutableStateFlow(availability)

        override suspend fun registerThisDevice(): Boolean = true
    }

    /** Starts at whatever [initial] says, and only ever changes when [SettingsViewModel
     * .refreshNotificationPermission] asks again - proving that class never re-reads on its own. */
    private class FakeNotificationPermissionChecker(
        var initial: Boolean = true,
    ) : NotificationPermissionChecker {
        var answer: Boolean = initial
        var calls: Int = 0
            private set

        override fun areNotificationsEnabled(): Boolean {
            calls++
            return answer
        }
    }

    private class InMemoryActiveSite(
        private var siteId: String? = null,
    ) : ActiveSiteSelection {
        override fun currentSiteId(): String? = siteId

        override fun select(siteId: String?) {
            this.siteId = siteId
        }
    }

    private class FakeIdentityApi(
        private val tenancies: TenancyListing,
    ) : IdentityApi {
        override suspend fun listMyTenancies(): TenancyListing = tenancies

        override suspend fun probeOperatorSeat(): ProbeOutcome = error("not used by this screen")

        override suspend fun probeOwnerEligibility(): ProbeOutcome = error("not used by this screen")
    }

    private class FakeThemePreferences(
        initial: ThemeMode = ThemeMode.System,
    ) : ThemePreferences {
        val current = MutableStateFlow(initial)
        override val mode: Flow<ThemeMode> = current

        override suspend fun setMode(mode: ThemeMode) {
            current.value = mode
        }
    }

    /** `26-92`: [FakeThemePreferences]'s own shape, plus [writes] - a plain counter, incremented only
     * once [current] has already been updated, so a test can tell whether a collector observed an event
     * before or after the write it is meant to follow (`setLanguage emits on languageApplied only after
     * the preference write lands`'s own reason for needing this at all). */
    private class FakeAppLanguagePreferences(
        initial: AppLanguage = AppLanguage.System,
    ) : AppLanguagePreferences {
        val current = MutableStateFlow(initial)
        override val language: Flow<AppLanguage> = current

        var writes: Int = 0
            private set

        override suspend fun setLanguage(language: AppLanguage) {
            current.value = language
            writes++
        }
    }

    private class FakeHubEvents : OperatorHubEvents {
        override val state = MutableStateFlow<OperatorHubConnectionState>(OperatorHubConnectionState.Connected)
        override val messages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 1)
        override val allMessages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 1)
        override val assignments = MutableSharedFlow<ConversationAssignedDto>(extraBufferCapacity = 1)
        override val messageDelivered = MutableSharedFlow<MessageDeliveredDto>(extraBufferCapacity = 1)
        override val teamMessages = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 1)
        override val teamMessageRemovals = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 1)

        var reconnectCalls: Int = 0
            private set

        override suspend fun joinConversation(conversationId: String): HistoryPage = error("not used by this screen")

        override fun leaveConversation() = error("not used by this screen")

        override suspend fun loadOlderHistory(
            conversationId: String,
            beforeSequence: Long,
            pageSize: Int,
        ): HistoryPage = error("not used by this screen")

        override suspend fun sendMessage(
            conversationId: String,
            body: String,
            clientMessageId: String,
            attachmentId: String?,
        ): SendMessageResult = error("not used by this screen")

        override suspend fun reconnectToActiveSite() {
            reconnectCalls++
        }

        override suspend fun getTeamHistory(
            beforeSequence: Long?,
            pageSize: Int,
        ): TeamHistoryPage = error("not used by this screen")

        override suspend fun getTeamDelta(afterSequence: Long): TeamHistoryPage = error("not used by this screen")

        override suspend fun sendTeamMessage(
            body: String,
            clientMessageId: String,
        ): SendMessageResult = error("not used by this screen")

        override suspend fun removeTeamMessage(teamMessageId: String) = error("not used by this screen")
    }
}
