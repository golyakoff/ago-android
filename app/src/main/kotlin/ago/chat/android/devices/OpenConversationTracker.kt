package ago.chat.android.devices

import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-18`: [decideAlert]'s own `openConversationId` input, as a port - `ThreadViewModel` is the one class
 * that actually knows which conversation is open (its own private `openConversationId` field, set in
 * [ThreadViewModel.open][ago.chat.android.thread.ThreadViewModel.open] and cleared in
 * [ThreadViewModel.close][ago.chat.android.thread.ThreadViewModel.close]/`onCleared`), and
 * [AgoPushMessagingService] runs in a different Android component entirely with no reference to that - or
 * any - `ViewModel` instance. A `@Singleton` seam is what lets the one write the other reads, without
 * either depending on the other's concrete type.
 *
 * **Why this and not a `StateFlow` read directly off `ThreadViewModel`.** Hilt scopes a `ViewModel` to its
 * own back-stack entry ([ThreadViewModel]'s own doc comment on why [ago.chat.android.shell
 * .ConversationsTabHost]'s `threadViewModel` parameter is a `@Composable` provider rather than a plain
 * value) - there is no single, always-current instance a `Service` could hold a reference to across the
 * thread being opened, closed, and re-opened for a different conversation. A small, genuinely
 * process-lifetime singleton is the correct shape for "which conversation, if any, is open right now",
 * independent of which `ThreadViewModel` instance is currently alive.
 */
public interface OpenConversationTracker {
    /** The conversation [ThreadViewModel.open][ago.chat.android.thread.ThreadViewModel.open] most
     * recently opened and has not yet closed - `null` before the first one, and after every close. */
    public val currentConversationId: String?

    public fun conversationOpened(conversationId: String)

    /** A no-op unless [conversationId] is the one currently recorded - see
     * [DefaultOpenConversationTracker]'s own doc comment for the race this guard exists to close. */
    public fun conversationClosed(conversationId: String)
}

/**
 * `@Volatile`, not a `StateFlow`: the identical reasoning [ProcessLifecycleForegroundTracker]'s own doc
 * comment gives for its own field - a background coroutine wants one synchronous read, never a stream.
 *
 * **[conversationClosed]'s own guard is not decoration.** `ThreadViewModel.open` on a *different*
 * conversation can run before the previous instance's own `onCleared`/`close` backstop fires (a
 * `ConversationsTabHost` switch from thread A straight to thread B, say) - without the equality check, B's
 * own `conversationOpened` could be overwritten by A's late `conversationClosed`, leaving this tracker
 * reporting "nothing open" while B genuinely is. Comparing against [conversationId] before clearing is
 * what keeps a stale close from ever winning over a newer open.
 */
@Singleton
public class DefaultOpenConversationTracker
    @Inject
    constructor() : OpenConversationTracker {
        @Volatile
        private var current: String? = null

        override val currentConversationId: String?
            get() = current

        override fun conversationOpened(conversationId: String) {
            current = conversationId
        }

        override fun conversationClosed(conversationId: String) {
            if (current == conversationId) {
                current = null
            }
        }
    }

/**
 * The default for [ThreadViewModel][ago.chat.android.thread.ThreadViewModel]'s own constructor -
 * `internal`, since only that class's own test call sites (this app's back-contract instrumented tests,
 * which construct it directly, bypassing Hilt, for reasons unrelated to push - `AppShellScreen`'s own doc
 * comment on why those tests substitute trivial slots at all) ever need a value at all. Production wiring
 * always resolves the real [DefaultOpenConversationTracker] through `di/AppModule`'s own `@Provides`, and
 * Hilt's generated factory supplies every constructor parameter explicitly regardless of a Kotlin default
 * - this object is never reached by a real build. See [ThreadViewModel]'s own doc comment on this
 * parameter for the trade-off this default is making.
 */
internal object NoOpOpenConversationTracker : OpenConversationTracker {
    override val currentConversationId: String? = null

    override fun conversationOpened(conversationId: String) {
    }

    override fun conversationClosed(conversationId: String) {
    }
}
