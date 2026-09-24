package ago.chat.android.devices

import ago.chat.android.MainActivity
import ago.chat.android.R
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The `Intent` extra `AgoPushMessagingService` and `MainActivity` agree on - a tap opens exactly this
 * conversation's thread, never the list (`docs/backlog/26-18-*.md`'s own Scope). Declared here, next to
 * the one place that writes it, rather than in `MainActivity` itself: the presenter is the producer, the
 * `Activity` only ever reads it back. */
public const val EXTRA_OPEN_CONVERSATION_ID: String = "ago.chat.android.EXTRA_OPEN_CONVERSATION_ID"

/**
 * `26-18`: the one Android-specific side effect the whole item's decision logic feeds into - build (or
 * skip) a system notification. Everything upstream of this interface ([parseIncomingPush], [decideAlert],
 * [PushMessageDedupeStore]) is plain Kotlin precisely so this is the only seam that needs the real
 * `NotificationManagerCompat`/`Context` to test at all, the identical "the decision is a pure function,
 * the effect is a small adapter" split `alerts.ts`/`useAlerts.ts` themselves establish
 * (`docs/backlog/26-18-*.md`'s own Scope names both files as what is being ported).
 */
public interface PushNotificationPresenter {
    /** Shows the one notification [event] warrants. A no-op, not a crash, when the operator has turned
     * notifications off for this app - see [SystemPushNotificationPresenter]'s own doc comment. */
    public fun present(event: IncomingPush)
}

/**
 * **Never the message body, and never the visitor's identity either.** `alertTextFor`'s own console-side
 * text at least names a truncated visitor id; this app's own `docs/backlog/26-18-*.md` goes one step
 * further and asks for neither - "the notification's title/text should describe the kind of event...
 * never the actual message content". `RuStorePushSender.BuildData` folds English `title`/`body` strings
 * onto the wire (`ago-chat`'s own `NotifyOperatorDevicesHandler`) for a reason that has nothing to do with
 * this app: RuStore's send API always accepts them in `data`, and no other consumer of that map exists
 * today. **This class deliberately never reads `data["title"]`/`data["body"]`** - they are the wrong
 * language for this app's own Russian locale (`26-10`) and the wrong shape for this item's own privacy
 * rule regardless of language, so the title and body shown here are this app's own [PushNotificationChannel]-
 * keyed string resources, naming only the *kind* of event.
 *
 * **Skips silently, not a crash, when notifications are off.** `NotificationManagerCompat.
 * areNotificationsEnabled()` is the one check that is correct across every API level this app supports
 * (`minSdk = 26`): unlike a bare `POST_NOTIFICATIONS` runtime-permission check, which only exists from API
 * 33, this also answers `false` when the operator disabled notifications for the whole app from system
 * Settings on an older phone - the identical "the app still works, and says what stopped working
 * elsewhere" contract `docs/backlog/26-18-*.md`'s own Done-when asks for, with the "says what stopped
 * working" half living in `SettingsScreen` (`26-18`'s own addition there) rather than here, since a
 * background `Service` has no screen to say anything on.
 */
@Singleton
public class SystemPushNotificationPresenter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PushNotificationPresenter {
        override fun present(event: IncomingPush) {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Log.i(TAG, "notifications disabled for this app - skipping")
                return
            }

            val channel = PushNotificationChannel.forEvent(event)
            val (titleRes, textRes) =
                when (event) {
                    is IncomingPush.ConversationAssigned -> R.string.push_title_assigned to R.string.push_text_assigned
                    is IncomingPush.VisitorMessage -> R.string.push_title_message to R.string.push_text_message
                }

            val notification =
                NotificationCompat
                    .Builder(context, channel.id)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(titleRes))
                    .setContentText(context.getString(textRes))
                    .setAutoCancel(true)
                    .setContentIntent(openConversationPendingIntent(event.conversationId))
                    .build()

            // `26-18`'s own Scope: "the notification tag `ago-conversation-{conversationId}`" - matching
            // `useAlerts.ts`'s own `Notification` `tag` exactly, so a redelivered push for the same
            // conversation replaces its own card (`NotificationManagerCompat.notify(tag, id, ...)`'s own
            // documented collapse-by-tag behaviour) rather than stacking a second one. `id` is a plain
            // constant because the tag alone already makes every notification this app ever posts unique
            // per conversation - a second, conversation-varying `id` would only ever be read back
            // together with its own tag, never alone.
            NotificationManagerCompat.from(context).notify(tagFor(event.conversationId), NOTIFICATION_ID, notification)
        }

        /**
         * `docs/backlog/26-18-*.md`'s own Scope: "A tap opens the thread, never the list". `MainActivity`
         * has no `launchMode` beyond `singleTop` and no deep-link `<intent-filter>` of its own for this -
         * a plain explicit `Intent` naming the `Activity` class directly, the same shape this app's own
         * `net.openid.appauth.RedirectUriReceiverActivity` declaration is the *only* implicit-intent
         * receiver in this manifest at all. `FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP` together
         * with the manifest's `android:launchMode="singleTop"` are what make a tap while the app is
         * already running deliver to the *existing* `MainActivity` through `onNewIntent` rather than
         * stacking a second instance - `MainActivity.onNewIntent`'s own doc comment states the back-stack
         * consequence in full.
         */
        private fun openConversationPendingIntent(conversationId: String): PendingIntent {
            val intent =
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_OPEN_CONVERSATION_ID, conversationId)
                }
            return PendingIntent.getActivity(
                context,
                // A request code that varies with the conversation - two different conversations must
                // never share the identical `PendingIntent` (which would silently carry only the first
                // one's extra on a second tap, `PendingIntent`'s own documented "same request code, same
                // Intent extras" reuse rule) even though `notify`'s own `tag` is what actually collapses
                // the *notification* itself.
                conversationId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun tagFor(conversationId: String): String = "ago-conversation-$conversationId"

        private companion object {
            const val TAG = "PushNotification"
            const val NOTIFICATION_ID = 1
        }
    }
