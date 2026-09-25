package ago.chat.android

import ago.chat.android.conversations.ConversationListForegroundRefreshTrigger
import ago.chat.android.core.domain.devices.PushProvider
import ago.chat.android.devices.ProcessLifecycleForegroundTracker
import ago.chat.android.devices.TransportSelector
import ago.chat.android.devices.ensureChannelsCreated
import ago.chat.android.presence.ensurePresenceChannelCreated
import ago.chat.android.realtime.OperatorHubConnectionLifecycle
import android.app.ActivityManager
import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import dagger.hilt.android.HiltAndroidApp
import ru.rustore.sdk.pushclient.RuStorePushClient
import ru.rustore.sdk.pushclient.common.logger.DefaultLogger
import javax.inject.Inject

/**
 * `26-12`: the app's `Application` class exists for one reason — `@HiltAndroidApp`, which generates
 * the singleton component every `@AndroidEntryPoint` below it is injected from.
 *
 * Hilt is wired *here*, in `:app`, and nowhere else. That is the same rule `ago-root`'s `CLAUDE.md`
 * states for the backend — "hosts reference everything and are the only place where DI wiring
 * lives" — read onto the one module allowed to know about Android. `:core:network` and
 * `:core:domain` carry no Hilt annotation at all: they declare constructors and interfaces, and
 * `di/AppModule` is where those are turned into a graph. The alternative — `@Inject` constructors
 * in the core modules — would make both of them unusable without Hilt, which is exactly the coupling
 * `adr/0178`'s "converting to a KMP `commonMain` source set is a build-file change" depends on not
 * existing.
 *
 * `26-13`: also where [OperatorHubConnectionLifecycle] is started — once, for the process's whole
 * life, never per-screen (that class's own doc comment says why). A `@HiltAndroidApp` `Application`
 * supports field injection the same way an `@AndroidEntryPoint` `Activity` does, injected before this
 * `onCreate` body runs.
 *
 * `26-18`: also where [ProcessLifecycleForegroundTracker] is started, the identical "once, here,
 * never per-screen" reasoning applied to a second `ProcessLifecycleOwner` observer, and where
 * [ensureChannelsCreated] runs - eagerly, on every process start, for the reason that function's own
 * doc comment gives (idempotent by construction, so "eagerly" costs nothing measurable).
 *
 * `26-61`: also where [ConversationListForegroundRefreshTrigger] is started - a third
 * `ProcessLifecycleOwner` observer, for the identical "once, here, never per-screen" reason as the two
 * above it, so a genuine return from the background re-reads the conversation queue exactly once
 * regardless of how many screens the operator passes through on the way back in.
 *
 * `26-85`: also where [ensurePresenceChannelCreated] runs, eagerly, for the identical reason
 * [ensureChannelsCreated] immediately above it already does - `OperatorPresenceService` itself is
 * started later and conditionally (`OperatorPresenceController`, gated on `conversation:send`), but its
 * notification channel is cheap to create unconditionally on every process start, the same "idempotent,
 * so eager costs nothing" reasoning both functions' own doc comments state.
 *
 * `26-06`/`adr/0180`: also where `RuStorePushClient.init` runs — **manual**, not the manifest
 * meta-data path RuStore's own docs also offer, so the project id can be a `BuildConfig` field
 * (`agoProperty`'s own pattern every other deployment value in `app/build.gradle.kts` already follows)
 * rather than a second, parallel way of naming the same value. **Main process only** — RuStore's own
 * documentation states the SDK does not support multi-process initialisation, and [isMainProcess]
 * checks for it even though this manifest declares no `android:process` anywhere today: cheap
 * insurance against a future process split silently double-initialising the SDK, not a symptom of one
 * existing now. `DefaultLogger()` — the SDK's own logcat-backed implementation — is passed
 * deliberately, not a custom wrapper: `docs/backlog/26-06-*.md`'s own Done-when asks that "no push
 * token appears in logcat… checked with the SDK's own default logger active, since that is what a
 * developer will actually be running" — a filtering logger substituted here would make that check
 * meaningless rather than pass it honestly.
 *
 * `26-100`/`adr/0181`: also where `FirebaseApp.initializeApp` runs, manually, for the identical
 * "`BuildConfig` field, not a second parallel way of naming the same value" reason — `FirebaseOptions`
 * built from `agoFcmProperty`'s four fields (`app/build.gradle.kts`'s own doc comment) rather than a
 * committed `google-services.json`, which would be a secret-shaped file this public repo cannot hold
 * even though its contents are not secret. Gated on [TransportSelector.selectedProvider] rather than run
 * unconditionally like RuStore's own `init` above: initialising Firebase with an empty-string
 * `FirebaseOptions` (a checkout with none of the four `local.properties` values set) has nothing useful
 * to do, and RuStore already covers every device this branch skips.
 */
