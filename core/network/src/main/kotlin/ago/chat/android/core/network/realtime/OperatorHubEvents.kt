package ago.chat.android.core.network.realtime

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * `26-14`: the observable surface of [OperatorHubConnection], pulled out as its own interface for one
 * reason — testability. `ConversationListViewModel` (`:app`) is the first real screen to react to hub
 * events rather than merely display a connection badge, and a view model that depended on the concrete
 * `OperatorHubConnection` could only ever be tested against a real `com.microsoft.signalr.HubConnection`
 * (network, or at minimum a running `Ago.Chat.Api`) — the exact gap this worktree was told not to try to
 * close (see this item's own hand-off notes). Depending on this interface instead is what lets a test
 * substitute a plain fake built from real `MutableStateFlow`/`MutableSharedFlow` values, the same "a fake
 * is easy to substitute because the real thing already exposes plain Flows" shape this item's own brief
 * names.
 *
 * [OperatorHubConnection] implements this directly rather than the screen depending on three separately
 * injected `Flow`s, so `di/AppModule` (`:app`) needs exactly one extra `@Provides` binding this interface
 * to that one `@Singleton`, not three.
 */
public interface OperatorHubEvents {
    /** [OperatorHubConnection.state], restated. */
    public val state: StateFlow<OperatorHubConnectionState>

    /** [OperatorHubConnection.messages], restated — scoped to whichever one conversation is currently
     * joined ([OperatorHubConnection.joinConversation]), deduplicated and ordered by `sequence`. `26-15`'s
     * own consumer, not this item's. */
    public val messages: SharedFlow<MessageDto>

    /**
     * `26-14`: every `MessageReceived` push this connection receives, for *every* conversation this
     * operator is assigned to — not only whichever one is joined. Mirrors `ago-console`'s own split
     * exactly: `operatorConnection.ts` keeps [messages]'s join-scoped, deduplicated listener (`onMessage`,
     * `26-15`'s concern) and a second, unscoped one (`onAnyMessage`) side by side, and
     * `WorkspaceLayout.tsx`'s own doc comment on `onAnyMessage` is why: "the console cannot know that a
     * second conversation has a new visitor message... without it", which is exactly this screen's own
     * unread-count problem.
     *
     * Deliberately **not** run through [MessageSubscription]'s dedup/resume machinery: that class exists
     * to make one conversation's own message stream exactly-once and gap-free for `26-15`'s thread screen,
     * and widening its "only the joined conversation" rule to "every assigned conversation" would change
     * a guarantee `26-13`'s own tests already lock in for a screen that does not need it. A message
     * arriving twice here (the ordinary at-least-once case, `docs/architecture/realtime.md`) costs this
     * screen one extra unread count that the next `GET /api/v1/conversations/queue` corrects — a cosmetic
     * staleness, the same tolerance `ago-console`'s own `markRead` failure handling already accepts for an
     * identical reason.
     */
    public val allMessages: SharedFlow<MessageDto>

    /** `26-14`: every `ConversationAssigned` push — `4-02`'s automatic engine and `23-04`'s manual claim
     * alike. Unscoped by construction: the server only ever sends this to the newly-assigned operator's
     * own connections, so there is no "which conversation is open" question to answer before delivering
     * it, unlike [messages]. */
    public val assignments: SharedFlow<ConversationAssignedDto>
}
