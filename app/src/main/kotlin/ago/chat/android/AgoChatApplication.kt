package ago.chat.android

import ago.chat.android.realtime.OperatorHubConnectionLifecycle
import android.app.ActivityManager
import android.app.Application
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
 */
@HiltAndroidApp
public class AgoChatApplication : Application() {
    @Inject
    public lateinit var hubConnectionLifecycle: OperatorHubConnectionLifecycle

    override fun onCreate() {
        super.onCreate()
        hubConnectionLifecycle.start()

        if (isMainProcess()) {
            RuStorePushClient.init(
                application = this,
                projectId = BuildConfig.AGO_RUSTORE_PUSH_PROJECT_ID,
                logger = DefaultLogger(),
            )
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
