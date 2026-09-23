package ago.chat.android.core.domain.conversations

/**
 * `26-14`: one row of `GET /api/v1/conversations/queue` (`Ago.Chat.Contracts.ConversationSummaryDto`),
 * reduced to exactly the fields this item's own Scope names — a visitor to render through
 * `VisitorDisplayPrefix` (`26-10`), when the conversation started, and how many messages the operator
 * has not yet read. `state`/`operatorId`/`operatorName`/the attachment-grant fields are on the wire DTO
 * and are not carried here because nothing in this screen reads them; a later item that needs one grows
 * this type rather than this screen inventing a second copy of the wire shape, the same additive
 * discipline `MessageDto`'s own doc comment states for its module.
 *
 * `createdAt` is kept as the raw ISO-8601 string the wire sends, not parsed here: parsing needs a `now`
 * to measure against ([elapsedSince]), and a domain entity that carried its own "how long ago" would be
 * a value that changes without a new server answer arriving — exactly the "cache what a write decision
 * depends on" shape rule 8 warns about, read onto a display value this time rather than a write one.
 *
 * `26-15`: [hasAttachmentUploadGrant] joins this reduced set - the one attachment-grant field this
 * screen's own doc comment above named as deliberately left off. The thread screen needs it to decide
 * whether to draw the composer's attach control at all (`navigation.md` §"The attach control": hidden,
 * never disabled, when absent) - and, mirroring `ago-console`'s own `useWorkspace().conversation`
 * (`Ago.Chat.Contracts.ConversationSummaryDto`'s own doc comment: "this is also what feeds
 * `ConversationPage`"), the already-fetched queue row is where that fact comes from. There is no
 * dedicated per-conversation fetch for it - the thread screen is handed the row the list already has,
 * never a second network round trip for one boolean.
 *
 * `26-30`: [lastMessagePreview]/[lastMessageAt] join the same additive way - `26-29`'s pair on
 * `Ago.Chat.Contracts.ConversationSummaryDto`, carried through unparsed for the identical reason
 * [createdAt] already is (a raw ISO-8601 string needs a `now` to render, which belongs to the screen's
 * own ticker, not to this entity). **Both null together means this conversation has no messages at
 * all** - that DTO's own doc comment, restated here rather than re-derived, since this type is the one
 * place `:app` reads it from. [lastMessagePreview] can also be null while [lastMessageAt] is not (a
 * system message, or one with no safe-to-preview content) - the row renders no snippet line at all in
 * that case, never an orphaned timestamp (`ConversationListScreen`'s own doc comment on
 * `ConversationRowSnippetLine`).
 *
 * `26-40`: [state] joins the same additive way — `Ago.Chat.Contracts.ConversationSummaryDto.State`
 * (`Ago.Chat.Domain.ConversationState`'s own member names: `"Waiting"`/`"Assigned"`/`"Closed"`, and
 * `"Pending"` in principle though no operator-facing read ever produces one), carried through verbatim
 * as the wire spelling rather than parsed here — the identical "the arithmetic/classification is a pure
 * `:core:domain` function, the prose is `:app`'s job" split [elapsedSince]/[ElapsedLabel] already
 * establish, restated by [conversationStateLabel]/[ConversationStateLabel]. Defaults to `""`, never a
 * real wire value, the same "a row that predates the field compiles unchanged" rule
 * [hasAttachmentUploadGrant] already follows. The thread screen's app-bar subtitle (`26-40`) is this
 * field's first reader.
 */
public data class ConversationSummary(
    public val conversationId: String,
    public val visitorId: String,
    public val emojiCreature: String?,
    public val emojiFood: String?,
    public val visitorName: String?,
    public val createdAt: String,
    public val operatorUnreadCount: Int,
    public val hasAttachmentUploadGrant: Boolean = false,
    public val lastMessagePreview: String? = null,
    public val lastMessageAt: String? = null,
    public val state: String = "",
)

/**
 * `GET /api/v1/conversations/queue`'s whole answer (`Ago.Chat.Contracts.OperatorQueueResponse`) — two
 * lists rather than one filterable list, for the identical reason that DTO's own doc comment gives:
 * "Waiting" and "AssignedToMe" answer genuinely different questions, and a screen showing both wants
 * them in one round trip.
 */
public data class ConversationQueue(
    public val waiting: List<ConversationSummary>,
    public val assignedToMe: List<ConversationSummary>,
)