@HiltAndroidApp
public class AgoChatApplication : Application() {
    @Inject
    public lateinit var hubConnectionLifecycle: OperatorHubConnectionLifecycle

    @Inject
    public lateinit var appForegroundTracker: ProcessLifecycleForegroundTracker

    @Inject
    public lateinit var conversationListForegroundRefreshTrigger: ConversationListForegroundRefreshTrigger

    @Inject
    public lateinit var transportSelector: TransportSelector

    override fun onCreate() {
        super.onCreate()
        hubConnectionLifecycle.start()
        appForegroundTracker.start()
        conversationListForegroundRefreshTrigger.start()
        ensureChannelsCreated(this)
        // `26-85`: `OperatorPresenceService`'s own third channel - see that function's own doc comment
        // for why it is not one more arm on `ensureChannelsCreated` above.
        ensurePresenceChannelCreated(this)

        if (isMainProcess()) {
            RuStorePushClient.init(
                application = this,
                projectId = BuildConfig.AGO_RUSTORE_PUSH_PROJECT_ID,
                logger = DefaultLogger(),
            )

            // `26-100` crash guard: never call FirebaseOptions with a blank applicationId — it throws
            // `IllegalArgumentException: ApplicationId must be set` and takes the whole app down at
            // startup (the `0.31.0` crash). The identifiers now carry real committed defaults, so this is
            // belt-and-suspenders: a build that somehow ships them empty degrades to the RuStore path
            // instead of crashing.
            if (transportSelector.selectedProvider() == PushProvider.Fcm &&
                BuildConfig.AGO_FCM_APPLICATION_ID.isNotBlank() &&
                BuildConfig.AGO_FCM_PROJECT_ID.isNotBlank()
            ) {
                FirebaseApp.initializeApp(
                    this,
                    FirebaseOptions
                        .Builder()
                        .setProjectId(BuildConfig.AGO_FCM_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.AGO_FCM_PROJECT_NUMBER)
                        .setApplicationId(BuildConfig.AGO_FCM_APPLICATION_ID)
                        .setApiKey(BuildConfig.AGO_FCM_API_KEY)
                        .build(),
                )
            }
        }
    }

    /** `getSystemService(Class)` rather than the `Any?`-returning legacy overload — `allWarningsAsErrors`
     * (`app/build.gradle.kts`) would turn the unchecked cast the legacy overload needs into a build
     * failure, the same reasoning `provideAgoChatDatabase`'s own comment gives for a modern overload
     * elsewhere in this codebase. `runningAppProcesses` returning `null` (a low-memory device may refuse
     * to answer) is read as "this is the main process" rather than as "unknown" — an app with no
     * declared secondary process, which this manifest is, can only ever be running as its own main
     * process regardless of what this query answers. */
    private fun isMainProcess(): Boolean {
        val pid = android.os.Process.myPid()
        val processName =
            getSystemService(ActivityManager::class.java)
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        return processName == null || processName == packageName
    }
}
