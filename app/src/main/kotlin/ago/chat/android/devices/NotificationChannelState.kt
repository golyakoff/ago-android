package ago.chat.android.devices

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-19`: "Channel importance stays the OS's" — reads Android's own live truth for one channel, id by
 * id, behind a port for the identical rule-2 reason [NotificationPermissionChecker] already is one:
 * [NotificationSettingsViewModel] holding a `Context` (or a real `NotificationManagerCompat`) directly
 * would make it untestable on a plain JVM. **There is deliberately no `setImportance` on this
 * interface** — this item's own Scope: "Where Android owns a setting, the row deep-links into the
 * system channel settings rather than keeping a second, disagreeing copy of it".
 */
public interface NotificationChannelStateReader {
    /**
     * Android's own `NotificationManager.IMPORTANCE_*` constant for [channelId], or
     * [NotificationManagerCompat.IMPORTANCE_UNSPECIFIED] when the channel has not been created yet —
     * [ensureChannelsCreated] runs at every process start, so this case is expected only before that has
     * ever run once, never in steady state.
     */
    public fun importanceOf(channelId: String): Int
}

@Singleton
public class AndroidNotificationChannelStateReader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : NotificationChannelStateReader {
        override fun importanceOf(channelId: String): Int =
            NotificationManagerCompat.from(context).getNotificationChannelCompat(channelId)?.importance
                ?: NotificationManagerCompat.IMPORTANCE_UNSPECIFIED
    }

/**
 * The one pure decision drawn from [NotificationChannelStateReader.importanceOf]'s own result — a plain
 * function so [NotificationChannelStateTest] can assert every importance value Android defines with no
 * real `NotificationManager` anywhere in the test. `IMPORTANCE_NONE` is the only value Android itself
 * calls off; every other named level — including the pre-channel-creation
 * [NotificationManagerCompat.IMPORTANCE_UNSPECIFIED] — reads as on, matching [ensureChannelsCreated]'s own
 * default of `NotificationManager.IMPORTANCE_DEFAULT`.
 */
public fun channelImportanceIsOn(importance: Int): Boolean = importance != NotificationManagerCompat.IMPORTANCE_NONE
