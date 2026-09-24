package ago.chat.android.devices

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-18`: `onDeletedMessages()`'s own recovery hook, as a port -
 * [ConversationListViewModel][ago.chat.android.conversations.ConversationListViewModel] is what actually
 * knows how to refresh (its own [refresh][ago.chat.android.conversations.ConversationListViewModel.refresh]),
 * and [AgoPushMessagingService] has no reference to that or any `ViewModel`, the identical "different
 * Android component, no direct line to a `ViewModel` instance" gap [OpenConversationTracker]'s own doc
 * comment states for the opposite direction (a `Service` telling a `ViewModel` something, rather than a
 * `ViewModel` telling a `Service`).
 *
 * A `SharedFlow` **event**, not a `StateFlow`, because "refresh now" is an instruction to run an action
 * once, not a value to hold: a `StateFlow`'s own replay-to-every-new-collector semantics would replay a
 * *previous* request to a `ConversationListViewModel` created fresh long after the push already happened -
 * `SignInViewModel.authorizationRequests`'s own `Channel` makes the identical event-vs-state distinction
 * for launching a Custom Tab intent.
 *
 * **`extraBufferCapacity = 1`, not the default `0`.** `onDeletedMessages()` can fire while
 * `ConversationListViewModel` has not started collecting yet (the operator has not opened Диалоги since
 * launch) - a suspending `emit()` with no buffer would hang the push-handling coroutine waiting for a
 * collector that may never arrive inside the 20-second window. `DROP_OLDEST` costs nothing here: a
 * dropped refresh request is indistinguishable in effect from one that arrived a moment later, since
 * [ConversationListViewModel][ago.chat.android.conversations.ConversationListViewModel]'s own `refresh()`
 * always re-fetches the *current* queue rather than replaying any state carried on the event itself.
 */
public interface ConversationRefreshSignal {
    public val refreshRequests: SharedFlow<Unit>

    public fun requestRefresh()
}

@Singleton
public class DefaultConversationRefreshSignal
    @Inject
    constructor() : ConversationRefreshSignal {
        private val mutableRefreshRequests =
            MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        override val refreshRequests: SharedFlow<Unit> = mutableRefreshRequests.asSharedFlow()

        override fun requestRefresh() {
            mutableRefreshRequests.tryEmit(Unit)
        }
    }

/**
 * The default for [ConversationListViewModel][ago.chat.android.conversations.ConversationListViewModel]'s
 * own constructor - `internal`, for the identical reason [NoOpOpenConversationTracker] exists: this app's
 * back-contract instrumented tests construct that class directly, for reasons unrelated to push, and
 * Hilt's own generated factory always supplies the real [DefaultConversationRefreshSignal] regardless of
 * this Kotlin default.
 */
internal object NoOpConversationRefreshSignal : ConversationRefreshSignal {
    override val refreshRequests: SharedFlow<Unit> = MutableSharedFlow<Unit>().asSharedFlow()

    override fun requestRefresh() {
    }
}
