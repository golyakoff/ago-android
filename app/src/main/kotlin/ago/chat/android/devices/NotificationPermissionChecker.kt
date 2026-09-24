package ago.chat.android.devices

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-18`: "Denying `POST_NOTIFICATIONS` leaves the app usable and states what it can no longer do" -
 * `SettingsScreen`'s own read of the live system truth, behind a port for the identical reason every
 * other framework call in this app sits behind one (rule 2, read onto an Android client): a `ViewModel`
 * holding a `Context` directly is untestable on a plain JVM.
 *
 * **`NotificationManagerCompat.areNotificationsEnabled()`, not a bare `POST_NOTIFICATIONS` runtime-
 * permission check.** The runtime permission only exists from API 33; this app's own `minSdk` is 26, and
 * on every version below 33 an operator can still turn notifications off for the whole app from system
 * Settings, which `areNotificationsEnabled()` answers correctly and a permission check alone would
 * silently miss. [SystemPushNotificationPresenter] gates on the identical call for the identical reason.
 *
 * **Read fresh on every call, never cached.** The one fact this port exists to report can change from
 * outside the app entirely - an operator opening system Settings and flipping the switch there - so a
 * value this class remembered from an earlier call would go stale the moment that happens, silently.
 * `SettingsViewModel`'s own doc comment on [ago.chat.android.shell.SettingsViewModel.refreshNotificationPermission]
 * states where this gets re-read from.
 */
public interface NotificationPermissionChecker {
    public fun areNotificationsEnabled(): Boolean
}

@Singleton
public class AndroidNotificationPermissionChecker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : NotificationPermissionChecker {
        override fun areNotificationsEnabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
