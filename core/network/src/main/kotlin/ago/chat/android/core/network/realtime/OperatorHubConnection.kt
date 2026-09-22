package ago.chat.android.core.network.realtime

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.network.auth.AccessTokenProvider
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * One `/hubs/operator` connection for the app's whole signed-in session —
 * `docs/architecture.md` §Realtime: "owned by a single connection holder in `:core:network` and
 * observed as a Kotlin `Flow` by whatever screen is on top. Screens do not own connections." This
 * class is that holder, and a screen (or a future feature module) only ever sees [state] and
 * [messages] — never a `com.microsoft.signalr.HubConnection` of its own.
 *
 * A `@Singleton` in `:app`'s object graph (`di/AppModule`), which is what makes "one connection per
 * signed-in session" true structurally rather than by convention: an `Activity` recreated by a
 * rotation gets a fresh `ViewModel`/Compose tree but the identical instance of this class, and
 * [ensureConnection]'s own "build the underlying connection at most once" behaviour (mirroring
 * `ago-console`'s `OperatorConnection.ensureConnection`) means a rotation can only ever call
 * [connect] on a connection that already exists — never construct a second one.
 *
 * ## This client has no automatic reconnect — found while building this, not assumed
 *
 * `ago-console`/`ago-widget` build on `@microsoft/signalr` (the JS client), whose
 * `withAutomaticReconnect(...)` this backlog item's own language ("reconnect with the client's own
 * backoff") describes. **`com.microsoft.signalr:signalr` 9.0.5 — the Android/JVM client `adr/0178`
 * chose — has no such API**: `HubConnectionState` has exactly three values
 * (`CONNECTED`/`CONNECTING`/`DISCONNECTED`, no `RECONNECTING`), and `HubConnection` exposes only
 * `onClosed`, no `RetryPolicy`, `withAutomaticReconnect`, `onreconnecting` or `onreconnected` of any
 * kind — confirmed by decompiling the actual 9.0.5 jar, not by reading its docs. So this class
 * hand-rolls the retry loop the JS client would otherwise provide: [onConnectionClosed] fires from
 * `HubConnection.onClosed`, and unless [disconnect] requested the stop, [attemptReconnect] restarts
 * the *same* `HubConnection` object after [HubReconnectBackoff]'s jittered delay, recursing on a
 * failed restart, exactly the way `@microsoft/signalr`'s own `nextRetryDelayInMilliseconds` callback
 * is invoked again after a failed attempt. `OperatorHubConnectionState.Reconnecting` is entirely this
 * class's own bookkeeping — the library never reports that state itself.
 *
 * ## The four properties this item exists to prove
 *
 * 1. **The access-token factory re-reads the current token on every negotiate** — [freshAccessTokenSingle]
 *    is the identical `5-16` discipline `AccessTokenProvider`'s own doc comment states for the REST
 *    client, carried to a transport whose async shape is RxJava rather than a Ktor send pipeline.
 * 2. **The active-site value rides the connection URL's own query string** ([buildHubUrl]), read once
 *    at the moment this class actually builds a connection ([ensureConnection]) — never a header,
 *    because a query string is how `OperatorIdentityClaimsTransformation` reads it on this hub
 *    (`docs/architecture.md` §Tenancy), the identical constraint that already put the bearer token in
 *    the query string instead of an `Authorization` header for a WebSocket upgrade.
 * 3. **Reconnect uses [HubReconnectBackoff]'s full-jitter delay sequence**, hand-driven as described
 *    above rather than handed to a library retry policy that does not exist here.
 * 4. **A reconnect re-reads history from the last known `sequence`**, never trusting what is in
 *    memory ([resumeSubscription], replayed both by [connect]'s own first `start()` — a no-op, nothing
 *    is subscribed yet — and by every successful [attemptReconnect]) — [MessageSubscription] is where
 *    the dedup/ordering half of that guarantee actually lives, kept dependency-free so it is testable
 *    with no hub connection at all.
 *
 * `JoinConversationAsync` is the one hub method this class calls — an existing method, called with
 * its existing two-argument arity (`conversationId`, `lastKnownSequence`) on every call, never fewer:
 * `docs/architecture/realtime.md`'s "a hub method's parameter count is a contract" rule, which this
 * class never has occasion to violate because it invents no new argument for it.
 *
 * What this class deliberately does **not** do, because `26-13`'s own Out of scope says so:
 * `SendMessageAsync` (no send path — this item proves the transport receives, not that an operator
 * can compose), `SetAwayAsync`/presence, and the team chat's own hub traffic.
 */
