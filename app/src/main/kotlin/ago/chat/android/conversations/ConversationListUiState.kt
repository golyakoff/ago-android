package ago.chat.android.conversations

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.Permission
import ago.chat.android.core.domain.permissions.holds

/** «Мои» / «Ожидают» / «Все» — `docs/backlog/26-14-*.md`'s own Scope: "one screen with a segmented
 * control, not two screens", so this is a value the one screen renders differently, never a navigation
 * destination.
 *
 * `26-90`: [All] joins as a third segment of the same control, not a route and not an item behind
 * «⋮» — the console makes the site-wide list its own administrator *page*, but on a phone a separate
 * route would mean the operator leaves their own list to look at everybody's. Not every operator sees
 * it: see [visibleConversationListTabs]. */
public enum class ConversationListTab { Mine, Waiting, All }

/**
 * `26-90`: which segments this operator's control actually has — **two or three, never three with one
 * greyed out.** «Все» reads `GET /api/v1/conversations/all`, which
 * `GetAllConversationsForSiteHandler` gates on `site:configure` and deliberately *not* on
 * `conversation:read` (that handler's own remarks: `conversation:read` is what every ordinary operator
 * already holds, and widening it would hand every operator the site-wide list). An operator without
 * `site:configure` therefore has nothing behind that segment at all, and the same "hide, don't
 * disable" rule the thread screen's own attach control already follows (`navigation.md` §"The attach
 * control": a disabled one would advertise a capability nobody could use) says to leave it out
 * entirely.
 *
 * A pure function over [OperatorPermissions], in the same shape and for the same reason
 * [ago.chat.android.core.domain.navigation.visibleBottomDestinations] already computes the bottom
 * bar's own visible set: the rule is one testable expression rather than a condition spread through a
 * composable, so `ConversationListTabsTest` can assert "three for a holder, two for a non-holder"
 * without rendering anything. [OperatorPermissions.Unknown] yields two — [holds] answers `false` for
 * it, and hiding is the safe direction to guess wrong in (that type's own doc comment).
 */
public fun visibleConversationListTabs(permissions: OperatorPermissions): List<ConversationListTab> =
    conversationListTabs(canSeeAllConversations = permissions.holds(Permission.SITE_CONFIGURE))

/** The same list, from the already-answered question. [ConversationListRoute] is handed a `Boolean`
 * rather than a permission set (the convention `AppShellScreen`'s own `teamTab` slot already follows:
 * the caller holding the permissions computes the Boolean), so this is the shape the screen calls and
 * [visibleConversationListTabs] is the one-line permission rule on top of it — rather than the screen
 * rebuilding a synthetic [OperatorPermissions] just to ask the question again. */
public fun conversationListTabs(canSeeAllConversations: Boolean): List<ConversationListTab> =
    buildList {
        add(ConversationListTab.Mine)
        add(ConversationListTab.Waiting)
        if (canSeeAllConversations) add(ConversationListTab.All)
    }

/**
 * `26-90`: the three checkboxes of the «Все» tab's own status filter, and the one place their wire
 * spelling is stated. [wireState] is `Ago.Chat.Domain.ConversationState`'s own member name, sent
 * verbatim as a repeated `state` query parameter — the same unparsed vocabulary
 * [ago.chat.android.core.domain.conversations.ConversationSummary.state] already travels in, so this
 * app never owns a second spelling of the same set.
 *
 * `Pending` has no checkbox. It is not a conversation an operator has any business filtering *to* — a
 * visitor opened the widget and never wrote anything (`Ago.Chat.Domain.ConversationState.Pending`) —
 * and with a filter always applied (see [ConversationListUiState.allFilter]) it never reaches this
 * tab at all, which is the correct outcome rather than a gap.
 */
public enum class ConversationStateFilter(
    public val wireState: String,
) {
    /** «Не начат» — the visitor wrote, nobody has taken it. */
    NotStarted("Waiting"),

    /** «Назначен» — an operator holds it. */
    Assigned("Assigned"),

    /** «Закрыт» — the archive. Off by default: a closed conversation is something an operator should
     * have to ask for, not scroll past (`26-90`'s own Scope). */
    Closed("Closed"),
}

/** `26-90`: «Не начат» + «Назначен» on, «Закрыт» off — the tab's own opening state, named here rather
 * than written inline in [ConversationListUiState]'s default so the view model's own "reset the
 * filter" path and the state's default cannot drift apart. */
