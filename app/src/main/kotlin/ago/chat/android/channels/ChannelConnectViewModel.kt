package ago.chat.android.channels

import ago.chat.android.core.domain.channels.ChannelConnectResult
import ago.chat.android.core.domain.channels.ChannelConnectionApi
import ago.chat.android.core.domain.channels.ChannelDisconnectResult
import ago.chat.android.core.domain.channels.ChannelKind
import ago.chat.android.core.domain.channels.ChannelStatus
import ago.chat.android.core.domain.channels.ChannelStatusResult
import ago.chat.android.core.domain.channels.VkReveal
import ago.chat.android.core.domain.net.NetworkFailure
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `26-188`: one abstract base for every token-channel connect screen (Telegram now,
 * [ago.chat.android.core.domain.channels.ChannelKind.Max]/`.Vk` in `C2`/`C3`) — three trivial
 * `@HiltViewModel` subclasses each fix [kind] rather than each re-implementing this same load/connect/
 * disconnect state machine, the identical "one base, thin subclasses" shape
 * [ago.chat.android.bookings.WorkerScheduleViewModel] and its own per-worker callers already establish
 * elsewhere in this app.
 *
 * **`sessionReveal` lives here, not only on a VK subclass.** [ChannelConnectResult.Connected.reveal] is
 * `null` for every Telegram/MAX connect by construction (`KtorChannelConnectionApi` never builds one for
 * them), so holding it in the shared base costs the non-VK channels nothing and saves `C3` from
 * duplicating the load/refresh machinery just to thread one extra field through.
 *
 * **A write is a no-op while one is already in flight**, read off the *current* state rather than a
 * separate boolean - [ChannelConnectUiState.Disconnected.connecting] and
 * [ChannelConnectUiState.Connected.disconnecting] already say whether a request is out, so a second
 * tap before it lands finds the guard already true and does nothing, without the state and the guard
 * ever being able to disagree.
 */
internal abstract class ChannelConnectViewModel(
    private val api: ChannelConnectionApi,
    private val ioDispatcher: CoroutineDispatcher,
    private val kind: ChannelKind,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ChannelConnectUiState>(ChannelConnectUiState.Loading)
    val state: StateFlow<ChannelConnectUiState> = mutableState.asStateFlow()

    /** VK's own shown-once `callbackUrl`/`webhookSecret`, kept for this session alone - `fetchStatus`
     * never carries it again, so a reload (or a second device) genuinely has nothing to show
     * ([ChannelConnectUiState.Connected.reveal]'s own doc comment). Cleared on a successful disconnect
     * so a fresh connect always starts from "nothing shown yet". */
    private var sessionReveal: VkReveal? = null

    init {
        refresh()
    }

    /** The initial load, and the retry a [ChannelConnectUiState.Failed] screen offers - also what a
     * successful connect/disconnect calls next, since the reloaded status is always this screen's one
     * source of truth, never an optimistic flip of the write's own result. */
    fun refresh() {
        mutableState.update { ChannelConnectUiState.Loading }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { api.fetchStatus(kind) }
            mutableState.update { toState(result) }
        }
    }

    fun connect(token: String) {
        val current = mutableState.value as? ChannelConnectUiState.Disconnected ?: return
        if (current.connecting) return
        mutableState.update { ChannelConnectUiState.Disconnected(connecting = true, connectError = null) }

        viewModelScope.launch {
            when (val result = withContext(ioDispatcher) { api.connect(kind, token) }) {
                is ChannelConnectResult.Connected -> {
                    sessionReveal = result.reveal
                    refresh()
                }

                is ChannelConnectResult.Refused ->
                    mutableState.update {
                        ChannelConnectUiState.Disconnected(
                            connecting = false,
                            connectError = ChannelActionError.ServerRefusal(result.detail),
                        )
                    }

                is ChannelConnectResult.Failed ->
                    mutableState.update {
                        ChannelConnectUiState.Disconnected(
                            connecting = false,
                            connectError = ChannelActionError.Unavailable(result.reason),
                        )
                    }
            }
        }
    }

    fun disconnect() {
        val current = mutableState.value as? ChannelConnectUiState.Connected ?: return
        if (current.disconnecting) return
        mutableState.update { current.copy(disconnecting = true, disconnectError = null) }

        viewModelScope.launch {
            when (val result = withContext(ioDispatcher) { api.disconnect(kind, current.channelCredentialId) }) {
                ChannelDisconnectResult.Disconnected -> {
                    sessionReveal = null
                    refresh()
                }

                is ChannelDisconnectResult.Refused ->
                    mutableState.update { state ->
                        (state as? ChannelConnectUiState.Connected)
                            ?.copy(disconnecting = false, disconnectError = ChannelActionError.ServerRefusal(result.detail))
                            ?: state
                    }

                is ChannelDisconnectResult.Failed ->
                    mutableState.update { state ->
                        (state as? ChannelConnectUiState.Connected)
                            ?.copy(disconnecting = false, disconnectError = ChannelActionError.Unavailable(result.reason))
                            ?: state
                    }
            }
        }
    }

    /** Dismisses a disconnect refusal's own inline error without discarding the connected state around
     * it - the identical "dismiss clears only the error" shape a confirm dialog's own cancel already
     * gives the connect side for free (there is no state to clear there; [connect]'s own next attempt
     * clears [ChannelConnectUiState.Disconnected.connectError] itself). */
    fun dismissDisconnectError() {
        mutableState.update { state ->
            (state as? ChannelConnectUiState.Connected)?.copy(disconnectError = null) ?: state
        }
    }

    private fun toState(result: ChannelStatusResult): ChannelConnectUiState =
        when (result) {
            is ChannelStatusResult.Loaded -> toState(result.status)
            is ChannelStatusResult.Failed -> ChannelConnectUiState.Failed(result.reason)
        }

    private fun toState(status: ChannelStatus): ChannelConnectUiState {
        if (!status.connected) {
            return ChannelConnectUiState.Disconnected(connecting = false, connectError = null)
        }
        // A connected status with no credential id is a shape this contract never promised - the
        // identical `KtorChannelConnectionApi`/`shapeGuard.ts` lesson, read onto the one field a
        // disconnect could not proceed without.
        val channelCredentialId = status.channelCredentialId ?: return ChannelConnectUiState.Failed(NetworkFailure.Unexpected)
        return ChannelConnectUiState.Connected(
            verified = status.verified,
            unreachable = status.unreachable,
            refusalReason = status.refusalReason,
            createdAt = status.createdAt,
            checkedAt = status.checkedAt,
            channelCredentialId = channelCredentialId,
            reveal = sessionReveal,
            disconnecting = false,
            disconnectError = null,
        )
    }
}
