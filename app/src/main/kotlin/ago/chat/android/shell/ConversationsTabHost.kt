package ago.chat.android.shell

import ago.chat.android.conversations.ConversationListRoute
import ago.chat.android.conversations.ConversationListViewModel
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.thread.ThreadRoute
import ago.chat.android.thread.ThreadViewModel
import androidx.compose.runtime.Composable
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
    viewModel: ConversationListViewModel = hiltViewModel(),
    threadViewModel: @Composable () -> ThreadViewModel = { hiltViewModel() },
) {
    val listState by viewModel.state.collectAsStateWithLifecycle()
    var openConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    val stateHolder = rememberSaveableStateHolder()

    val currentlyOpen = openConversationId
    if (currentlyOpen == null) {
        stateHolder.SaveableStateProvider(SAVEABLE_KEY_LIST) {
            ConversationListRoute(
                activeSiteId = activeSiteId,
                hubConnectionState = hubConnectionState,
                viewModel = viewModel,
                onOpenConversation = { conversationId -> openConversationId = conversationId },
                onSignOut = onSignOut,
            )
        }
    } else {
        val row = (listState.mine + listState.waiting).firstOrNull { it.conversationId == currentlyOpen }
        stateHolder.SaveableStateProvider("$SAVEABLE_KEY_THREAD_PREFIX$currentlyOpen") {
            ThreadRoute(
                conversationId = currentlyOpen,
                visitorId = row?.visitorId ?: currentlyOpen,
                emojiCreature = row?.emojiCreature,
                emojiFood = row?.emojiFood,
                visitorName = row?.visitorName,
                hasAttachmentUploadGrant = row?.hasAttachmentUploadGrant ?: false,
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