public val defaultConversationStateFilter: Set<ConversationStateFilter> =
    setOf(ConversationStateFilter.NotStarted, ConversationStateFilter.Assigned)

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
    /** `26-90`: carried through unchanged from [ago.chat.android.core.domain.conversations.ConversationSummary] -
     * the «Все» row's own third line, `Сообщений: N`. **A total, never an unread count** - [unreadCount]
     * keeps its own meaning and this tab draws no badge at all (that field's own doc comment). `0` on
     * every row of «Мои»/«Ожидают», which read an endpoint that does not populate it. */
    public val messageCount: Int = 0,
    /** `26-90`: carried through unchanged from [ago.chat.android.core.domain.conversations.ConversationSummary] -
     * who holds this conversation, for the «Все» row's own «Назначен: Мария П.» pill. `null` on a
     * `Waiting` row (nobody holds it) and on every row of «Мои»/«Ожидают»; the pill then renders the
     * bare word, never an invented placeholder. */
    public val operatorName: String? = null,
    /** `26-90`: this row's erasure has been *requested* and the server has not yet stopped returning
     * it. Set by [ConversationListViewModel.confirmErasure] on a `202 Accepted` and cleared only by a
     * list answer that no longer contains this conversation - never by a timer, and never by removing
     * the row from the list on the operator's behalf. See that method's own doc comment for why a
     * `202` may not be treated as a deletion. */
    public val isErasing: Boolean = false,
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
    /** `26-60`: `true` for exactly the duration of one in-flight [ConversationListViewModel.refresh]
     * call - what [ConversationListScreen] disables the retry control on and relabels while true, and
     * what [ConversationListViewModel.refresh] itself checks first so a second tap while a refresh is
     * still out is a no-op rather than a second identical request (the same in-flight guard
     * [ConversationListViewModel.claim] already keeps in `claimingIds`, restated here for the one
     * request this class makes with no per-row id to key a set on). */
    public val isRefreshing: Boolean = false,
    /** `26-90`: the «Все» tab's own rows, kept beside [mine]/[waiting] rather than replacing them,
     * because they come from a genuinely different endpoint with a genuinely different lifetime - this
     * list is keyset-paged and grows as the operator scrolls, where those two are re-read whole on
     * every answer. Newest first, the order the server already returns them in (`c.id desc`, and
     * conversation ids are UUID v7), never re-sorted here. */
    public val all: List<ConversationRowUi> = emptyList(),
    /** `26-90`: which of the three checkboxes are ticked. Never empty - [ConversationListViewModel
     * .onStateFilterToggled] refuses to untick the last one, because an empty set would mean
     * "unfiltered" to the server (`GetAllConversationsForSiteHandler`: no states is not a filter that
     * matches nothing) and an operator who has just cleared every box would read a full list as a bug. */
    public val allFilter: Set<ConversationStateFilter> = defaultConversationStateFilter,
    /** `false` until this tab's own first page has answered - [hasData]'s counterpart for a list this
     * screen fetches separately, kept apart from it for the same reason [all] is kept apart from
     * [mine]/[waiting]: the queue answering says nothing about whether this list has. */
    public val allHasData: Boolean = false,
    /** `26-90`: whether the server handed back a keyset cursor for another page - `false` means the
     * last page has been reached, never "ask again and find out"
     * (`AllConversationsForSiteResponse.NextBeforeId`'s own contract). */
    public val allHasMore: Boolean = false,
    /** `true` only while a page (first or next) is in flight - what the list's own trailing spinner
     * renders from, and what stops the scroll-triggered [ConversationListViewModel.loadMoreAll] from
     * firing a second identical request while the first is still out. */
    public val isLoadingAll: Boolean = false,
    /** The «Все» tab's own read failure, separate from [loadError] because the two lists fail
     * independently: a queue read failing says nothing about the site-wide one, and showing one
     * banner for both would blame the wrong list. */
    public val allLoadError: NetworkFailure? = null,
    /** `26-90`: whether this operator may erase at all (`conversation:erase`) - computed once by the
     * caller that already holds the permission set and handed down, the same split
     * [visibleConversationListTabs] uses for the segment itself. `false` means the swipe gesture is
     * not wired at all, not that it is wired and refused. */
    public val canErase: Boolean = false,
    /** `26-90`: the last erasure request's own failure, shown once above the list and cleared by
     * [ConversationListViewModel.dismissEraseFailure] - never per row, because the row it belongs to is
     * still in the list exactly where it was and the operator has just come back from a confirmation
     * dialog, so there is no ambiguity about which conversation this is about. */
    public val eraseFailure: EraseFailureUi? = null,
)

/**
 * `26-90`: what an erasure *request* failed with. Mirrors
 * [ago.chat.android.core.domain.conversations.ErasureResult]'s own two failure arms one-to-one, which
 * is the same rule [ClaimErrorUi] states for itself against `ClaimResult` - one UI type per domain
 * result type, rather than one shared "row action failed" type the two would both have to be bent to
 * fit. Kept separate from [ClaimErrorUi] deliberately: they are rendered in different places (a claim
 * refusal inline under its own waiting row, an erase refusal above the «Все» list), and merging them
 * would rename a type three files and an existing test already name, on exactly the files
 * `26-60`/`26-61`/`26-63`/`26-67` are queued to rebase onto.
 */
public sealed interface EraseFailureUi {
    public data class ServerRefusal(
        val detail: String,
    ) : EraseFailureUi

    public data class Unavailable(
        val reason: NetworkFailure,
    ) : EraseFailureUi
}

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
