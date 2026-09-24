package ago.chat.android.presence

import ago.chat.android.R
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * `26-85`: this service's own, third notification channel — `IMPORTANCE_LOW` (silent, no sound, no
 * heads-up), deliberately not one more arm on [ago.chat.android.devices.PushNotificationChannel]. That
 * enum's own doc comment states it exists for exactly the two kinds `NotifyOperatorDevicesHandler`
 * sends and calls a third arm "a switch that lies" — this notification is not a push at all, it is the
 * mandatory, persistent notice `Service.startForeground` requires for as long as the connection stays
 * open, so it earns its own channel and its own file instead of stretching that enum's own contract.
 */
internal const val OPERATOR_PRESENCE_CHANNEL_ID: String = "ago.presence.online"

/** [OperatorPresenceService]'s own `startForeground` id — a distinct constant from
 * [ago.chat.android.devices.SystemPushNotificationPresenter]'s own `NOTIFICATION_ID`, even though the
 * two could never collide in practice (different channels, and a push is posted through `notify`'s own
 * tag-keyed slot while this one is the one and only id `startForeground` ever uses for this service). */
internal const val OPERATOR_PRESENCE_NOTIFICATION_ID: Int = 1001

/**
 * Called from `AgoChatApplication.onCreate`, alongside [ago.chat.android.devices.ensureChannelsCreated]
 * — idempotent by the identical `NotificationManagerCompat.createNotificationChannelsCompat` contract
 * that function's own doc comment already states, so running it on every process start costs nothing
 * measurable.
 */
internal fun ensurePresenceChannelCreated(context: Context) {
    val channel =
        NotificationChannelCompat
            .Builder(OPERATOR_PRESENCE_CHANNEL_ID, NotificationManager.IMPORTANCE_LOW)
            .setName(context.getString(R.string.operator_presence_channel_name))
            .setDescription(context.getString(R.string.operator_presence_channel_description))
            .build()
    NotificationManagerCompat.from(context).createNotificationChannelsCompat(listOf(channel))
}

/**
 * [OperatorPresenceService]'s own `startForeground` argument — no `PendingIntent` of any kind, unlike a
 * push ([ago.chat.android.devices.PushNotificationPresenter.openConversationPendingIntent]): tapping this
 * one has nowhere more useful to send the operator than the app already open, which is a plain
 * `Notification` with no `setContentIntent`'s own default tap behaviour (opens the app if it can, does
 * nothing otherwise).
 */
internal fun buildPresenceNotification(context: Context): Notification =
    NotificationCompat
        .Builder(context, OPERATOR_PRESENCE_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.operator_presence_notification_title))
        .setContentText(context.getString(R.string.operator_presence_notification_body))
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
