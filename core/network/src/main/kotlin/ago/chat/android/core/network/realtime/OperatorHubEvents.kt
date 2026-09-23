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

    /**
     * `26-42`: every `MessageDelivered` push — the visitor's own widget acknowledging one of this
     * operator's messages, the live half of the second delivery tick. Unscoped the identical way
     * [assignments] is: the server only ever sends this to the message's own operator, so [ThreadViewModel]
     * (`:app`) is what decides whether the named conversation is the one currently open before applying
     * it to a message already on screen.
     */
    public val messageDelivered: SharedFlow<MessageDeliveredDto>

    /**
     * `26-15`: [OperatorHubConnection.joinConversation], restated on this interface for the identical
     * testability reason every member above it already is — `ThreadViewModel` (`:app`) is the first
     * caller that needs to *act* on the connection, not only observe it, and depending on this
     * interface instead of the concrete class is what lets its own test substitute a fake with no
     * hub connection at all.
     */
    public suspend fun joinConversation(conversationId: String): HistoryPage

    /** [OperatorHubConnection.leaveConversation], restated. */
    public fun leaveConversation()

    /**
     * `26-15`: the backward-keyset "load older messages" page — `ago-console`'s own `loadOlderHistory`,
     * restated. Unlike [joinConversation] (always the newest page), this is `GetHistoryAsync` called
     * directly with a real `beforeSequence` cursor: the strictly-older page whose own `nextBeforeSequence`
     * is `null` once every message in this conversation has been walked (`ConversationReadStore.Sql`,
     * `ago-chat`: `sequence < @BeforeSequence`, `order by sequence desc` — a page is exhausted exactly
     * when it comes back shorter than [pageSize]).
     */
    public suspend fun loadOlderHistory(
        conversationId: String,
        beforeSequence: Long,
        pageSize: Int,
    ): HistoryPage

    /**
     * `26-15`: `OperatorHub.SendMessageAsync`, called with its full four-argument arity every time
     * (`OperatorHub.cs`'s own comment on why a hub method's argument *count* is a wire contract this
     * class must never shorten). The message this call sends is never appended to [messages] directly
     * from its return value — the server's own local echo (`Clients.Caller.SendAsync("MessageReceived"
     * , ...)`, `OperatorHub.SendAsync`) is what actually delivers it back over that flow, the identical
     * "the push is the only path that renders a sent message" shape `ago-console`'s own `sendMessage`
     * already relies on.
     */
    public suspend fun sendMessage(
        conversationId: String,
        body: String,
        clientMessageId: String,
        attachmentId: String? = null,
    ): SendMessageResult

    /**
     * `26-17`: the hub-side half of a site switch. [OperatorHubConnection.ensureConnection] reads the
     * active site only once, at first build, and caches the resulting `HubConnection` forever — so
     * writing a new site through [ago.chat.android.core.domain.identity.ActiveSiteSelection.select]
     * alone re-points the REST header (`ActiveSiteHeaderPlugin` reads that fresh on every request) but
     * leaves the hub connected to whatever site it was first built against. A caller that lets an
     * operator change site — `ago.chat.android.shell.SettingsViewModel` is the first — calls this
     * *after* writing the new selection, so both halves of "which site every subsequent call acts in"
     * move together, per this item's own Done-when.
     *
     * Declared here rather than only on the concrete class for the identical testability reason every
     * other member of this interface already is: a view model that switches sites needs a fake it can
     * assert against, not a real `HubConnection` attempting a real socket.
     */
    public suspend fun reconnectToActiveSite()

    /**
     * `26-54`: every `TeamMessageReceived` push — one tenant-wide room, so unlike [messages] there is
     * no "which conversation is joined" scoping question to answer: every push on this connection
     * belongs to the one team room this operator's site membership already grants. [TeamChatViewModel]
     * (`:app`) is the only planned consumer, and does its own id-based merge/dedup — this stream carries
     * no [MessageSubscription]-style resume record of its own, because there is nothing to resume: see
     * [getTeamDelta]'s own doc comment for why a reconnect catches up by delta instead.
     */
    public val teamMessages: SharedFlow<TeamMessageDto>

    /**
     * `26-54`: every `TeamMessageRemoved` push — a distinct push, distinct method name, never routed
     * through [teamMessages]'s own dedup. The payload is the *same* [TeamMessageDto] already rendered,
     * with [TeamMessageDto.removedAt] now set and [TeamMessageDto.body] redacted to `null` — a caller
     * updates the matching entry it already holds by [TeamMessageDto.id], the same
     * `applyRemoval`/`teamMessageRemovedListener` shape `ago-console`'s own `TeamChatPage.tsx` takes.
     * Removing a message is this item's own Out of scope (`RemoveTeamMessageButton`'s confirmation flow
     * is a separate promise), but a removal by *another* operator must still stop showing this
     * connection's own already-rendered content, which is why this push is wired even with no button
     * on this screen to trigger one.
     */
    public val teamMessageRemovals: SharedFlow<TeamMessageDto>

    /**
     * `26-54`: `OperatorHub.GetTeamHistoryAsync` — the team room's own backward-keyset page,
     * `ago-console`'s own `getTeamHistory` restated. `beforeSequence: null` is the initial "most recent
     * page" load, exactly [loadOlderHistory]'s own convention for a conversation's history.
     */
    public suspend fun getTeamHistory(
        beforeSequence: Long?,
        pageSize: Int,
    ): TeamHistoryPage

    /**
     * `26-54`: `OperatorHub.GetTeamDeltaAsync` — every team message strictly after [afterSequence],
     * oldest first. The team room's own reconnect catch-up: unlike a conversation
     * ([OperatorHubConnection.resumeSubscription]), there is no `JoinConversationAsync` analogue for
     * this room to replay on a fresh socket — `SendTeamMessageAsync`/`GetTeamHistoryAsync` are reachable
     * the moment the connection authenticates, with no server-side group membership to lose — so the
     * only gap a reconnect opens is a [teamMessages] push sent while the socket was down, and a caller
     * already knows the last sequence it rendered. `ago-console`'s own `getTeamDelta`, restated.
     */
    public suspend fun getTeamDelta(afterSequence: Long): TeamHistoryPage

    /**
     * `26-54`: `OperatorHub.SendTeamMessageAsync`, called with its full two-argument arity
     * (`body`, `clientMessageId`) every time — the identical "a hub method's parameter count is a
     * contract" rule [sendMessage] already states, unshortened by this method having fewer arguments
     * than that one. Unlike [sendMessage] there is no conversation id: a site's operator claim already
     * names the one room this connection may ever write to. Reuses [SendMessageResult] rather than a
     * second sealed type — the retry-safety rule that type's own doc comment states (fresh id for
     * [SendMessageResult.NotConnected], the same id for [SendMessageResult.OutcomeUnknown]) is
     * unchanged by which hub method produced the ambiguity. The sent message is never appended to
     * [teamMessages] from this return value directly — the server's own local echo is what actually
     * delivers it back over that flow, [sendMessage]'s own doc comment's shape restated here.
     */
    public suspend fun sendTeamMessage(
        body: String,
        clientMessageId: String,
    ): SendMessageResult

    /**
     * `26-54`: `OperatorHub.RemoveTeamMessageAsync` — declared here so [OperatorHubEvents] carries the
     * team room's whole hub-method surface even though no screen calls this one yet (`RemoveTeamMessageButton`
     * is a separate item's own promise). No return value: the caller's own tab learns the outcome
     * through [teamMessageRemovals], the identical local-echo-via-push shape [sendTeamMessage] takes.
     */
    public suspend fun removeTeamMessage(teamMessageId: String)
}
