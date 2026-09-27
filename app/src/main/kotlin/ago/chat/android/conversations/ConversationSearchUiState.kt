package ago.chat.android.conversations

import ago.chat.android.core.domain.conversations.ConversationSearchHit
import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-245`: everything [ConversationSearchScreen] renders. [query] is the live text of the search field
 * and is deliberately kept outside [phase] — it must survive across every phase (a failed search still
 * shows the phrase the operator typed, so a retry does not make them re-type it), which no single arm of
 * [phase] could own on its own. [phase] is what the *body* below the field renders from — the identical
 * "the input persists, the outcome is a sealed value" split every search-style screen in this app takes.
 */
public data class ConversationSearchUiState(
    public val query: String = "",
    public val phase: ConversationSearchPhase = ConversationSearchPhase.Idle,
)

/**
 * `26-245`: the search body's own five states — nothing searched yet, a first page in flight, a genuine
 * server refusal, a transport/shape failure, or a landed page (whose own hit list may be empty, which is
 * the "no results" case rendered from [Results] with `hits.isEmpty()` rather than a sixth arm). The same
 * sealed-outcome shape [ago.chat.android.restrictions.RestrictedVisitorsUiState] takes for its own
 * screen, and the same [ago.chat.android.core.domain.conversations.SearchConversationsResult] three-way
 * split it renders from — [Refused] shown verbatim, [Failed] rendered through the app's shared
 * classification vocabulary.
 */
public sealed interface ConversationSearchPhase {
    /** No search has been run yet — the field is empty or freshly opened. */
    public data object Idle : ConversationSearchPhase

    /** The first page of a fresh search is in flight. */
    public data object Searching : ConversationSearchPhase

    /** A `400 Conversation.SearchInvalidQuery` (an invalid range) — the server's own words, shown as-is
     * (`ConversationSearchViewModel`'s own doc comment on why this is separate from [Failed]). */
    public data class Refused(
        val detail: String,
    ) : ConversationSearchPhase

    /** A transport failure or a bad-shape/other non-2xx — rendered through `networkFailureText`, never a
     * fabricated sentence, with a retry that re-runs the same phrase. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : ConversationSearchPhase

    /**
     * A landed page. [hits] empty is the "nothing matched" case, distinct from [Idle] (nothing searched)
     * — the screen draws a "no results" body for it rather than the initial prompt. [nextBeforeMessageId]
     * `null` means the last page has been reached (never "ask again and find out",
     * `ConversationSearchPage.nextBeforeMessageId`'s own contract). [searchedFrom]/[searchedTo] are the
     * range the server actually used, shown as a caption so the effective window is visible rather than
     * silent. [loadingMore] guards the scroll-triggered [ConversationSearchViewModel.loadMore] against
     * firing a second request while one is already out.
     */
    public data class Results(
        val hits: List<ConversationSearchHit>,
        val nextBeforeMessageId: String?,
        val searchedFrom: String,
        val searchedTo: String,
        val loadingMore: Boolean = false,
    ) : ConversationSearchPhase
}