public class OperatorHubConnection(
    private val hubUrl: String,
    private val accessTokens: AccessTokenProvider,
    private val activeSite: ActiveSiteSelection,
    private val backoff: HubReconnectBackoff = HubReconnectBackoff(),
) {
    private val buildLock = Mutex()
    private val subscription = MessageSubscription()
    private val reconnectAttempt = AtomicInteger(0)

    /** Set before every deliberate [disconnect] and cleared by every successful [connect], so
     * [onConnectionClosed] can tell "the operator backgrounded the app" from "the network dropped"
     * apart — only the second one should ever start a reconnect loop. */
    @Volatile
    private var stopRequested = false

    private val mutableState = MutableStateFlow<OperatorHubConnectionState>(OperatorHubConnectionState.Disconnected)

    /** The one thing every screen — and every future feature module — ever observes about this
     * connection's own health. Never a `com.microsoft.signalr` type; see this class's own doc
     * comment for why. */
    public val state: StateFlow<OperatorHubConnectionState> = mutableState.asStateFlow()

    private val mutableMessages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 64)

    /** Every `MessageReceived` push this connection has decided is new and belongs to whichever
     * conversation is currently joined ([joinConversation]) — already ordered and deduplicated by
     * [MessageSubscription]. `26-14`/`26-15` are the real listeners; this item's own tests are the
     * only consumer so far. */
    public val messages: SharedFlow<MessageDto> = mutableMessages.asSharedFlow()

    @Volatile
    private var connection: HubConnection? = null

    /** Cancelled and rebuilt around each `disconnect()`/`connect()` pair so that a reconnect attempt
     * or a resume triggered by a *previous* connection lifetime can never run after the operator has
     * backgrounded the app and this class has moved on. */
    private var callbackScope = newCallbackScope()

    /** Idempotent: a second call while already connecting/connected is a no-op, which is what makes
     * it safe for [OperatorHubConnectionLifecycle] (or a rotation re-observing this singleton) to call
     * this on every foreground transition without risking a second connection or a "HubConnection
     * already active" exception from the library itself. */
    public suspend fun connect() {
        val hub = buildLock.withLock { ensureConnection() }
        if (hub.connectionState != HubConnectionState.DISCONNECTED) {
            return
        }

        stopRequested = false
        mutableState.value = OperatorHubConnectionState.Connecting
        hub.start().await()
        resumeSubscription(hub)
        reconnectAttempt.set(0)
        mutableState.value = OperatorHubConnectionState.Connected
    }

    /** Idempotent: a call with nothing built yet, or a connection already down, is a no-op. Marks the
     * stop as deliberate first, so the `onClosed` callback this triggers does not start a reconnect
     * loop for a connection the app itself chose to let go of (`docs/architecture.md`: "Android will
     * suspend a background socket... the app connects when it is in front and lets go when it is
     * not"). */
    public suspend fun disconnect() {
        stopRequested = true
        callbackScope.cancel()
        callbackScope = newCallbackScope()

        val hub = connection ?: return
        if (hub.connectionState != HubConnectionState.DISCONNECTED) {
            hub.stop().await()
        }
        mutableState.value = OperatorHubConnectionState.Disconnected
    }

    /**
     * Opens (or resumes, on the next reconnect) the given conversation. Mirrors `ago-console`'s own
     * `joinConversation`: this call's own return value is the initial page for the caller to render
     * directly, so those messages are recorded as already delivered ([MessageSubscription.markAlreadyDelivered])
     * rather than re-emitted through [messages] a second time.
     */
    public suspend fun joinConversation(conversationId: String): HistoryPage {
        subscription.join(conversationId)
        val page =
            requireConnection()
                .invoke(HistoryPage::class.java, JOIN_CONVERSATION_METHOD, conversationId, null)
                .await()
        subscription.markAlreadyDelivered(page.messages)
        return page
    }

    /** Stops routing pushes to [messages] and stops a later reconnect from re-joining a conversation
     * nobody is looking at any more — `ago-console`'s own `leaveConversation`, restated. */
    public fun leaveConversation() {
        subscription.leave()
    }

    // ------------------------------------------------------------------------------ internals

    private fun requireConnection(): HubConnection = connection ?: error("OperatorHubConnection: connect() has not been called yet.")

    /**
     * Builds the underlying `HubConnection` on first use, never earlier — the identical "read the
     * active-site signal at first `start()`, not at construction" timing `ago-console`'s own
     * `ensureConnection` uses and for the identical reason (`13-07`/`adr/0068`): the active site is
     * not reliably known yet at the point this object is constructed (Hilt builds the whole singleton
     * graph well before a session, let alone a chosen site, exists).
     *
     * Built at **most once**: every later `connect()` on this same instance — including one reached
     * after a rotation, since this class is a `@Singleton` — reuses the identical `HubConnection`
     * object. Guarded by [buildLock] rather than merely checking-then-setting the field, since two
     * concurrent first calls to [connect] (a plausible race the moment a lifecycle observer and a
     * screen both call it around the same app-start instant) must build exactly one connection.
     *
     * `internal`, not `private`: `HubConnectionBuilder.create(url).build()` does no network I/O of its
     * own (negotiation only happens on `start()`), so `OperatorHubConnectionTest` calls this directly
     * to prove the "built at most once" property with no server, no emulator and no coroutine involved.
     */
    internal fun ensureConnection(): HubConnection {
        connection?.let { return it }

        val url = buildHubUrl(hubUrl, activeSite.currentSiteId())
        val hub =
            HubConnectionBuilder
                .create(url)
                .withAccessTokenProvider(freshAccessTokenSingle(accessTokens))
                .build()

        hub.on(MESSAGE_RECEIVED_METHOD, { dto: MessageDto -> handleIncoming(dto) }, MessageDto::class.java)
        hub.onClosed { onConnectionClosed(hub) }

        connection = hub
        return hub
    }

    /**
     * `HubConnection.onClosed`'s own callback — the only lifecycle signal this library gives at all
     * (see this class's own doc comment on the missing automatic-reconnect API). Fires both for a
     * deliberate [disconnect] and for a real network drop; [stopRequested] is what tells them apart.
     */
    private fun onConnectionClosed(hub: HubConnection) {
        mutableState.value = OperatorHubConnectionState.Disconnected
        if (!stopRequested) {
            attemptReconnect(hub)
        }
    }

    /**
     * The hand-rolled half of "reconnect with the client's own backoff and full jitter": waits
     * [HubReconnectBackoff]'s jittered delay for the next attempt number, then restarts the *same*
     * `HubConnection` object. A failed restart recurses into another attempt (never gives up on its
     * own, matching [OperatorHubConnectionState.Disconnected]'s own doc comment); a successful one
     * replays [resumeSubscription] before announcing `Connected`, the identical "resume from
     * `lastKnownSequence` before telling anyone the link is healthy again" ordering
     * `ago-console`'s own `resumeAfterReconnect` uses.
     */
    private fun attemptReconnect(hub: HubConnection) {
        val attempt = reconnectAttempt.incrementAndGet()
        val delayMs = backoff.delayMillisFor(attempt)
        mutableState.value = OperatorHubConnectionState.Reconnecting

        callbackScope.launch {
            delay(delayMs)
            if (stopRequested) {
                return@launch
            }

            try {
                hub.start().await()
                resumeSubscription(hub)
                reconnectAttempt.set(0)
                mutableState.value = OperatorHubConnectionState.Connected
            } catch (failure: Exception) {
                if (!stopRequested) {
                    attemptReconnect(hub)
                }
            }
        }
    }

    /**
     * The one replay of [subscription]'s own record — called by [connect]'s first `start()` (a no-op:
     * nothing is subscribed on a fresh connection) and by every successful [attemptReconnect] after it
     * (the whole point: a hub connection that comes back up has no server-side group membership from
     * its previous life). Asks for the delta after [MessageSubscription.lastKnownSequence] rather than
     * re-joining from nothing, so a resume appends what was missed instead of discarding and
     * re-fetching the whole conversation.
     */
    private suspend fun resumeSubscription(hub: HubConnection) {
        val conversationId = subscription.conversationId ?: return
        val page =
            hub
                .invoke(HistoryPage::class.java, JOIN_CONVERSATION_METHOD, conversationId, subscription.lastKnownSequence)
                .await()
        for (message in subscription.acceptResumeDelta(page.messages)) {
            mutableMessages.tryEmit(message)
        }
    }

    private fun handleIncoming(dto: MessageDto) {
        subscription.accept(dto)?.let { mutableMessages.tryEmit(it) }
    }

    private fun newCallbackScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val JOIN_CONVERSATION_METHOD = "JoinConversationAsync"
        const val MESSAGE_RECEIVED_METHOD = "MessageReceived"
    }
}
