package ago.chat.android.core.network.realtime

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.network.auth.AccessTokenProvider
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import kotlinx.coroutines.CancellationException
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
 * `JoinConversationAsync` was, in `26-13`, the one hub method this class called — with its existing
 * two-argument arity (`conversationId`, `lastKnownSequence`) on every call, never fewer:
 * `docs/architecture/realtime.md`'s "a hub method's parameter count is a contract" rule. `26-15` adds
 * two more real callers of that same rule, never violating it: [loadOlderHistory] always sends
 * `GetHistoryAsync`'s full three arguments, and [sendMessage] always sends `SendMessageAsync`'s full
 * four — see each method's own doc comment. `26-54` adds the team room's own four methods
 * ([getTeamHistory], [getTeamDelta], [sendTeamMessage], [removeTeamMessage]), each with its own smaller
 * but equally fixed arity.
 *
 * What this class deliberately still does **not** do: `SetAwayAsync`/presence — no backlog item has
 * reached it yet.
 */
public class OperatorHubConnection(
    private val hubUrl: String,
    private val accessTokens: AccessTokenProvider,
    private val activeSite: ActiveSiteSelection,
    private val backoff: HubReconnectBackoff = HubReconnectBackoff(),
) : OperatorHubEvents {
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
    public override val state: StateFlow<OperatorHubConnectionState> = mutableState.asStateFlow()

    private val mutableMessages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 64)

    /** Every `MessageReceived` push this connection has decided is new and belongs to whichever
     * conversation is currently joined ([joinConversation]) — already ordered and deduplicated by
     * [MessageSubscription]. `26-15` is the real listener; this item's own tests are the
     * only consumer so far. */
    public override val messages: SharedFlow<MessageDto> = mutableMessages.asSharedFlow()

    private val mutableAllMessages = MutableSharedFlow<MessageDto>(extraBufferCapacity = 64)

    /** [OperatorHubEvents.allMessages] — see that property's own doc comment for why this is a second,
     * undeduplicated flow rather than a widening of [messages]/[MessageSubscription]. */
    public override val allMessages: SharedFlow<MessageDto> = mutableAllMessages.asSharedFlow()

    private val mutableAssignments = MutableSharedFlow<ConversationAssignedDto>(extraBufferCapacity = 16)

    /** [OperatorHubEvents.assignments]. */
    public override val assignments: SharedFlow<ConversationAssignedDto> = mutableAssignments.asSharedFlow()

    private val mutableMessageDelivered = MutableSharedFlow<MessageDeliveredDto>(extraBufferCapacity = 16)

    /** [OperatorHubEvents.messageDelivered]. */
    public override val messageDelivered: SharedFlow<MessageDeliveredDto> = mutableMessageDelivered.asSharedFlow()

    private val mutableTeamMessages = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 64)

    /** [OperatorHubEvents.teamMessages]. */
    public override val teamMessages: SharedFlow<TeamMessageDto> = mutableTeamMessages.asSharedFlow()

    private val mutableTeamMessageRemovals = MutableSharedFlow<TeamMessageDto>(extraBufferCapacity = 16)

    /** [OperatorHubEvents.teamMessageRemovals]. */
    public override val teamMessageRemovals: SharedFlow<TeamMessageDto> = mutableTeamMessageRemovals.asSharedFlow()

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
    public override suspend fun joinConversation(conversationId: String): HistoryPage {
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
    public override fun leaveConversation() {
        subscription.leave()
    }

    /**
     * `26-15`: `GetHistoryAsync`, called directly rather than through [joinConversation] — the
     * "load older messages" page a thread screen asks for as the operator scrolls up, never the
     * fresh/resume page [joinConversation] and reconnect already own. The result is recorded through
     * the identical [MessageSubscription.markAlreadyDelivered] path — mirroring `ago-console`'s own
     * `loadOlderHistory`, which feeds every fetched id through `seenMessageIds.markSeen` too — so a
     * message a live push later redelivers (a fan-out race landing after this page did) is still
     * caught, and so [MessageSubscription]'s sequence tracker only ever advances, never regresses:
     * [HubSequenceTracker.observe] already ignores an older sequence, so replaying history here can
     * never move "resume from" backwards.
     */
    public override suspend fun loadOlderHistory(
        conversationId: String,
        beforeSequence: Long,
        pageSize: Int,
    ): HistoryPage {
        val page =
            requireConnection()
                .invoke(HistoryPage::class.java, GET_HISTORY_METHOD, conversationId, beforeSequence, pageSize)
                .await()
        subscription.markAlreadyDelivered(page.messages)
        return page
    }

    /**
     * `26-15`: `OperatorHub.SendMessageAsync`, called with its full four-argument arity
     * (`conversationId`, `body`, `attachmentId`, `clientMessageId`) every time — never fewer, per that
     * method's own comment on why appending is safe for a caller like this one but omitting a
     * trailing argument is not once a method has more than this class ever sends.
     *
     * Mirrors `ago-console`'s own `sendMessage`/`OperatorConnection.sendMessage` exactly:
     * not-yet-connected and dropped-mid-invoke are told apart by whether the connection is still
     * `CONNECTED` *after* the failure, because only that distinguishes "nothing was sent" (safe to
     * retry with a fresh id) from "an invoke was genuinely in flight" (safe to retry only with the
     * same one) — [SendMessageResult]'s own doc comment states which is which.
     */
    public override suspend fun sendMessage(
        conversationId: String,
        body: String,
        clientMessageId: String,
        attachmentId: String?,
    ): SendMessageResult {
        val hub = connection
        if (hub == null || hub.connectionState != HubConnectionState.CONNECTED) {
            return SendMessageResult.NotConnected
        }

        return try {
            val sequence =
                hub
                    // `Int::class.javaObjectType`, not `Integer::class.java` and not
                    // `Int::class.java`: SignalR's `invoke` deserialises into the `Class` handed to
                    // it, so it needs the *boxed* `java.lang.Integer` (`Int::class.java` is the
                    // primitive `int.class`, which a generic deserialiser cannot instantiate).
                    // Naming `Integer` directly is what Kotlin 2.4 started warning about ("This
                    // class is not recommended for use in Kotlin"), and `allWarningsAsErrors`
                    // makes that a build failure here; `javaObjectType` is the same class object
                    // typed as `Class<Int>`, so the call's type argument is a Kotlin type.
                    .invoke(Int::class.javaObjectType, SEND_MESSAGE_METHOD, conversationId, body, attachmentId, clientMessageId)
                    .await()
            SendMessageResult.Sent(sequence.toLong())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (hub.connectionState != HubConnectionState.CONNECTED) {
                SendMessageResult.OutcomeUnknown(failure)
            } else {
                SendMessageResult.Refused(failure.message ?: (failure::class.simpleName ?: "send failed"))
            }
        }
    }

    /**
     * `26-17`: [OperatorHubEvents.reconnectToActiveSite] — drops the current connection (exactly the
     * way a deliberate [disconnect] does, so [onConnectionClosed] never starts a reconnect loop for a
     * connection this call itself is retiring), discards whatever the joined-conversation subscription
     * remembered (the conversation belonged to the site being left; a fresh connection to a different
     * site has nothing to resume it against), and rebuilds — which reads [activeSite]'s current value
     * fresh, the same "capture nothing, read at the point of use" discipline this class's own doc
     * comment states for the bearer token.
     */
    public override suspend fun reconnectToActiveSite() {
        disconnect()
        subscription.leave()
        discardConnection()
        connect()
    }

    /**
     * `26-54`: `GetTeamHistoryAsync`, called with its full two-argument arity — see
     * [OperatorHubEvents.getTeamHistory]'s own doc comment. No [MessageSubscription]-style
     * "mark already delivered" bookkeeping here: that machinery exists to keep one joined
     * conversation's [messages] stream exactly-once, and the team room has no join or resume record of
     * its own for a fetched page to be reconciled against — [TeamChatViewModel] (`:app`) does its own
     * id-based merge, the identical shape [teamMessages]'s own doc comment already states for a live
     * push.
     */
    public override suspend fun getTeamHistory(
        beforeSequence: Long?,
        pageSize: Int,
    ): TeamHistoryPage =
        requireConnection()
            .invoke(TeamHistoryPage::class.java, GET_TEAM_HISTORY_METHOD, beforeSequence, pageSize)
            .await()

    /** `26-54`: `GetTeamDeltaAsync` — see [OperatorHubEvents.getTeamDelta]'s own doc comment for why a
     * reconnect catches this room up by delta rather than by rejoining. */
    public override suspend fun getTeamDelta(afterSequence: Long): TeamHistoryPage =
        requireConnection()
            .invoke(TeamHistoryPage::class.java, GET_TEAM_DELTA_METHOD, afterSequence)
            .await()

    /**
     * `26-54`: `OperatorHub.SendTeamMessageAsync`, called with its full two-argument arity
     * (`body`, `clientMessageId`) every time — see [OperatorHubEvents.sendTeamMessage]'s own doc
     * comment. The not-connected/outcome-unknown/refused split is the identical logic [sendMessage]
     * above already carries, restated for this method's own two-argument invoke rather than shared as a
     * private helper — the two calls differ in exactly the arguments each sends, and threading an
     * arity-generic helper through both would obscure the one property this whole file exists to make
     * checkable by inspection: which literal arguments a given hub call sends.
     */
    public override suspend fun sendTeamMessage(
        body: String,
        clientMessageId: String,
    ): SendMessageResult {
        val hub = connection
        if (hub == null || hub.connectionState != HubConnectionState.CONNECTED) {
            return SendMessageResult.NotConnected
        }

        return try {
            val sequence =
                hub
                    .invoke(Int::class.javaObjectType, SEND_TEAM_MESSAGE_METHOD, body, clientMessageId)
                    .await()
            SendMessageResult.Sent(sequence.toLong())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (hub.connectionState != HubConnectionState.CONNECTED) {
                SendMessageResult.OutcomeUnknown(failure)
            } else {
                SendMessageResult.Refused(failure.message ?: (failure::class.simpleName ?: "send failed"))
            }
        }
    }

    /** `26-54`: `RemoveTeamMessageAsync` — no return value, so this uses `HubConnection.invoke`'s
     * `Completable`-returning overload (`send()` would not wait for the server to acknowledge the
     * call, the same distinction every other method in this class already draws by awaiting its own
     * invoke). See [OperatorHubEvents.removeTeamMessage]'s own doc comment for why this exists with no
     * caller yet. */
    public override suspend fun removeTeamMessage(teamMessageId: String) {
        requireConnection().invoke(REMOVE_TEAM_MESSAGE_METHOD, teamMessageId).await()
    }

    // ------------------------------------------------------------------------------ internals

    private fun requireConnection(): HubConnection = connection ?: error("OperatorHubConnection: connect() has not been called yet.")

    /**
     * Drops the cached `HubConnection` so the next [ensureConnection] call builds a fresh one against
     * whatever [activeSite] reports *then*, rather than reusing the site the old socket was opened
     * against. Split out of [reconnectToActiveSite] as its own, plain (non-suspend) function so it is
     * unit-testable with no coroutine and no socket at all — the same "safe to call directly" property
     * [ensureConnection] itself already documents.
     */
    internal fun discardConnection() {
        connection = null
    }

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
        // `26-14`: unlike `MESSAGE_RECEIVED_METHOD` above, this push needs no dedup/resume record at
        // all — see [OperatorHubEvents.assignments]'s own doc comment — so it is emitted straight
        // through with no [MessageSubscription] involvement.
        hub.on(
            CONVERSATION_ASSIGNED_METHOD,
            { dto: ConversationAssignedDto -> mutableAssignments.tryEmit(dto) },
            ConversationAssignedDto::class.java,
        )
        // `26-42`: the identical "no dedup/resume record needed" shape `CONVERSATION_ASSIGNED_METHOD`
        // above already is — see [OperatorHubEvents.messageDelivered]'s own doc comment.
        hub.on(
            MESSAGE_DELIVERED_METHOD,
            { dto: MessageDeliveredDto -> mutableMessageDelivered.tryEmit(dto) },
            MessageDeliveredDto::class.java,
        )
        // `26-54`: the team room's own two pushes — no dedup/resume record for either, the identical
        // "no join to replay" shape [OperatorHubEvents.teamMessages]'s own doc comment states.
        hub.on(
            TEAM_MESSAGE_RECEIVED_METHOD,
            { dto: TeamMessageDto -> mutableTeamMessages.tryEmit(dto) },
            TeamMessageDto::class.java,
        )
        hub.on(
            TEAM_MESSAGE_REMOVED_METHOD,
            { dto: TeamMessageDto -> mutableTeamMessageRemovals.tryEmit(dto) },
            TeamMessageDto::class.java,
        )
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
        // `26-14`: every push reaches [allMessages], regardless of what [subscription] is currently
        // joined to or has already seen — see [OperatorHubEvents.allMessages]'s own doc comment.
        mutableAllMessages.tryEmit(dto)
    }

    private fun newCallbackScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val JOIN_CONVERSATION_METHOD = "JoinConversationAsync"
        const val GET_HISTORY_METHOD = "GetHistoryAsync"
        const val SEND_MESSAGE_METHOD = "SendMessageAsync"
        const val MESSAGE_RECEIVED_METHOD = "MessageReceived"
        const val CONVERSATION_ASSIGNED_METHOD = "ConversationAssigned"
        const val MESSAGE_DELIVERED_METHOD = "MessageDelivered"
        const val GET_TEAM_HISTORY_METHOD = "GetTeamHistoryAsync"
        const val GET_TEAM_DELTA_METHOD = "GetTeamDeltaAsync"
        const val SEND_TEAM_MESSAGE_METHOD = "SendTeamMessageAsync"
        const val REMOVE_TEAM_MESSAGE_METHOD = "RemoveTeamMessageAsync"
        const val TEAM_MESSAGE_RECEIVED_METHOD = "TeamMessageReceived"
        const val TEAM_MESSAGE_REMOVED_METHOD = "TeamMessageRemoved"
    }
}
