package ago.chat.android.team

import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.core.network.realtime.TeamMessageDto

/**
 * `26-54`: everything [TeamChatScreen] renders. [messages] is always sorted ascending by
 * [TeamMessageDto.sequence] and deduplicated by [TeamMessageDto.id] — [TeamChatViewModel]'s own
 * `mergeAndRender`, the identical shape `ThreadUiState`'s own doc comment states for a conversation
 * (`CLAUDE.md` rule 6: never a timestamp order).
 */
public data class TeamChatUiState(
    public val messages: List<TeamMessageDto> = emptyList(),
    /** The first `GetTeamHistoryAsync` call — gated on [hubConnectionState] actually reaching
     * [OperatorHubConnectionState.Connected] ([TeamChatViewModel]'s own doc comment) — is still in
     * flight. `true` until it resolves one way or the other; never re-set by a later reconnect's own
     * delta catch-up, which has nothing on screen to hide behind a spinner for. */
    public val loading: Boolean = true,
    /** `true` once the most recently loaded page's own `nextBeforeSequence` was non-null — there is
     * more history to page backward into. */
    public val canLoadOlder: Boolean = false,
    /** The manual "load older" affordance's own spinner — distinct from [loading], which must never
     * show for a fetch the operator did not ask for. */
    public val loadingOlder: Boolean = false,
    /** The initial load or a "load older" page failed outright. Retried only by the operator's own
     * explicit action, never automatically. */
    public val historyError: String? = null,
    public val draft: String = "",
    public val sending: Boolean = false,
    /** `true` when the most recent send's outcome is ambiguous or definitely failed to leave the
     * connection, and a retry is available — [SendMessageResult][ago.chat.android.core.network.realtime.SendMessageResult]'s
     * own doc comment states which id a retry from here must reuse. */
    public val pendingRetry: Boolean = false,
    /** The server definitively refused the send — shown once, dismissible, never retried automatically. */
    public val sendRefusedMessage: String? = null,
    public val hubConnectionState: OperatorHubConnectionState = OperatorHubConnectionState.Disconnected,
)
