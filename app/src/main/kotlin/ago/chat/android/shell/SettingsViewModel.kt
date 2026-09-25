package ago.chat.android.shell

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.identity.IdentityApi
import ago.chat.android.core.domain.identity.TenancyListing
import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.devices.AutostartAdvisor
import ago.chat.android.devices.AutostartSettingsTarget
import ago.chat.android.devices.BatteryOptimizationChecker
import ago.chat.android.devices.DeviceModeStatus
import ago.chat.android.devices.DeviceRegistrar
import ago.chat.android.devices.NotificationPermissionChecker
import ago.chat.android.devices.PushAvailability
import ago.chat.android.di.IoDispatcher
import ago.chat.android.ui.language.AppLanguage
import ago.chat.android.ui.language.AppLanguagePreferences
import ago.chat.android.ui.theme.ThemeMode
import ago.chat.android.ui.theme.ThemePreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-17`: the Settings screen's own state — genuinely separate concerns sharing one view model because
 * they share one screen, not because they share any data: [themeMode] (local, no network),
 * [tenancies]/[currentSiteId] (a site switcher fed by the identical [IdentityApi] port
 * [ago.chat.android.signin.SignInViewModel] already calls through
 * [ago.chat.android.core.domain.identity.PostSignInRouter]), and О приложении/Выход, which need no
 * state at all — this class's own report reads [BuildConfig][ago.chat.android.BuildConfig] directly and
 * `SettingsRoute` forwards `onSignOut` straight through, unchanged, the same way every pre-session
 * screen already does. `26-92` adds a fourth, [language] — local, no network, the identical shape
 * [themeMode] already is, except that *applying* it needs a real Android call
 * ([ago.chat.android.ui.language.applyAppLanguage]) this class deliberately does not make itself — see
 * [languageApplied]'s own doc comment for why that step stays in `SettingsRoute`, which has a `Context`
 * to make it with and this class, by the dependency rule, does not.
 *
 * ## The active-site switch's own two halves
 *
 * [switchSite] performs both, in order: [ActiveSiteSelection.select] first (the REST header's single
 * source of truth, `ActiveSiteHeaderPlugin` reads it fresh on every request) and
 * [OperatorHubEvents.reconnectToActiveSite] second (the hub connection's own query-string parameter,
 * otherwise frozen at whatever site was active the first time the connection was ever built). Both are
 * awaited before [siteSwitched] fires, so a caller that reacts to that event — `SettingsRoute`, which
 * returns to Диалоги — only ever does so once both halves have actually moved, never while the hub is
 * still mid-reconnect against the old site.
 */
@HiltViewModel
public class SettingsViewModel
    @Inject
    constructor(
        private val identity: IdentityApi,
        private val activeSite: ActiveSiteSelection,
        private val hubConnection: OperatorHubEvents,
        private val themePreferences: ThemePreferences,
        private val languagePreferences: AppLanguagePreferences,
        private val deviceRegistrar: DeviceRegistrar,
        private val notificationPermissionChecker: NotificationPermissionChecker,
        private val batteryOptimizationChecker: BatteryOptimizationChecker,
        private val autostartAdvisor: AutostartAdvisor,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        public val themeMode: StateFlow<ThemeMode> =
            themePreferences.mode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.System)

        /** `26-92`: the Язык row's own current selection — the identical [themeMode] shape, one line
         * up, ported for a second, unrelated preference. */
        public val language: StateFlow<AppLanguage> =
            languagePreferences.language.stateIn(viewModelScope, SharingStarted.Eagerly, AppLanguage.System)

        /**
         * `26-92`: fires once [AppLanguagePreferences.setLanguage] has actually persisted the new
         * choice — not before, and not as a side effect [setLanguage] performs directly — so
         * [ago.chat.android.shell.SettingsRoute]'s own collector never calls
         * [ago.chat.android.ui.language.applyAppLanguage] (which, below API 33, recreates the current
         * `Activity`) against a value [ago.chat.android.MainActivity.attachBaseContext] might still read
         * back as the *previous* one on the very next cold start that recreation triggers. A one-shot
         * `Channel`, not a `StateFlow`, for the identical reason [siteSwitched] is one: a `StateFlow`
         * would redeliver the same value to a screen recreated after a configuration change, and the
         * `Activity.recreate()` this event can itself cause is exactly such a change.
         */
        private val languageAppliedEvents = Channel<AppLanguage>(Channel.BUFFERED)
        public val languageApplied: Flow<AppLanguage> = languageAppliedEvents.receiveAsFlow()

        /** `26-18`: "`checkPushAvailability()` returning `Unavailable` produces a state the operator can
         * act on" - a plain relay onto [DeviceRegistrar.pushAvailability], the identical shape
         * [ago.chat.android.signin.SignInViewModel.pushAvailability] already establishes for the same
         * port; that class's own doc comment named this exact screen ("a future screen (`26-19`)") as
         * where a real binding would eventually land - it lands here instead, one item early, because
         * `26-18`'s own Scope asks for "a banner or a Settings row" now rather than waiting for `26-19`'s
         * dedicated screen. */
        public val pushAvailability: StateFlow<PushAvailability?> = deviceRegistrar.pushAvailability

        private val mutableNotificationsEnabled = MutableStateFlow(notificationPermissionChecker.areNotificationsEnabled())

        /** `26-18`: "Denying `POST_NOTIFICATIONS` leaves the app usable and states what it can no longer
         * do" - read once at construction and again on every [refreshNotificationPermission] call
         * (`SettingsRoute`'s own `ON_RESUME` observer), never cached beyond that: the one fact this
         * reports can change from outside the app entirely (system Settings), so a value read once at
         * `init` and never again would go stale the moment an operator backgrounds this screen, changes
         * it, and comes back. */
        public val notificationsEnabled: StateFlow<Boolean> = mutableNotificationsEnabled.asStateFlow()

        private val mutableBatteryUnrestricted = MutableStateFlow(batteryOptimizationChecker.isIgnoringBatteryOptimizations())

        /** `26-128`: Settings → «Режим работы»'s own live value - the identical "read once at
         * construction, re-read on [refreshBatteryOptimization]" shape [notificationsEnabled] above
         * already is, for the identical reason: an operator can flip this from system Settings while this
         * screen is backgrounded. */
        public val batteryUnrestricted: StateFlow<Boolean> = mutableBatteryUnrestricted.asStateFlow()

        /** `26-128`: Settings → «Автозапуск»'s own status - never re-read, unlike [batteryUnrestricted]
         * above: [AutostartAdvisor.recommendation] is a pure function of [android.os.Build.MANUFACTURER],
         * which cannot change for the life of this process, so there is nothing an `ON_RESUME` refresh
         * could ever pick up that `init` did not already see. */
        public val autostartStatus: DeviceModeStatus = autostartAdvisor.recommendation()

        /** `26-128`: where «Настройки автозапуска» leads - `SettingsRoute`'s own click handler reads this
         * to build the `Intent`, since building it needs a `Context` this `ViewModel` may never hold
         * (rule 2). */
        public val autostartSettingsTarget: AutostartSettingsTarget = autostartAdvisor.settingsTarget()

        private val mutableTenancies = MutableStateFlow<TenancyListing>(TenancyListing.Known(emptyList()))
        public val tenancies: StateFlow<TenancyListing> = mutableTenancies.asStateFlow()

        private val mutableCurrentSiteId = MutableStateFlow(activeSite.currentSiteId())
        public val currentSiteId: StateFlow<String?> = mutableCurrentSiteId.asStateFlow()

        private val mutableSwitching = MutableStateFlow(false)

        /** Whether [switchSite] is between its two writes and [siteSwitched] firing — `SettingsScreen`'s
         * own cue to disable the switcher rather than let a second tap start a second reconnect. */
        public val switching: StateFlow<Boolean> = mutableSwitching.asStateFlow()

        /** A one-shot event, not a `StateFlow`: `SettingsRoute`'s own "return to Диалоги" is a
         * navigation action, and a `StateFlow` re-delivering the same value to a screen recreated after
         * a rotation would fire that navigation a second time for free — the identical reasoning
         * [ago.chat.android.signin.SignInViewModel.authorizationRequests] already states for its own
         * `Channel`. */
        private val switchedEvents = Channel<String>(Channel.BUFFERED)

        /** Carries the *new* site id — not merely a signal — so a collector never has to race this
         * class's own [currentSiteId] to learn which site just became active. */
        public val siteSwitched: Flow<String> = switchedEvents.receiveAsFlow()

        init {
            loadTenancies()
        }

        public fun setThemeMode(mode: ThemeMode) {
            viewModelScope.launch { themePreferences.setMode(mode) }
        }

        /** `26-92`: persists first, then signals [languageApplied] — see that property's own doc comment
         * for why the order is load-bearing rather than incidental. */
        public fun setLanguage(language: AppLanguage) {
            viewModelScope.launch {
                languagePreferences.setLanguage(language)
                languageAppliedEvents.send(language)
            }
        }

        /** `SettingsRoute`'s own `ON_RESUME` call - see [notificationsEnabled]'s own doc comment for why
         * this needs re-reading rather than trusting the value [init] captured once. */
        public fun refreshNotificationPermission() {
            mutableNotificationsEnabled.value = notificationPermissionChecker.areNotificationsEnabled()
        }

        /** `SettingsRoute`'s own `ON_RESUME` call, alongside [refreshNotificationPermission] - see
         * [batteryUnrestricted]'s own doc comment for why this needs re-reading rather than trusting the
         * value [init] captured once. */
        public fun refreshBatteryOptimization() {
            mutableBatteryUnrestricted.value = batteryOptimizationChecker.isIgnoringBatteryOptimizations()
        }

        private fun loadTenancies() {
            viewModelScope.launch {
                mutableTenancies.value = withContext(ioDispatcher) { identity.listMyTenancies() }
            }
        }

        /**
         * A no-op for the currently-active site (nothing to switch to) and while [switching] is already
         * `true` (no second reconnect stacked on top of one already running) — both are UI-level
         * guards `SettingsScreen` also expresses by disabling the row, restated here so this class's own
         * contract does not depend on the screen actually doing so.
         */
        public fun switchSite(siteId: String) {
            if (siteId == mutableCurrentSiteId.value || mutableSwitching.value) return

            viewModelScope.launch {
                mutableSwitching.value = true
                try {
                    withContext(ioDispatcher) {
                        activeSite.select(siteId)
                        hubConnection.reconnectToActiveSite()
                    }
                    mutableCurrentSiteId.value = siteId
                    switchedEvents.send(siteId)
                } finally {
                    mutableSwitching.value = false
                }
            }
        }
    }
