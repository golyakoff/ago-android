package ago.chat.android.conversations

import ago.chat.android.devices.ConversationRefreshSignal
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-61`: the conversation list's own "the operator just came back" signal — `docs/backlog/26-61-*.md`'s
 * own Found section traced the bug to there being no such signal at all: `ConversationListRoute`'s
 * `DisposableEffect`/`LifecycleEventObserver` only ever calls
 * [ConversationListViewModel.onScreenStarted], and that method's whole job is starting the «Ожидают»
 * poll — it never re-reads the queue itself, so an hour-old list stayed on screen indefinitely after a
 * real background/foreground cycle.
 *
 * **`ProcessLifecycleOwner`, not the screen's own `LocalLifecycleOwner`, and not folded into
 * [ConversationListViewModel.onScreenStarted] itself.** That method's own caller
 * ([ConversationListRoute]) observes the *Activity-scoped* lifecycle, which fires `ON_START` on a device
 * rotation too — this item's own Scope item 2 requires that a rotation must never issue a queue fetch,
 * which is exactly the same "the wrong signal, not merely one that needs filtering after the fact"
 * reasoning [ago.chat.android.realtime.OperatorHubConnectionLifecycle]'s own doc comment gives for using
 * [ProcessLifecycleOwner] instead of `MainActivity`'s own `onStart`/`onStop`, and the reason
 * [ago.chat.android.devices.ProcessLifecycleForegroundTracker] exists as a second, independent observer
 * of the identical signal rather than one shared with the hub connection's own class.
 * [ProcessLifecycleOwner] reports the *process's* foreground state and produces no event at all for a
 * rotation, so no rotation-detecting logic is needed anywhere downstream of this class — the wrong
 * signal is simply never wired to the queue fetch in the first place.
 *
 * **Fires into the existing [ConversationRefreshSignal], rather than a second port with the identical
 * shape.** `26-18` already built that `SharedFlow` for `AgoPushMessagingService.onDeletedMessages()`'s
 * own recovery hook, and [ConversationListViewModel]'s own `init` already collects
 * [ConversationRefreshSignal.refreshRequests] into a `refresh()` call — "something changed outside this
 * screen's own view of the world, re-ask for the truth" is exactly what a foreground return is too, so
 * this class is a second producer on that bus rather than a reason to touch
 * [ConversationListViewModel] at all. [ConversationListViewModel.refresh] already re-reads both halves
 * of the queue regardless of which tab is selected, already refuses to duplicate a fetch already in
 * flight ([ConversationListViewModel]'s own `isRefreshing` guard), and already leaves a failed refresh on
 * `26-60`'s own retry body rather than retrying on its own — the three other Done-when boxes this item
 * carries, satisfied for free by reusing that method rather than writing a second, parallel load path.
 *
 * **The very first `onStart` is skipped.** That is the *process* starting, not a return from the
 * background: [ConversationListViewModel]'s own `init` already issues its own first, unconditional
 * `refresh()` the moment it is constructed, and [hasSeenFirstStart] is what stops this class from queuing
 * a second, redundant one right behind it. Without it, [ConversationRefreshSignal]'s own
 * `extraBufferCapacity = 1` (`ConversationRefreshSignal.kt`'s own doc comment: a dropped/buffered
 * emission is indistinguishable from one that arrives a moment later) would still deliver that first,
 * spurious signal the instant [ConversationListViewModel] starts collecting — however long after cold
 * start that construction actually happens to be, since Диалоги is not necessarily the first screen a
 * freshly launched, signed-out app shows.
 */
@Singleton
public class ConversationListForegroundRefreshTrigger
    @Inject
    constructor(
        private val refreshSignal: ConversationRefreshSignal,
    ) : DefaultLifecycleObserver {
        private var hasSeenFirstStart = false

        /** Called once, from `AgoChatApplication.onCreate` — registering more than once would add a
         * second observer and request a refresh twice per genuine foreground return, which is harmless
         * (the extra event collapses into the same in-flight-guarded `refresh()` call) but is not the
         * intended shape, the same "harmless but not intended" note
         * [ago.chat.android.realtime.OperatorHubConnectionLifecycle.start]'s own doc comment states for
         * the identical registration pattern. */
        public fun start() {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }

        override fun onStart(owner: LifecycleOwner) {
            if (!hasSeenFirstStart) {
                hasSeenFirstStart = true
                return
            }
            refreshSignal.requestRefresh()
        }
    }
