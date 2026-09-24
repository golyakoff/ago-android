package ago.chat.android.devices

/**
 * `26-18`/`26-86`: the three push kinds `NotifyOperatorDevicesHandler` (`ago-chat`,
 * `Ago.Chat.Application.UseCases.NotifyOperatorDevices`) actually sends, parsed from the wire `data` map
 * `RemoteMessage.data` carries - never a `notification` object, which this app never receives at all
 * (`push-notifications.md`'s own "data-only messages, never `notification` payloads").
 *
 * **Declared here, not as a `Boolean`/`String` pair.** A sealed type is what makes
 * [PushNotificationPresenter] exhaustive over "which channels exist" rather than trusting every call
 * site to remember how many there are - the same reasoning `PushAvailability`'s own doc comment gives
 * for a sealed interface over a bare enum-plus-nullable-reason.
 */
public sealed interface IncomingPush {
    /** The conversation the event is about - every kind carries one, always
     * (`NotifyOperatorDevicesHandler`'s three `Handle*Async` arms each put `conversationId` on the
     * wire). */
    public val conversationId: String

    /** `ConversationAssignedToOperator`, relayed - the wire `data` map is
     * `{"conversationId": "...", "reason": "assigned"}` and nothing else,
     * `NotifyOperatorDevicesHandler.HandleAssignmentAsync`'s own `data:` literal. */
    public data class ConversationAssigned(
        override val conversationId: String,
    ) : IncomingPush

    /** `MessageAccepted` from a visitor, relayed - the wire `data` map additionally carries
     * `messageId` (`NotifyOperatorDevicesHandler.HandleMessageAsync`'s own `data:` literal). */
    public data class VisitorMessage(
        override val conversationId: String,
    ) : IncomingPush

    /** `26-86`: `ConversationWaitingForOperator`, relayed - a brand-new visitor conversation entered the
     * queue with nobody assigned yet. The wire `data` map is `{"conversationId": "...", "reason":
     * "waiting"}`, the identical no-extra-key shape [ConversationAssigned] already has - there is no
     * operator to name (`NotifyOperatorDevicesHandler.HandleWaitingAsync`'s own remarks: every eligible
     * operator on the site gets this same push, not one assignee). */
    public data class ConversationWaiting(
        override val conversationId: String,
    ) : IncomingPush
}

/**
 * Reads [IncomingPush] back out of `RemoteMessage.data` (`message.data.orEmpty()` at the call site -
 * RuStore's own SDK types that field nullable even though its documentation describes it as a flat,
 * always-present map).
 *
 * **Discriminates on the explicit `reason` key, not on which other keys happen to be present.**
 * `26-81` found the previous shape fragile: neither `HandleAssignmentAsync` nor `HandleMessageAsync` put
 * their own already-named `ReasonAssigned`/`ReasonMessage` server-side reason on the wire, so this
 * function had no choice but to infer the kind from **whether `messageId` was present** - a real,
 * working signal at the time, but an implicit one that would have silently misparsed any push carrying
 * no `messageId` of its own as `ConversationAssigned`. `26-86` closes that gap in the same change it
 * needed a real discriminator for anyway (a third kind with neither a `messageId` nor an assignee would
 * have made the old inference actively wrong, not merely fragile): every kind now carries an explicit
 * `reason: "assigned" | "message" | "waiting"` string
 * (`NotifyOperatorDevicesHandler.SendToOperatorAsync`'s own remarks), and this function reads it
 * directly instead of re-deriving it from key presence.
 *
 * Returns `null` for anything this app cannot act on - a missing or blank `conversationId`, or a
 * `reason` this version does not recognise - rather than throwing: a malformed or future payload should
 * be silently ignored, not crash the 20-second handling window `AgoPushMessagingService`'s own doc
 * comment names.
 */
public fun parseIncomingPush(data: Map<String, String>): IncomingPush? {
    val conversationId = data[KEY_CONVERSATION_ID]?.takeIf { it.isNotBlank() } ?: return null
    return when (data[KEY_REASON]) {
        REASON_ASSIGNED -> IncomingPush.ConversationAssigned(conversationId)
        REASON_MESSAGE -> IncomingPush.VisitorMessage(conversationId)
        REASON_WAITING -> IncomingPush.ConversationWaiting(conversationId)
        else -> null
    }
}

/** `NotifyOperatorDevicesHandler`'s own data keys, by their real wire names - not guessed at. */
public const val KEY_CONVERSATION_ID: String = "conversationId"
public const val KEY_MESSAGE_ID: String = "messageId"

/** `26-86`/`26-81`: the explicit discriminator every kind now carries -
 * `NotifyOperatorDevicesHandler`'s own `ReasonAssigned`/`ReasonMessage`/`ReasonWaiting` constants,
 * restated client-side by their real wire values rather than the server's private field names. */
public const val KEY_REASON: String = "reason"
public const val REASON_ASSIGNED: String = "assigned"
public const val REASON_MESSAGE: String = "message"
public const val REASON_WAITING: String = "waiting"
