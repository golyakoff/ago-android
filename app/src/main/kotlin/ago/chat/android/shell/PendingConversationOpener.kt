package ago.chat.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-18`: "A tap opens the thread, never the list" - the bridge between a notification's own
 * `PendingIntent` (delivered to `MainActivity.onCreate`/`onNewIntent`, a plain Android component with no
 * reference to any `NavController`) and [ago.chat.android.shell.AppShellScreen]'s real navigation graph,
 * three layers of composition away.
 *
 * **Not the same job as [ago.chat.android.devices.OpenConversationTracker].** That interface answers "is
 * this conversation open *right now*", read by [decideAlert][ago.chat.android.devices.decideAlert] the
 * instant a push arrives; this one answers "was the app just told to open a conversation it does not
 * necessarily have open yet", read by the navigation layer once, on the next composition. Conflating the
 * two would mean a notification tap has to simultaneously mean both "a conversation is open" (which would
 * suppress the *next* push for it, wrongly, before the operator has actually looked at anything) and
 * "please navigate there" - two different questions this app already keeps separate for the identical
 * reason `ConversationListViewModel`'s own doc comment gives for [ago.chat.android.conversations
 * .ConversationListViewModel.newlyAssignedIds] and [ago.chat.android.conversations
 * .ConversationListViewModel.unreadBumps] being two different fields rather than one.
 *
 * **A `StateFlow`, not a one-shot `Channel`.** [ago.chat.android.shell.AppShellScreen]'s `AppShellContent`
 * and [ago.chat.android.shell.ConversationsTabHost] each need their own, independent look at the current
 * pending id - the first to navigate the bottom bar to Диалоги, the second to actually open the thread and
 * [consume] it - and a `Channel` only ever delivers one element to whichever collector happens to receive
 * it first, the wrong shape for two collectors that both need to see it. A `StateFlow`'s own replay-to-
 * every-collector semantics is what lets both react to the identical value regardless of which one
 * subscribed first, which matters concretely on a cold start: both collectors begin running at roughly the
 * same composition pass, before the sign-in flow has even resolved which one, if either, is currently
 * live.
 */
public interface PendingConversationOpener {
    public val pendingConversationId: StateFlow<String?>

    /** `MainActivity`'s own call, from `onCreate`/`onNewIntent` - records that a tap asked to open
     * [conversationId], overwriting whatever was pending before (a second tap while the first has not
     * been consumed yet is the newer instruction, not a queue). */
    public fun open(conversationId: String)

    /** [ago.chat.android.shell.ConversationsTabHost]'s own call, once it has actually opened the thread -
     * clears the pending value so a later, unrelated recomposition does not re-open the identical
     * conversation a second time. */
    public fun consume()
}

@Singleton
public class DefaultPendingConversationOpener
    @Inject
    constructor() : PendingConversationOpener {
        private val mutablePendingConversationId = MutableStateFlow<String?>(null)
        override val pendingConversationId: StateFlow<String?> = mutablePendingConversationId.asStateFlow()

        override fun open(conversationId: String) {
            mutablePendingConversationId.value = conversationId
        }

        override fun consume() {
            mutablePendingConversationId.value = null
        }
    }

/**
 * `DeviceRegistrationWorker`'s own `EntryPointAccessors` shape, reused for a different reason: not
 * `WorkManager` bypassing Hilt's component tree, but a plain `@Composable` needing a `@Singleton` that is
 * not a `ViewModel` at all - [AppShellScreen]'s own `AppShellContent` (which owns the `NavController`,
 * for the tab switch) and [ConversationsTabHost] (which owns the thread/list toggle, for the actual open)
 * are two different composables, neither of which is the natural home for a new `ViewModel` whose entire
 * job would be relaying one `StateFlow` one level further down - `hiltViewModel()` exists for a screen's
 * own state machine, not as a generic service locator. [rememberPendingConversationOpener] reads the
 * identical, already-running production singleton `AgoChatApplication.onCreate` initialised via Hilt at
 * process start, straight through `LocalContext.current.applicationContext` - which resolves correctly
 * even for this app's own back-contract instrumented tests: those construct [ConversationsTabHost] and
 * [AppShellScreen] directly with no Hilt component of their *own* (`BackContractDialogsTabTest`'s own doc
 * comment), but still run inside the real, compiled `AgoChatApplication`, whose Hilt graph is live
 * regardless of what any one test file injects for itself.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
public interface PendingConversationOpenerEntryPoint {
    public fun pendingConversationOpener(): PendingConversationOpener
}

@Composable
public fun rememberPendingConversationOpener(): PendingConversationOpener {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors
            .fromApplication(context, PendingConversationOpenerEntryPoint::class.java)
            .pendingConversationOpener()
    }
}
