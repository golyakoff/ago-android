package ago.chat.android

import ago.chat.android.realtime.OperatorHubConnectionLifecycle
import android.app.Application
import dagger.hilt.android.HiltAndroidApp
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
 */
@HiltAndroidApp
public class AgoChatApplication : Application() {
    @Inject
    public lateinit var hubConnectionLifecycle: OperatorHubConnectionLifecycle

    override fun onCreate() {
        super.onCreate()
        hubConnectionLifecycle.start()
    }
}
