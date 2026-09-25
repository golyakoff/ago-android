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
    // `26-149`: `conversation:tag`, computed by [AppShellScreen]'s own `conversationsTab` default and
    // threaded straight through to [ThreadRoute] alongside `canReadContactDetail`, which gates the tags
    // section's write affordances on it (hide-not-disable). Defaulted to `false` so every back-contract
    // test constructing this composable directly compiles and behaves unchanged.
    canTagConversation: Boolean = false,
    viewModel: ConversationListViewModel = hiltViewModel(),
    threadViewModel: @Composable () -> ThreadViewModel = { hiltViewModel() },
) {
    val listState by viewModel.state.collectAsStateWithLifecycle()
    var openConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    val stateHolder = rememberSaveableStateHolder()

    // `26-18`: the other half of "a tap opens the thread, never the list" -
    // [AppShellScreen]'s own `AppShellContent` makes sure Диалоги is the tab on screen; this is what
    // actually opens the thread once it is. A `LaunchedEffect` that never completes (`collect` suspends
    // for ever), the identical reasoning that function's own doc comment gives for its matching
    // collector, so a second push arriving while this tab is already showing is caught too, not only the
    // first one this composition ever saw. [PendingConversationOpener.consume] is what stops a later,
    // unrelated recomposition (a rotation, a tab revisit) from re-opening the identical conversation a
    // second time.
    val pendingConversationOpener = rememberPendingConversationOpener()
    LaunchedEffect(pendingConversationOpener) {
        pendingConversationOpener.pendingConversationId.collect { pendingConversationId ->
            if (pendingConversationId != null) {
                openConversationId = pendingConversationId
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
                onOpenConversation = { conversationId -> openConversationId = conversationId },
                onSignOut = onSignOut,
                operatorDisplayName = operatorDisplayName,
                operatorEmail = operatorEmail,
                onOpenSettings = onOpenSettings,
                canSeeAllConversations = canSeeAllConversations,
                canEraseConversations = canEraseConversations,
            )
        }
    } else {
        // `26-90`: deliberately still the two queue lists, not `listState.all` as well - a row on the
        // «Все» tab cannot be opened at all (`ConversationListScreen.AllRow`'s own doc comment: the
        // hub's own join assigns rather than reads), so searching that list here would be searching it
        // for a conversation id it can never be asked about.
        val row = (listState.mine + listState.waiting).firstOrNull { it.conversationId == currentlyOpen }
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
        val identityUnavailable = row == null && !listState.isStale
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
                canTagConversation = canTagConversation,
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
