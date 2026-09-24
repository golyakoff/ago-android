package ago.chat.android.devices

import ago.chat.android.R
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat

/**
 * `26-18`: "Notification channels for the kinds the fan-out actually sends... **only those**." Originally
 * exactly two (`NotifyOperatorDevicesHandler.HandleAssignmentAsync`/`HandleMessageAsync`); `26-86` adds the
 * third the fan-out gained (`HandleWaitingAsync`) - still a closed set matching the server's own arms
 * one-for-one, never a channel with no sender behind it (this item's own words: "a switch that lies").
 * `26-19` is what will ever let an operator *configure* per-channel importance; this class only ever
 * creates the ones that exist, at their platform default importance.
 *
 * `internal` [PushNotificationChannel] values rather than bare string constants scattered across
 * [PushNotificationPresenter] and this file - an [IncomingPush] arm can only ever resolve to one of these,
 * which [PushNotificationChannel.forEvent] makes exhaustive rather than a string that could silently
 * drift from what [ensureChannelsCreated] actually registered.
 */
internal enum class PushNotificationChannel(
    val id: String,
    val nameRes: Int,
    val descriptionRes: Int,
) {
    Assignment("ago.push.assignment", R.string.push_channel_assignment_name, R.string.push_channel_assignment_description),
    VisitorMessage(
        "ago.push.visitor_message",
        R.string.push_channel_visitor_message_name,
        R.string.push_channel_visitor_message_description,
    ),
    Waiting("ago.push.waiting", R.string.push_channel_waiting_name, R.string.push_channel_waiting_description),
    ;

    companion object {
        fun forEvent(event: IncomingPush): PushNotificationChannel =
            when (event) {
                is IncomingPush.ConversationAssigned -> Assignment
                is IncomingPush.VisitorMessage -> VisitorMessage
                is IncomingPush.ConversationWaiting -> Waiting
            }
    }
}

/**
 * Idempotent by construction - `NotificationManagerCompat.createNotificationChannelsCompat`'s own
 * documented behaviour is that re-creating a channel with the same id is a no-op for every field an
 * operator may since have changed by hand (importance, sound) and only updates the name/description this
 * app owns - so calling this on every process start (`AgoChatApplication.onCreate`) is correct rather than
 * merely harmless, the same "eagerly, every start, cheap because the platform makes it cheap" shape
 * `RuStorePushClient.init` alongside it already follows.
 *
 * `NotificationManager.IMPORTANCE_DEFAULT` for both - a heads-up/lock-screen-visible notification with a
 * sound, which is the ordinary shape for "an operator should probably look at this soon" and requires no
 * product decision this item is scoped to make (`26-19`'s own per-channel importance control is where a
 * *different* default per channel would be decided, not here).
 */
internal fun ensureChannelsCreated(context: Context) {
    val channels =
        PushNotificationChannel.entries.map { channel ->
            NotificationChannelCompat
                .Builder(channel.id, NotificationManager.IMPORTANCE_DEFAULT)
                .setName(context.getString(channel.nameRes))
                .setDescription(context.getString(channel.descriptionRes))
                .build()
        }
    NotificationManagerCompat.from(context).createNotificationChannelsCompat(channels)
}
