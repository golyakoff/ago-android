package ago.chat.android.devices

import kotlinx.coroutines.flow.Flow
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-19`: "Quiet hours, client-side only" — a time-of-day range in the phone's own local time zone,
 * never UTC and never sent to the server (`docs/backlog/26-19-*.md`'s own Scope: "Not on the server: the
 * phone already has Do Not Disturb, a server-side schedule would be a less capable copy of an OS feature,
 * and it would introduce a timezone question for a rule nobody has asked for" — `adr/0179`'s own "What
 * this design deliberately leaves out").
 *
 * Minutes since local midnight (`0..1439`), not [java.time.LocalTime] itself, purely so this is a plain
 * `data class` two `Int` preferences can store with no serializer of its own to write or test.
 * [startMinuteOfDay] > [endMinuteOfDay] is a real, valid state (a range crossing midnight, e.g. 22:00 to
 * 07:00) that [suppressesAt] resolves, not an error case to reject at construction.
 *
 * **Disabled by default.** A fresh install must not start silently swallowing pushes at some default
 * hour the operator never chose — the identical "the app still works, and does not surprise" instinct
 * this codebase's other silent-until-asked defaults already follow ([ThemeMode.System], `26-18`'s own
 * default-importance channels).
 */
public data class QuietHoursSettings(
    public val enabled: Boolean = false,
    public val startMinuteOfDay: Int = DEFAULT_START_MINUTE,
    public val endMinuteOfDay: Int = DEFAULT_END_MINUTE,
) {
    public companion object {
        public const val DEFAULT_START_MINUTE: Int = 22 * 60
        public const val DEFAULT_END_MINUTE: Int = 8 * 60
    }
}

/**
 * The one pure decision this feature makes — [IncomingPushRouter]'s own fourth check, alongside parse/
 * dedupe/[decideAlert]. Tested as a plain function over explicit minute-of-day instants
 * ([QuietHoursTest]) rather than by driving a real clock or scheduler through simulated time: this
 * session's own established lesson is that a periodic/polling coroutine test can spin forever when code
 * re-schedules itself in a loop, where asserting a pure function directly over chosen instants cannot.
 *
 * Handles the wraparound a quiet-hours range crossing midnight requires: when [QuietHoursSettings
 * .startMinuteOfDay] is *after* [QuietHoursSettings.endMinuteOfDay] (22:00-07:00), the suppressed window
 * is "from start to midnight, or from midnight to end" rather than the ordinary "between the two" test
 * that is only correct when the range does not cross midnight.
 */
public fun QuietHoursSettings.suppressesAt(nowMinuteOfDay: Int): Boolean {
    if (!enabled) return false
    return if (startMinuteOfDay <= endMinuteOfDay) {
        nowMinuteOfDay in startMinuteOfDay until endMinuteOfDay
    } else {
        nowMinuteOfDay >= startMinuteOfDay || nowMinuteOfDay < endMinuteOfDay
    }
}

/**
 * [IncomingPushRouter]'s own read of "what time is it right now, in local minutes-since-midnight" — a
 * port for the identical reason [NotificationPermissionChecker] is one (rule 2, read onto an Android
 * client): `java.time.LocalTime.now()` inside a plain class would make every quiet-hours router test
 * depend on the real wall clock at the moment it happens to run, rather than on an instant the test
 * itself chooses.
 */
public interface LocalClock {
    public fun currentMinuteOfDay(): Int
}

@Singleton
public class SystemLocalClock
    @Inject
    constructor() : LocalClock {
        override fun currentMinuteOfDay(): Int {
            val now = LocalTime.now()
            return now.hour * 60 + now.minute
        }
    }

/**
 * `26-19`'s own quiet-hours settings, persisted — the identical `androidx.datastore` shape
 * [ago.chat.android.ui.theme.ThemePreferences] already establishes for a UI preference, declared here
 * rather than in `:core:domain` for the identical reason that interface's own doc comment states: nothing
 * outside this UI/device layer ever needs to read it.
 */
public interface QuietHoursPreferences {
    public val settings: Flow<QuietHoursSettings>

    public suspend fun setSettings(settings: QuietHoursSettings)
}
