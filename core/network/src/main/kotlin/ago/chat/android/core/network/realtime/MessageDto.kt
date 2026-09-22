package ago.chat.android.core.network.realtime

/**
 * `Ago.Chat.Contracts.MessageDto`, hand-written the same way `ago-console`'s
 * `realtime/protocol/types.ts` and `ago-widget`'s own copy already are — `adr/0178`: "there is
 * nothing to generate from". Deserialized by `com.microsoft.signalr`'s own bundled Gson-backed hub
 * protocol, not by this app's `kotlinx.serialization` `Json` instance (`AgoHttpClient.kt`'s `agoJson`
 * only ever sees this app's plain REST responses) — Gson resolves a Kotlin data class's fields
 * reflectively regardless of constructor visibility, so no annotation is needed here for that to work.
 *
 * Reduced to exactly the fields [OperatorHubConnection] itself needs to prove ordering, dedup and
 * resume — this item's own scope is the transport, not rendering a message (`docs/backlog/26-13-*.md`'s
 * own Out of scope: "Rendering any actual conversation content" is `26-14`/`26-15`). Those items are
 * expected to grow this type with `body`/`authorKind`/`authorId`/`attachmentId`/etc. rather than
 * invent a second copy of it — nothing about `id`/`sequence`/`conversationId` changes shape when they
 * do, since the wire contract is additive by convention (`api-design.md`).
 *
 * `26-14`: [authorKind] is the first of that growth — `Ago.Chat.Contracts.MessageDto.AuthorKind`,
 * `"Visitor"`/`"Operator"`/`"System"` on the wire. The list screen's own unread count has to exclude an
 * operator's own echoed-back send, the identical filter `ago-console`'s `WorkspaceLayout.tsx` applies
 * (`message.authorKind !== "Visitor"`) before counting a push as unread — without this field there is no
 * way to tell "a visitor wrote" from "I just sent", and a badge that counted the operator's own messages
 * would climb every time they answered. Defaulted to `""` (never a valid wire value) rather than made
 * mandatory, so `26-13`'s own fixtures — none of which cared about authorship — keep compiling unchanged;
 * `""` compares false against every real `AuthorKind`, so an un-set value is silently excluded from the
 * unread count rather than silently included in it.
 */
public data class MessageDto(
    val id: String,
    val sequence: Long,
    val conversationId: String? = null,
    val authorKind: String = "",
)

/**
 * `OperatorHub.JoinConversationAsync`'s return shape (`docs/architecture/realtime.md`: "one handler,
 * two entry points" — the same shape `GetHistoryAsync` returns as `Ago.Chat.Contracts.HistoryPage`).
 * `nextBeforeSequence` is the backward-keyset cursor for "load older messages", a direction `26-13`
 * never exercises (that is `26-14`'s "load more history" screen) — carried here only so this type
 * matches the wire shape rather than because this item's own resume path reads it.
 */
public data class HistoryPage(
    val messages: List<MessageDto> = emptyList(),
    val nextBeforeSequence: Long? = null,
)
