package ago.chat.android.core.network.realtime

/**
 * `26-309`: the operator's own away/online status — a *read* ([getMyPresence]) and a *write*
 * ([setAway]) of one bit of server state, pulled out as its own interface for the identical
 * testability reason [OperatorHubEvents] and [HubConnectionControl] already state for the read and
 * write halves of the connection's own lifecycle: a caller that only ever needs to ask or change
 * availability should be testable against a plain fake, never a real `HubConnection` attempting a
 * real socket.
 *
 * **Why its own interface, not two more methods on [OperatorHubEvents] or [HubConnectionControl].**
 * These are neither a connection-lifecycle action (`connect`/`disconnect`) nor a message/history read —
 * they are a read and a write of *the person's own status*, the same distinction `docs/backlog/26-309-*.md`
 * §3.1 draws to keep "manage the socket" and "set the person's status" from conflating the way §1 of
 * that document rejects for the presence dot itself. [OperatorHubConnection] implements this directly,
 * the identical "one extra `@Provides` binding, not a second connection" shape [OperatorHubEvents]'s own
 * doc comment already states.
 *
 * **No push for the operator's own availability.** Unlike [OperatorHubEvents.assignments] or
 * [OperatorHubEvents.messageDelivered], there is no `MyPresenceChanged` hub callback — the server never
 * tells a connection its own status changed. A caller re-reads [getMyPresence] on every transition into
 * `Connected` (`docs/backlog/26-309-*.md` §3.2, mirroring `ago-console`'s own poll-on-connect) and
 * otherwise trusts its own last write.
 */
public interface OperatorPresenceControl {
    /**
     * `OperatorHub.GetMyPresenceAsync()` — **returns a single "am I away" bit, not the server's three-value
     * `OperatorStatus` enum.** `true` means `Away`; `false` means anything else the server considers
     * reachable (`Online`, in practice, since this is only ever meaningful once this connection is itself
     * `Connected`). [OperatorHubConnection.setAway]'s own doc comment states the same wire-shape choice
     * for the write half.
     */
    public suspend fun getMyPresence(): Boolean

    /**
     * `OperatorHub.SetAwayAsync(bool away)` — `true` calls the server's own `GoAwayAsync`, `false` calls
     * `GoOnlineAsync`. Returns nothing: the server's own method returns `void`, and a caller confirms the
     * new state by reading its own return here having not thrown, exactly the way
     * [OperatorHubConnection.removeTeamMessage] (also a no-return-value hub method) is called.
     */
    public suspend fun setAway(away: Boolean)
}
