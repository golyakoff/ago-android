package ago.chat.android.core.network.realtime

/**
 * What a screen may ever ask [OperatorHubConnection] about itself — the identical four-value shape
 * `ago-console`'s own `ConnectionState` already settled on (`operatorConnection.ts`), read here
 * because the underlying transport question is the same one, not because this project prefers to
 * copy strings across languages for its own sake: "connecting" (this connection's first `start()` is
 * in flight), "connected", "reconnecting" (the socket dropped and this class's own backoff loop is
 * retrying), "disconnected" (never started, or stopped on purpose).
 *
 * A sealed interface of our own rather than `com.microsoft.signalr.HubConnectionState` — a screen
 * observing [OperatorHubConnection.state] must not need `com.microsoft.signalr` on its own classpath
 * to render a badge, the same "screens do not own connections" boundary `docs/architecture.md`
 * §Realtime draws, one layer further in. It is also the only way to represent "reconnecting" at all:
 * **this project's `com.microsoft.signalr:signalr` (the Java client) has only three connection states
 * — `CONNECTED`/`CONNECTING`/`DISCONNECTED`, no `RECONNECTING`, and no automatic-reconnect API
 * whatsoever** (no `withAutomaticReconnect`, no `RetryPolicy`, no `onreconnecting`/`onreconnected`) —
 * unlike `@microsoft/signalr`, the JS client `ago-console`/`ago-widget` use, which has all of it. This
 * is a real gap between the two clients from the same vendor, found while implementing this item, not
 * assumed from the JS client's own shape: see [OperatorHubConnection]'s own doc comment for how this
 * class hand-rolls the retry loop `withAutomaticReconnect` would otherwise have provided.
 */
public sealed interface OperatorHubConnectionState {
    /**
     * Never started yet, or [OperatorHubConnection.disconnect] was called. This connection's own
     * reconnect loop ([HubReconnectBackoff]) never gives up on its own, so today the only paths into
     * this state are "never started" and "stopped on purpose", never "the client exhausted its
     * retries".
     */
    public data object Disconnected : OperatorHubConnectionState

    public data object Connecting : OperatorHubConnectionState

    public data object Connected : OperatorHubConnectionState

    /**
     * The socket dropped and the SignalR client's own automatic-reconnect loop is retrying, waiting
     * [HubReconnectBackoff]'s jittered delay between attempts.
     */
    public data object Reconnecting : OperatorHubConnectionState
}
