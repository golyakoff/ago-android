package ago.chat.android.devices

/**
 * `26-18`: the two push kinds `26-05`'s fan-out (`Ago.Chat.Application.UseCases.NotifyOperatorDevices.
 * NotifyOperatorDevicesHandler`, `ago-chat`) actually sends, parsed from the wire `data` map
 * `RemoteMessage.data` carries - never a `notification` object, which this app never receives at all
 * (`push-notifications.md`'s own "data-only messages, never `notification` payloads").
 *
 * **Declared here, not as a `Boolean`/`String` pair.** A sealed type is what makes
 * [PushNotificationPresenter] exhaustive over "which two channels exist" rather than trusting every
 * call site to remember there are only two - the same reasoning `PushAvailability`'s own doc comment
 * gives for a sealed interface over a bare enum-plus-nullable-reason.
 */
public sealed interface IncomingPush {
    /** The conversation the event is about - both kinds carry one, always
     * (`NotifyOperatorDevicesHandler.HandleAssignmentAsync`/`HandleMessageAsync`, `ago-chat`). */
    public val conversationId: String

    /** `ConversationAssignedToOperator`, relayed - the wire `data` map is `{"conversationId": "..."}`
     * and nothing else, `NotifyOperatorDevicesHandler.HandleAssignmentAsync`'s own `data:` literal. */
    public data class ConversationAssigned(
        override val conversationId: String,
    ) : IncomingPush

    /** `MessageAccepted` from a visitor, relayed - the wire `data` map additionally carries
     * `messageId` (`NotifyOperatorDevicesHandler.HandleMessageAsync`'s own `data:` literal), which is
     * this type's own discriminator against [ConversationAssigned] below - see [parseIncomingPush]'s
     * own doc comment for why presence of that key is the only signal this app has. */
    public data class VisitorMessage(
        override val conversationId: String,
    ) : IncomingPush
}

/**
 * Reads [IncomingPush] back out of `RemoteMessage.data` (`message.data.orEmpty()` at the call site -
 * RuStore's own SDK types that field nullable even though its documentation describes it as a flat,
 * always-present map).
 *
 * **There is no `reason`/`kind` field on the wire, and this is a finding, not a guess.**
 * `NotifyOperatorDevicesHandler.HandleAssignmentAsync` sends `data = {conversationId}`;
 * `HandleMessageAsync` sends `data = {conversationId, messageId}` (plus `title`/`body`/`groupKey`,
 * folded in by `RuStorePushSender.BuildData` - deliberately unused by this app, see
 * [PushNotificationPresenter]'s own doc comment for why). Neither carries an explicit `reason: "assigned"
 * | "message"` string even though `NotifyOperatorDevicesHandler` itself names that exact distinction
 * server-side (`ReasonAssigned`/`ReasonMessage`, used only for its own metric tags). So this function's
 * only honest discriminator is **whether `messageId` is present** - a real key on the wire today, but an
 * implicit one, and a worthwhile follow-up for `ago-chat` would be to send `reason` explicitly rather
 * than have every consumer re-derive it this way. Recorded here rather than guessed around.
 *
 * Returns `null` for anything this app cannot act on - a missing or blank `conversationId`, which is
 * every field both kinds require - rather than throwing: a malformed or future payload this version does
 * not understand should be silently ignored, not crash the 20-second handling window
 * `AgoPushMessagingService`'s own doc comment names.
 */
public fun parseIncomingPush(data: Map<String, String>): IncomingPush? {
    val conversationId = data[KEY_CONVERSATION_ID]?.takeIf { it.isNotBlank() } ?: return null
    val messageId = data[KEY_MESSAGE_ID]?.takeIf { it.isNotBlank() }
    return if (messageId != null) {
        IncomingPush.VisitorMessage(conversationId)
    } else {
        IncomingPush.ConversationAssigned(conversationId)
    }
}

/** `NotifyOperatorDevicesHandler`'s own two data keys, by their real wire names - not guessed at. */
public const val KEY_CONVERSATION_ID: String = "conversationId"
public const val KEY_MESSAGE_ID: String = "messageId"
