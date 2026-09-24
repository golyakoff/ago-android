package ago.chat.android

import ago.chat.android.devices.EXTRA_OPEN_CONVERSATION_ID
import ago.chat.android.presence.OperatorPresenceController
import ago.chat.android.session.OidcConfig
import ago.chat.android.session.appLanguageDataStore
import ago.chat.android.session.toAppLanguage
import ago.chat.android.shell.PendingConversationOpener
import ago.chat.android.signin.SignInHost
import ago.chat.android.signin.SignInViewModel
import ago.chat.android.ui.language.wrapContextForLanguage
import ago.chat.android.ui.theme.AgoChatTheme
import ago.chat.android.ui.theme.ThemeMode
import ago.chat.android.ui.theme.ThemePreferences
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * The only `Activity` in the app (`docs/architecture.md`, "Module layout"), and now the first one
 * with something real in it: `26-12`'s sign-in flow.
 *
 * Three Android-specific jobs live here and nowhere else, because each of them is something a
 * `ViewModel` genuinely cannot do:
 *
 * 1. **Launching the Custom Tab.** AppAuth hands back an `Intent`; only an `Activity` can start it
 *    for a result. The view model emits the intent and this collects it, so the decision to sign in
 *    stays testable and only the launch is here.
 * 2. **Receiving the redirect.** `ago-android://callback` is caught by AppAuth's own
 *    `RedirectUriReceiverActivity` (declared in the library's manifest, with the scheme supplied by
 *    `app/build.gradle.kts`'s `appAuthRedirectScheme` placeholder), which completes this
 *    `ActivityResultLauncher`. There is no callback *screen* — `scope-inventory.md`'s own reading of
 *    `/callback` as "a mechanism, not a screen" ports exactly. `26-93`'s `ago-android://logout-callback`
 *    is caught by the identical activity (its own filter matches the whole scheme, not one path) and
 *    completes [signOutLauncher] instead — a second `ActivityResultLauncher` rather than a branch
 *    inside this one, because the two round trips resolve to two different view-model calls
 *    ([SignInViewModel.onAuthorizationResult] vs. [SignInViewModel.onSignOutResult]).
 * 3. **Leaving the app** for the web console, which is an `ACTION_VIEW` and therefore a `Context`.
 *
 * `26-18` adds two more, both real Android jobs no `ViewModel` can do either:
 *
 * 4. **Receiving a notification tap.** [handleIntent] reads [EXTRA_OPEN_CONVERSATION_ID] out of
 *    whichever `Intent` this `Activity` was started or resumed with and hands the id to
 *    [pendingConversationOpener] - see [onNewIntent]'s own doc comment for why both `onCreate` and
 *    `onNewIntent` have to call it.
 * 5. **The `POST_NOTIFICATIONS` runtime permission request itself** - `ActivityResultContracts
 *    .RequestPermission()` needs an `Activity`, and [SignInViewModel.requestNotificationPermissionEvents]'s
 *    own doc comment states why the *decision* to ask still lives in the view model rather than here.
 *
 * `26-85` adds a sixth, the identical "only an `Activity` can launch this" shape as (5):
 *
 * 6. **The battery-optimisation exemption request.** `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 *    is launched here, from [presenceController]'s own event, on the identical
 *    `registerForActivityResult`/`repeatOnLifecycle` shape the notification-permission launcher above
 *    already establishes - see [OperatorPresenceController.requestBatteryOptimizationExemptionEvents]'s
 *    own doc comment for why the *decision* to ask lives in `OperatorPresenceController` instead.
 *
 * `26-92` adds a seventh, of a different shape than the first six: not a launch this class defers to a
 * view model, but a pre-`onCreate` step nothing else in the app is positioned to take.
 *
 * 7. **Applying the persisted interface language before the first `Resources` lookup.** [attachBaseContext]'s
 *    own doc comment has the full account of why this, and not `AppCompatDelegate`, and why it has to run
 *    this early.
 */
@AndroidEntryPoint
public class MainActivity : ComponentActivity() {
    private val viewModel: SignInViewModel by viewModels()

    /** Injected rather than read from `BuildConfig` here, so one module owns "which deployment". */
    @Inject
    public lateinit var oidcConfig: OidcConfig

    /** `26-17`: Тема's own single source of truth — see that interface's own doc comment. Field-injected
     * alongside [oidcConfig] rather than read inside a view model, because applying it is purely a
     * matter of which colour scheme `setContent` below builds, with no business logic in front of it. */
    @Inject
    public lateinit var themePreferences: ThemePreferences

    /** `26-18`: this `Activity`'s own side of [PendingConversationOpener] - see that interface's own doc
     * comment for the two other places that read the value this class writes. */
    @Inject
    public lateinit var pendingConversationOpener: PendingConversationOpener

    /** `26-85`: this `Activity`'s own side of the battery-optimisation request - see
     * [OperatorPresenceController.requestBatteryOptimizationExemptionEvents]'s own doc comment for the
     * decision that fires it. */
    @Inject
    public lateinit var presenceController: OperatorPresenceController

    private lateinit var authorizationLauncher: ActivityResultLauncher<Intent>
    private lateinit var signOutLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var batteryOptimizationLauncher: ActivityResultLauncher<Intent>

    /**
     * `26-92`: applies the persisted [ago.chat.android.ui.language.AppLanguage] before this - the app's
     * only `Activity` - resolves a single string resource, on API levels below 33 where nothing else
     * will. `ago.chat.android.ui.language.applyAppLanguage`'s own doc comment has the full account of why
     * this exists at all rather than a plain `AppCompatDelegate.setApplicationLocales` call: that call is
     * a proven dead end in this exact app (`docs/architecture.md`'s "Pinning the locale instrumented UI
     * tests render against", `26-94`), and on API 33+ the real platform facility applies the persisted
     * choice on its own with no help from this override.
     *
     * Reads [appLanguageDataStore] synchronously, with `runBlocking` - the one deliberate use of it in
     * this codebase. `attachBaseContext` has no `suspend` hook of any kind, and every `Activity`'s own
     * `Resources` are already built from whatever `Context` this method returns by the time
     * `super.onCreate()` runs - there is no later, asynchronous point at which "wrap the base `Context`
     * for this language" could still take effect. This is not [themePreferences]'s own pattern (a `Flow`,
     * collected reactively once `setContent` below is already running) because a colour scheme is Compose
     * state with nothing underneath it to pre-build, where a locale is a property of `Resources` itself.
     * A fresh [DataStore][androidx.datastore.core.DataStore] instance, not the Hilt-provided one
     * [ago.chat.android.di.AppModule.provideAppLanguageDataStore] injects - Hilt's field injection runs
     * from `onCreate` onward, which is already too late for this method's own job; the two instances read
     * and write the identical file regardless (that provider's own doc comment states why this is safe).
     */
    override fun attachBaseContext(newBase: Context) {
        val language = runBlocking { appLanguageDataStore(newBase).data.first().toAppLanguage() }
        super.attachBaseContext(wrapContextForLanguage(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        authorizationLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                // `result.data` is null when the operator dismissed the Custom Tab. That is not a
                // failure and the view model does not render it as one.
                viewModel.onAuthorizationResult(result.data)
            }

        // `26-93`: the end-session Custom Tab's own result launcher, the identical shape as
        // [authorizationLauncher] immediately above - `result.data` is null exactly as often here (the
        // operator dismissing the tab, or the redirect never arriving because the realm was
        // unreachable) and `onSignOutResult` does not treat that as a failure either, for the same
        // reason `viewModel.signOut`'s own doc comment states.
        signOutLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                viewModel.onSignOutResult(result.data)
            }

        // `26-18`: the result itself needs no handling here - `SettingsScreen` re-reads the live system
        // truth through `NotificationManagerCompat.areNotificationsEnabled()` on its own next resume
        // ([NotificationPermissionChecker]'s own doc comment on why a cached copy of this particular
        // answer would go stale), rather than this class remembering what the dialog returned.
        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

        // `26-85`: the identical "discard the result, re-read live system truth elsewhere if it ever
        // matters" shape [notificationPermissionLauncher] above already takes -
        // `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` gives no dependable result to read anyway
        // (`BatteryOptimizationGate`'s own doc comment), and nothing in this app currently re-checks
        // `PowerManager.isIgnoringBatteryOptimizations` after the fact.
        batteryOptimizationLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.authorizationRequests.collect { intent -> authorizationLauncher.launch(intent) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.signOutRequests.collect { intent -> signOutLauncher.launch(intent) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.requestNotificationPermissionEvents.collect {
                    // The permission (and the runtime prompt) only exist from API 33 - a no-op launch
                    // on an older phone would ask the system for a permission string it does not
                    // recognise, harmless but meaningless, so this class is the one place that checks
                    // the SDK level rather than pushing that check into the view model, which has no
                    // reason to know an API level at all.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                presenceController.requestBatteryOptimizationExemptionEvents.collect {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    try {
                        batteryOptimizationLauncher.launch(intent)
                    } catch (missing: ActivityNotFoundException) {
                        // A build with no Settings screen for this action at all - the identical
                        // "no crash, no loop" contract [openInBrowser]'s own catch already applies, for
                        // the identical reason: a missing system screen is not this app's bug to retry.
                    }
                }
            }
        }

        handleIntent(intent)

        setContent {
            // `26-17`: read fresh, every recomposition - a `setMode` call from the Settings screen
            // (a different `ViewModel`, a different part of the tree entirely) reaches this exact
            // `collectAsState` because both sides ultimately share the one `DataStore` instance
            // `di/AppModule` builds as a `@Singleton`, which is what makes "applied immediately, no
            // restart" true with no event bus of any kind between the two screens.
            val themeMode by themePreferences.mode.collectAsState(initial = ThemeMode.System)
            val systemIsDark = isSystemInDarkTheme()
            val darkTheme =
                when (themeMode) {
                    ThemeMode.System -> systemIsDark
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }

            AgoChatTheme(darkTheme = darkTheme) {
                val state by viewModel.state.collectAsState()
                val hubConnectionState by viewModel.hubConnectionState.collectAsState()
                SignInHost(
                    state = state,
                    hubConnectionState = hubConnectionState,
                    consoleUrl = oidcConfig.consoleUrl,
                    onSignIn = viewModel::beginSignIn,
                    onChooseSite = viewModel::chooseSite,
                    onRetry = viewModel::retry,
                    onSignOut = viewModel::signOut,
                    onOpenConsole = ::openInBrowser,
                )
            }
        }
    }

    /**
     * `26-18`: the manifest's own `android:launchMode="singleTop"` is what makes this override fire at
     * all for a second notification tap while this `Activity` is already the top of its task - without
     * it, `standard` launch mode (this app's default until now) would create a fresh `MainActivity`
     * instance instead, and this override would simply never run for that tap. `setIntent(intent)` keeps
     * `this.intent` in step with the one actually delivered, matching the platform's own documented
     * contract for `onNewIntent` (a future `getIntent()` call, or a configuration-change recreation,
     * must see the new `Intent`, not the one this instance launched with).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** `EXTRA_OPEN_CONVERSATION_ID`'s only reader - a plain extra, no deep-link `<intent-filter>` of any
     * kind (`PushNotificationPresenter.openConversationPendingIntent`'s own doc comment states why an
     * explicit `Intent` naming this class is the right shape for a notification this app itself posts,
     * as opposed to a link opened from outside it). Absent for every other way this `Activity` starts -
     * the launcher icon, the AppAuth redirect - so a blank/missing extra is the ordinary case, not an
     * error. */
    private fun handleIntent(intent: Intent) {
        val conversationId = intent.getStringExtra(EXTRA_OPEN_CONVERSATION_ID) ?: return
        pendingConversationOpener.open(conversationId)
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (missing: ActivityNotFoundException) {
            // A device with no browser at all, which is the only way this throws. Nothing useful to
            // offer in its place, and crashing on a link is worse than the link doing nothing — the
            // screen it sits on already says in words where the operator has to go. Swallowed here
            // rather than logged, because this class's whole neighbourhood is under a "no logging"
            // rule (`AgoAuthSession`) and one exception message is not worth carving one out for.
        }
    }
}
