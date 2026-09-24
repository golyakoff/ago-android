package ago.chat.android.devices

/**
 * `26-18`: `ago-console/src/workspace/alerts.ts`'s own `decideAlert`, ported rather than re-invented
 * (`docs/backlog/26-18-*.md`'s own Scope: "It is reused, not re-invented"). The console's own doc
 * comment on that function states the one subtlety that makes a naive port wrong in either direction,
 * and it applies unchanged here:
 *
 * - **The app can be in front while a different conversation is open.** An operator answering visitor A
 *   is not looking at visitor B, and a message from B is exactly what they need told about - "the app is
 *   foregrounded" alone would wrongly swallow it.
 * - **The right conversation can be "open" while the app is backgrounded.** `ConversationsTabHost`'s own
 *   `openConversationId` can still name a conversation after the operator has switched to another app
 *   entirely (nothing clears it on backgrounding, by design - closing it is a navigation act, not a
 *   lifecycle one) - "the conversation is open" alone would wrongly swallow this one too, which is the
 *   case a push exists for.
 *
 * So silence requires **both**: this exact conversation is the one open, *and* the app is actually in
 * front of the user right now. Everything else is told.
 *
 * A plain function over three primitives rather than a class, for the identical reason `alerts.ts`'s own
 * `decideAlert` is a pure function rather than a hook: the whole rule is one readable expression, and a
 * test never needs a `Service`, a `ProcessLifecycleOwner` or a `ThreadViewModel` to exercise it.
 */
public fun decideAlert(
    conversationId: String,
    openConversationId: String?,
    appInForeground: Boolean,
): Boolean {
    val alreadyLookingAt = conversationId == openConversationId && appInForeground
    return !alreadyLookingAt
}
