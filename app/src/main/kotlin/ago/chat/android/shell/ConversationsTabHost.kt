package ago.chat.android.shell

import ago.chat.android.conversations.ConversationListRoute
import ago.chat.android.conversations.ConversationListViewModel
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.thread.ThreadRoute
import ago.chat.android.thread.ThreadViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * `26-16`: the Диалоги destination's own content — `26-15`'s hand-rolled list/thread "back stack",
 * relocated unchanged out of `ago.chat.android.signin.SignInScreens` (that file's own retired
 * `SignedInHost`) into its real home now that one exists. **Nothing about the mechanism changes here**
 * — [rememberSaveableStateHolder] is still what makes back-from-a-thread keep the list's own scroll
 * position and filters (back-button contract clause 1, `docs/navigation.md`), the identical property
 * `26-15`'s own Done-when already proved before this item existed.
 *
 * What *is* new is where this composable sits: one route (`BottomDestination.Conversations.route`,
 * [AppShellScreen]'s own `NavHost`) rather than the only thing `SignInHost` ever drew once signed in.
 * That relocation is what makes clause 3 of the back contract true for free — see [AppShellScreen]'s
 * own doc comment for why Navigation Compose's ordinary bottom-navigation recipe already produces
 * "back off any other bottom-bar destination lands on Диалоги; back off Диалоги exits" without a line
 * of custom back-handling here or there, precisely because this destination is the graph's own
 * `startDestination`.
 *
 * [viewModel] and [threadViewModel] both keep the identical `hiltViewModel()`-default shape
 * [ConversationListRoute]/[ThreadRoute] themselves already establish — a test can substitute its own
 * instance of either with no Hilt component in play at all, which is what lets the back-button-contract
 * UI tests for clause 1 (thread → list) and clause 6 (a draft survives back) construct this composable
 * directly with fakes. [threadViewModel] is a `@Composable` provider rather than a plain value because
 * [ThreadViewModel] is scoped per open conversation in production (`hiltViewModel()`'s own default
 * scoping to the current back stack entry) — a plain default parameter would have to call `hiltViewModel()`
 * outside this function's own composition, which is not legal Compose.
 */
@Composable
public fun ConversationsTabHost(
    activeSiteId: String?,
    hubConnectionState: OperatorHubConnectionState,
    onSignOut: () -> Unit,
    operatorDisplayName: String? = null,
    operatorEmail: String? = null,
    onOpenSettings: () -> Unit = {},
    // `26-90`: the two permission-derived facts the Диалоги destination needs, computed by
    // [AppShellScreen]'s own `conversationsTab` default from the permission set it already holds - the
    // identical split its `teamTab`/`bookingsTab` slots already use. Defaulted to `false` so every
    // back-contract test that constructs this composable directly compiles and behaves unchanged.
    canSeeAllConversations: Boolean = false,
    canEraseConversations: Boolean = false,
    // `26-147`: `conversation:read`, computed by [AppShellScreen]'s own `conversationsTab` default from
    // the permission set it already holds - the identical split `canSeeAllConversations` above already
    // uses. Threaded straight through to [ThreadRoute], which gates the contact-panel affordance on it
    // (hide-not-disable). Defaulted to `false` so every back-contract test constructing this composable
    // directly compiles and behaves unchanged.
    canReadContactDetail: Boolean = false,
    // `26-169`: `conversation:send`, computed by [AppShellScreen]'s own `conversationsTab` default and
    // threaded straight through to [ThreadRoute] alongside `canReadContactDetail`, which gates the contact
    // details section's row `⋮` (edit + set-assessment) on it (hide-not-disable). Defaulted to `false` so
    // every back-contract test constructing this composable directly compiles and behaves unchanged.
    canSendConversation: Boolean = false,
    // `26-149`: `conversation:tag`, computed by [AppShellScreen]'s own `conversationsTab` default and
    // threaded straight through to [ThreadRoute] alongside `canReadContactDetail`, which gates the tags
    // section's write affordances on it (hide-not-disable). Defaulted to `false` so every back-contract
    // test constructing this composable directly compiles and behaves unchanged.
    canTagConversation: Boolean = false,
    // `26-150`: `conversation:note_write`, computed by [AppShellScreen]'s own `conversationsTab` default
    // and threaded straight through to [ThreadRoute] alongside `canReadContactDetail`/`canTagConversation`,
    // which gates the notes sub-screen's own composer on it (hide-not-disable). Defaulted to `false` so
    // every back-contract test constructing this composable directly compiles and behaves unchanged.
    canWriteNote: Boolean = false,
    // `26-152`: `conversation:attachment_upload_grant`, computed by [AppShellScreen]'s own `conversationsTab`
    // default and threaded straight through to [ThreadRoute] alongside `canReadContactDetail`/
    // `canTagConversation`/`canWriteNote`, which gates the attachment-upload section's whole existence on
    // it (hide-not-disable). Defaulted to `false` so every back-contract test constructing this composable
    // directly compiles and behaves unchanged.
    canGrantAttachmentUpload: Boolean = false,
    // `26-153`: `conversation:close`, computed by [AppShellScreen]'s own `conversationsTab` default and
    // threaded straight through to [ThreadRoute] alongside `canReadContactDetail`/`canTagConversation`/
    // `canWriteNote`/`canGrantAttachmentUpload`, which gates the contact-panel's «Закрыть диалог» button
    // on it (hide-not-disable). Defaulted to `false` so every back-contract test constructing this
    // composable directly compiles and behaves unchanged.
    canCloseConversation: Boolean = false,
    // `26-153`: `conversation:block`, computed the same way and threaded through alongside
    // `canCloseConversation`, which gates the contact-panel's reversible «Ограничить»/«Снять ограничение»
    // button on it (hide-not-disable). Defaulted to `false` so every back-contract test constructing this
    // composable directly compiles and behaves unchanged.
    canRestrictVisitor: Boolean = false,
    viewModel: ConversationListViewModel = hiltViewModel(),
    threadViewModel: @Composable () -> ThreadViewModel = { hiltViewModel() },
) {
    val listState by viewModel.state.collectAsStateWithLifecycle()
    var openConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    // `26-98`: which open, if any, is the «Все» list's own read-only one - holds `openConversationId`'s
    // own value again when it is, `null` otherwise, rather than a plain `Boolean`: a `Boolean` alone
    // would stay `true` across a *different* conversation later opened the ordinary way unless every one
    // of the three opening paths below remembered to reset it, and a value this hard to accidentally
    // leave stale is worth the one extra string over a bit. `rememberSaveable` for the identical reason
    // `openConversationId` itself is - it must survive a process death exactly as long as the id it
    // qualifies does.
    var readOnlyConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    val stateHolder = rememberSaveableStateHolder()

    // `26-18`: the other half of "a tap opens the thread, never the list" -
    // [AppShellScreen]'s own `AppShellContent` makes sure Диалоги is the tab on screen; this is what
    // actually opens the thread once it is. A `LaunchedEffect` that never completes (`collect` suspends
    // for ever), the identical reasoning that function's own doc comment gives for its matching
    // collector, so a second push arriving while this tab is already showing is caught too, not only the
    // first one this composition ever saw. [PendingConversationOpener.consume] is what stops a later,
    // unrelated recomposition (a rotation, a tab revisit) from re-opening the identical conversation a
    // second time.
    //
    // `26-174`: **the identical [ConversationListViewModel.onRowOpened] call an in-app row tap makes,
    // not a second, weaker open.** `ConversationListRoute`'s own wrapped `onOpenConversation` (that
    // file's own doc comment) is what clears a row's «Новое» pill and unread count the instant a tap
    // opens it — [newlyAssignedIds]/[unreadBumps]/[locallyReadIds] never touched by anything a push
    // does, because a push reaches this composable by writing [PendingConversationOpener] and never
    // passes through `ConversationListRoute`'s composition at all. Before this, a push-opened
    // conversation's row kept showing «Новое» after the operator went back to the list — read, by every
    // other signal (the thread's own `markReadUpTo`, driven by `MessageList`), but this screen's own
    // optimistic badge never heard about it and had nothing forcing a re-fetch to catch up either. Called
    // on [viewModel] directly, the identical instance [ConversationListRoute] shares below, rather than
    // duplicated onto some push-only copy of the same bookkeeping - `onRowOpened` is idempotent (its own
    // doc comment: "unconditional... the common case... must clear exactly the same way"), so nothing
    // about calling it a second time were `ConversationListRoute` ever also reachable for the identical
    // id changes what either call does.
    val pendingConversationOpener = rememberPendingConversationOpener()
    LaunchedEffect(pendingConversationOpener, viewModel) {
        pendingConversationOpener.pendingConversationId.collect { pendingConversationId ->
            if (pendingConversationId != null) {
                viewModel.onRowOpened(pendingConversationId)
                openConversationId = pendingConversationId
                // `26-98`: a push notification always opens the ordinary, writable thread - never the
                // «Все» list's read-only one, which nothing about a push carries a signal for anyway.
                readOnlyConversationId = null
                pendingConversationOpener.consume()
            }
        }
    }

    val currentlyOpen = openConversationId
    if (currentlyOpen == null) {
        stateHolder.SaveableStateProvider(SAVEABLE_KEY_LIST) {
            ConversationListRoute(
                activeSiteId = activeSiteId,
                hubConnectionState = hubConnectionState,
                viewModel = viewModel,
                onOpenConversation = { conversationId ->
                    openConversationId = conversationId
                    readOnlyConversationId = null
                },
                // `26-98`: the «Все» list's own row tap - a genuinely different open than
                // `onOpenConversation` above, not a second call into it, because only this path must
                // set [readOnlyConversationId]. See `ConversationListRoute`'s own doc comment for why
                // this bypasses `ConversationListViewModel.onRowOpened` (the «мои»/«ожидают»-only
                // bookkeeping that callback does has nothing to say about an admin-wide row).
                onOpenAllConversation = { conversationId ->
                    openConversationId = conversationId
                    readOnlyConversationId = conversationId
                },
                onSignOut = onSignOut,
                operatorDisplayName = operatorDisplayName,
                operatorEmail = operatorEmail,
                onOpenSettings = onOpenSettings,
                canSeeAllConversations = canSeeAllConversations,
                canEraseConversations = canEraseConversations,
            )
        }
    } else {
        // `26-98`: `listState.all` joins `mine`/`waiting` in this lookup now that a row on the «Все» tab
        // can genuinely be opened (read-only) - `26-90`'s own comment here, restated until this item,
        // reasoned from a server limitation `GetConversationHistoryAsSiteConfigureHolderQuery` removes.
        // Concatenation order matters only when the identical id somehow appears in more than one list at
        // once (a supervisor's own assigned conversation also showing on the admin-wide list) - `mine`/
        // `waiting` come first so that case still resolves to the live queue row's own data, not the
        // «Все» list's copy of the same fact.
        val readOnly = readOnlyConversationId == currentlyOpen
        val row = (listState.mine + listState.waiting + listState.all).firstOrNull { it.conversationId == currentlyOpen }
        // `26-68`: the old fallback here (`row?.visitorId ?: currentlyOpen`) substituted the
        // conversation's own id into the visitor-id slot whenever `row` was not found yet - a restored
        // thread after process death, or any cold return before the first queue fetch lands - and
        // `VisitorDisplayPrefix` rendered it as if it were a real, plausible visitor short code
        // (`docs/backlog/26-68-*.md`'s own Found). `visitorId` is `null`, genuinely, in that case now -
        // no different from `emojiCreature`/`emojiFood`/`visitorName` two lines below, which were never
        // given a fabricated fallback in the first place. Same reasoning for `hasAttachmentUploadGrant`:
        // it was silently `false` whenever `row` was unknown, hiding a control the operator might
        // actually be entitled to with no sign anything was unknown - `null` now says "not yet known"
        // rather than a stated, confident "no".
        //
        // Both of these are self-healing for the ordinary transient case with no new fetch added here:
        // `ConversationListViewModel.refresh()` already runs unconditionally from that class's own
        // `init` (a fresh instance after process death included, since the ViewModel itself does not
        // survive it - this file's own top-of-file doc comment states `openConversationId` does, the
        // ViewModel does not). Once that answer lands, `listState` updates, this composable recomposes,
        // `row` resolves, and the real `visitorId`/`hasAttachmentUploadGrant` flow down with nothing
        // further to wire - the identical mechanism that already refreshes every other field here.
        //
        // `identityUnavailable` covers the other case that fetch can never resolve: a conversation that
        // has since left both `mine` and `waiting` for good - closed, or reassigned - never matches this
        // lookup again, ever (`docs/backlog/26-68-*.md`'s own Found). `listState.isStale` is what tells
        // "still loading, may yet resolve" apart from "a genuinely fresh answer already confirmed this
        // conversation is not in either half" - it starts `true` and is cleared only inside
        // `ConversationListViewModel.refresh()`'s own `QueueResult.Loaded` branch, which is the one
        // place a real, whole-list answer actually landed (that class's own doc comment on `isStale`:
        // "stale until proven fresh"). No new state is introduced to tell these two cases apart.
        //
        // `26-98`: a read-only open has no `isStale`-shaped freshness flag to lean on - «Все» is keyset-
        // paged incrementally, never re-read whole the way `mine`/`waiting` are, so there is no single
        // moment "the whole list is confirmed current" the way `isStale` clearing marks for the queue.
        // `listState.allHasData` is the nearest fact this list does keep (`false` until its own first
        // page has answered at all, `ConversationListUiState`'s own doc comment) - not "confirmed genuinely
        // gone" so much as "at least one real answer has landed", which is enough here because a read-only
        // open is always reached by tapping a row already rendered on screen: the row existed in `all`
        // the instant this open began, so `allHasData` is already `true` by construction on every path
        // except a restored thread after process death, which is exactly the case this flag exists to
        // cover.
        val identityUnavailable = row == null && (if (readOnly) listState.allHasData else !listState.isStale)
        stateHolder.SaveableStateProvider("$SAVEABLE_KEY_THREAD_PREFIX$currentlyOpen") {
            ThreadRoute(
                conversationId = currentlyOpen,
                visitorId = row?.visitorId,
                emojiCreature = row?.emojiCreature,
                emojiFood = row?.emojiFood,
                visitorName = row?.visitorName,
                createdAt = row?.createdAt,
                conversationState = row?.state,
                hasAttachmentUploadGrant = row?.hasAttachmentUploadGrant,
                identityUnavailable = identityUnavailable,
                canReadContactDetail = canReadContactDetail,
                canSendConversation = canSendConversation,
                canTagConversation = canTagConversation,
                canWriteNote = canWriteNote,
                canGrantAttachmentUpload = canGrantAttachmentUpload,
                canCloseConversation = canCloseConversation,
                canRestrictVisitor = canRestrictVisitor,
                readOnly = readOnly,
                onBack = {
                    stateHolder.removeState("$SAVEABLE_KEY_THREAD_PREFIX$currentlyOpen")
                    openConversationId = null
                },
                viewModel = threadViewModel(),
            )
        }
    }
}

private const val SAVEABLE_KEY_LIST = "conversation-list"
private const val SAVEABLE_KEY_THREAD_PREFIX = "thread:"
