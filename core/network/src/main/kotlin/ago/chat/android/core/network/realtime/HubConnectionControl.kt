package ago.chat.android.core.network.realtime

/**
 * `26-85`: the write half of [OperatorHubConnection]'s own lifecycle, pulled out as its own interface
 * for the identical testability reason [OperatorHubEvents] already states for the read half — a caller
 * that only ever needs to start or stop the connection, never read [OperatorHubConnection.state] or call
 * a hub method, should be testable against a plain fake rather than a real `HubConnection` attempting a
 * real socket.
 *
 * [ago.chat.android.realtime.OperatorHubConnectionLifecycle] is this interface's first caller. `26-85`
 * gives its own `onStop` a second signal to consult (`ago.chat.android.presence.OperatorPresenceGate`)
 * before ever calling [disconnect] — proving that decision is exactly what a unit test needs a fake
 * connection for, with no network attempt anywhere in the way.
 *
 * [OperatorHubConnection] implements this directly, the identical "one extra `@Provides` binding, not a
 * second connection" shape [OperatorHubEvents]'s own doc comment already states.
 */
public interface HubConnectionControl {
    /** [OperatorHubConnection.connect], restated. */
    public suspend fun connect()

    /** [OperatorHubConnection.disconnect], restated. */
    public suspend fun disconnect()
}
