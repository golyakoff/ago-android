package ago.chat.android.session

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's one answer to "which tenancy is this request acting in" — `ago-console`'s
 * `src/api/activeSite.ts` with a disk behind it instead of a module-level `let`.
 *
 * **Read straight through to the store, with no in-memory cache.** A cache would be a second copy of
 * a value whose whole job is to be single, and it would need invalidating the day `26-17`'s switcher
 * writes it from another screen. What the read actually costs is worth stating rather than assuming:
 * `SharedPreferences` holds the whole file in memory after it is first loaded, so only the *first*
 * call touches disk and every later one is a map lookup plus one short Tink decrypt — microseconds,
 * per request, off the main thread.
 *
 * That first call is the one worth placing deliberately, and it is: the Ktor plugin runs on the
 * client's own dispatcher, and `SignInViewModel` runs the router on an IO dispatcher explicitly
 * (that class's own remarks say so) rather than on `viewModelScope`'s `Main.immediate`.
 */
@Singleton
public class AgoActiveSite
    @Inject
    constructor(
        private val store: SessionStore,
    ) : ActiveSiteSelection {
        override fun currentSiteId(): String? = store.activeSiteId

        override fun select(siteId: String?) {
            store.activeSiteId = siteId
        }
    }
