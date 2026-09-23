package ago.chat.android.conversations

import ago.chat.android.core.domain.net.NetworkFailure

/** «Мои» / «Ожидают» — `docs/backlog/26-14-*.md`'s own Scope: "one screen with a segmented control,
 * not two screens", so this is a value the one screen renders differently, never a navigation
 * destination. */
public enum class ConversationListTab { Mine, Waiting }

/**
 * One row, as the screen renders it — [ago.chat.android.core.domain.conversations.ConversationSummary]
 * plus the purely local, in-memory overlay a hub push or a claim attempt adds on top of it
 * ([ConversationListViewModel]'s own doc comment explains why that overlay is never persisted).
 */
public data class ConversationRowUi(
    public val conversationId: String,
    public val visitorId: String,
    public val emojiCreature: String?,
    public val emojiFood: String?,
    public val visitorName: String?,
    public val createdAt: String,
    public val unreadCount: Int,
    /** Set the moment a `ConversationAssigned` push names this conversation, cleared the moment the
     * operator opens it ([ConversationListViewModel.onRowOpened]) — `ConversationList.tsx`'s own
     * `isNewlyAssigned`/`"New"` badge, ported. Never read by anything that decides whether to
     * navigate — see `docs/navigation.md`: "a new assignment arriving never navigates". */
    public val isNewlyAssigned: Boolean = false,
    /** Only ever true for a `Waiting` row, and only while its own [ConversationsApi.claim] call is in
     * flight — the same "hidden, not disabled... two operators clicking this within the same instant
     * is the ordinary case" posture `ago-console`'s `ClaimConversationButton` already documents. */
    public val isClaiming: Boolean = false,
    /** What the last claim attempt on this row failed with, shown once and cleared only by
     * [ConversationListViewModel.dismissClaimError] or by this conversation moving out of «Ожидают»
     * entirely (a claim that then succeeded from elsewhere, or the visitor leaving) — never cleared by
     * an automatic retry, because there never is one. `26-59`: [ClaimErrorUi] rather than a `String`, so
     * a genuine server refusal ([ClaimErrorUi.ServerRefusal]) and a transport failure that never reached
     * the server ([ClaimErrorUi.Unavailable]) render differently instead of sharing one field. */
    public val claimError: ClaimErrorUi? = null,
    /** `26-15`: carried through unchanged from [ago.chat.android.core.domain.conversations.ConversationSummary] -
     * the thread screen's own attach-control gate, read from the row the list already fetched rather
     * than a second network call (that field's own doc comment). Not read by anything on this screen
     * itself. */
    public val hasAttachmentUploadGrant: Boolean = false,
    /** `26-30`: carried through unchanged from [ago.chat.android.core.domain.conversations.ConversationSummary] -
     * the row's own snippet line. `null` means "no snippet line at all", never an empty one - that
     * field's own doc comment. */
    public val lastMessagePreview: String? = null,
    /** `26-30`: the snippet line's own timestamp, rendered beside [lastMessagePreview] in the identical
     * short elapsed format the name line's [createdAt] already uses. Read only when [lastMessagePreview]
     * is non-null - never a lone timestamp with nothing to attach to. */
    public val lastMessageAt: String? = null,
    /** `26-40`: carried through unchanged from [ago.chat.android.core.domain.conversations.ConversationSummary] -
     * the thread screen's own app-bar subtitle, read from the row the list already fetched
     * (`ConversationsTabHost`) rather than a second network call. Not read by anything on this screen
     * itself; `""` (the field's own default) means "predates this field" and renders no state word. */
    public val state: String = "",
    /** `26-76`: carried through unchanged from [ago.chat.android.core.domain.conversations.ConversationSummary] -
     * `null` for plain prose, non-null for a module step. [ConversationListScreen]'s own snippet-line
     * call site reads this to prefix [lastMessagePreview] with "📅 " rather than rendering it bare. */
    public val lastMessageContentKind: String? = null,
)

/**
 * `26-14`: everything [ConversationListScreen] renders. [isStale] and [hasData] are deliberately two
 * different booleans rather than one three-valued enum, because they answer two different questions a
 * `when` over one type would conflate: *is there anything to show* ([hasData]) and *is what is showing
 * confirmed current* ([isStale]) — a cold start with a cache hit is `hasData = true, isStale = true`,
 * which no single boolean could express without inventing a third state to stand for it.
 */
public data class ConversationListUiState(
    public val selectedTab: ConversationListTab = ConversationListTab.Mine,
    public val mine: List<ConversationRowUi> = emptyList(),
    public val waiting: List<ConversationRowUi> = emptyList(),
    /** `true` until this session's own [ConversationsApi.fetchQueue] has answered successfully at
     * least once — a Room hit at screen start is real data, rendered immediately, and still marked
     * this way until a live answer confirms it (`docs/backlog/26-14-*.md`: "stale until proven
     * fresh"). A live hub push narrows one row's own freshness ([ConversationListViewModel]'s own
     * per-row overlay) but never clears this flag on its own — only a full [QueueResult.Loaded] does,
     * because only that answer actually re-read the *whole* list this flag describes. */
    public val isStale: Boolean = true,
    /** `false` only before either a cache read or a network answer has produced anything at all - the
     * one moment a loading skeleton, rather than an empty-state message, is the honest thing to show. */
    public val hasData: Boolean = false,
    /** `26-59`: [NetworkFailure]'s own classification rather than a pre-rendered `String` — this
     * screen stopped choosing the operator's words the moment it stopped being trustworthy enough to
     * write an exception's own message into them; [ConversationListScreen] renders this into a
     * sentence. */
    public val loadError: NetworkFailure? = null,
)

/** `26-59`: what a claim attempt on one row failed with — a genuine server answer
 * ([ServerRefusal], shown verbatim) or anything that kept the answer from ever being genuine at all
 * ([Unavailable], a classification, never a fabricated sentence). Mirrors
 * [ago.chat.android.core.domain.conversations.ClaimResult]'s own two failure arms one-to-one; this type
 * exists only because [ConversationRowUi] needs one field to hold either. */
public sealed interface ClaimErrorUi {
    public data class ServerRefusal(
        val detail: String,
    ) : ClaimErrorUi

    public data class Unavailable(
        val reason: NetworkFailure,
    ) : ClaimErrorUi
}
