package ago.chat.android.conversations

import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.domain.conversations.SearchConversationsResult
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-245`: the search screen's own state machine — reads through [ConversationsApi.searchConversations]
 * alone, the port `26-14` shipped and this item extends rather than a second port for the same server
 * resource. Created only when the operator actually opens the search screen
 * ([ago.chat.android.shell.ConversationsTabHost] composes [ConversationSearchRoute] only then), so the
 * first request happens only on a real search — this view model runs nothing on construction, unlike the
 * list's own [ConversationListViewModel], because there is nothing to show until a phrase is entered.
 *
 * **Opening a hit is read-only, never a claim or a positioned jump.** The console positions an
 * `Assigned` hit at `?at=<sequence>` and offers a claim button on a `Waiting` one
 * (`conversationsApi.ts#searchConversations`'s own doc comment); this app deliberately does neither.
 * Search is gated on the same `site:configure` the «Все» tab already requires, and that tab's own
 * established behaviour is to open any row **read-only** through
 * `GetConversationHistoryAsSiteConfigureHolderQuery` (`ConversationsTabHost`'s own read-only path) — a
 * server read that never claims. A phone-sized list makes an accidental claim costlier than the
 * console's table does, so every hit opens that same read-only way regardless of state, and
 * [ago.chat.android.core.domain.conversations.ConversationSearchHit.sequence] is carried for a future
 * positioned open rather than acted on today.
 */
@HiltViewModel
public class ConversationSearchViewModel
    @Inject
    constructor(
        private val api: ConversationsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(ConversationSearchUiState())
        public val state: StateFlow<ConversationSearchUiState> = mutableState.asStateFlow()

        /** The phrase the current [ConversationSearchPhase.Results] page was actually fetched with —
         * held here rather than read back off [ConversationSearchUiState.query] so a [loadMore] uses the
         * phrase the page belongs to even if the operator has since edited the field without re-running
         * the search. */
        private var searchedPhrase: String = ""

        /** Every keystroke in the field — updates [ConversationSearchUiState.query] and nothing else, so
         * the body below the field keeps showing the previous outcome until a search is actually run. */
        public fun onQueryChange(query: String) {
            mutableState.update { it.copy(query = query) }
        }

        /**
         * Runs a fresh search on the current [ConversationSearchUiState.query]. A blank phrase is a no-op
         * — the server would reject an empty `phrase` and there is nothing to search for, so this never
         * leaves [ConversationSearchPhase.Idle] for one (the identical "trim, and refuse the empty case"
         * guard `ago-console`'s own `SearchConversationsPage` applies before calling the endpoint).
         * Always asks for the first page — no cursor — and lets the server default the date window
         * (`from`/`to` omitted), reading the effective range back off the response.
         */
        public fun search() {
            val phrase = mutableState.value.query.trim()
            if (phrase.isEmpty()) return

            searchedPhrase = phrase
            mutableState.update { it.copy(phase = ConversationSearchPhase.Searching) }
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        api.searchConversations(
                            phrase = phrase,
                            from = null,
                            to = null,
                            beforeMessageId = null,
                            pageSize = SEARCH_PAGE_SIZE,
                        )
                    }
                mutableState.update { current ->
                    current.copy(
                        phase =
                            when (result) {
                                is SearchConversationsResult.Loaded ->
                                    ConversationSearchPhase.Results(
                                        hits = result.page.results,
                                        nextBeforeMessageId = result.page.nextBeforeMessageId,
                                        searchedFrom = result.page.searchedFrom,
                                        searchedTo = result.page.searchedTo,
                                    )

                                is SearchConversationsResult.Refused -> ConversationSearchPhase.Refused(result.detail)
                                is SearchConversationsResult.Failed -> ConversationSearchPhase.Failed(result.reason)
                            },
                    )
                }
            }
        }

        /**
         * The scroll-triggered next page — [ConversationSearchPhase.Results.nextBeforeMessageId]'s own
         * keyset cursor drives the request, with the identical double-guard (cursor exhausted, or a page
         * already in flight) [ago.chat.android.restrictions.RestrictedVisitorsViewModel.loadMore] states.
         * Reads `current`, not the phase captured at the call site, when applying the answer: a fresh
         * [search] may have run while this page was still out, in which case the stale page must not be
         * spliced onto whatever replaced it. A failed next page degrades non-destructively — the hits
         * already on screen stay, only [ConversationSearchPhase.Results.loadingMore] clears — the
         * identical "a secondary read fails quietly, the same control asks again" posture that sibling
         * screen takes; there is no dedicated error field for a failed page.
         */
        public fun loadMore() {
            val results = mutableState.value.phase as? ConversationSearchPhase.Results ?: return
            val cursor = results.nextBeforeMessageId ?: return
            if (results.loadingMore) return

            mutableState.update { it.copy(phase = results.copy(loadingMore = true)) }
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        api.searchConversations(
                            phrase = searchedPhrase,
                            from = null,
                            to = null,
                            beforeMessageId = cursor,
                            pageSize = SEARCH_PAGE_SIZE,
                        )
                    }
                mutableState.update { current ->
                    val currentResults = current.phase as? ConversationSearchPhase.Results ?: return@update current
                    when (result) {
                        is SearchConversationsResult.Loaded ->
                            current.copy(
                                phase =
                                    currentResults.copy(
                                        hits = currentResults.hits + result.page.results,
                                        nextBeforeMessageId = result.page.nextBeforeMessageId,
                                        loadingMore = false,
                                    ),
                            )

                        is SearchConversationsResult.Refused,
                        is SearchConversationsResult.Failed,
                        -> current.copy(phase = currentResults.copy(loadingMore = false))
                    }
                }
            }
        }

        private companion object {
            /** The page size sent to `/search`. `50` matches the site-wide list's own page
             * ([ConversationListViewModel]'s `ALL_PAGE_SIZE`) — a screen's worth with room to scroll into
             * the next page, rather than a number chosen for this endpoint alone. */
            const val SEARCH_PAGE_SIZE = 50
        }
    }
