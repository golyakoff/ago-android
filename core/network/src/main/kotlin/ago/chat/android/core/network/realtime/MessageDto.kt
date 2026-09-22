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
 */
public data class MessageDto(
    val id: String,
    val sequence: Long,
    val conversationId: String? = null,
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
