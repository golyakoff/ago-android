package ago.chat.android.thread

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.network.realtime.MessageDto
import ago.chat.android.core.network.realtime.OperatorHubConnectionState

/**
 * `26-15`: everything [ThreadScreen] renders for one open conversation. [messages] is always sorted
 * ascending by [MessageDto.sequence] and deduplicated by [MessageDto.id] — never a timestamp order,
 * never a raw append of whatever arrived last (`CLAUDE.md` rule 6, `ThreadViewModel`'s own
 * `mergeAndRender`).
 */
public data class ThreadUiState(
    public val conversationId: String? = null,
    public val messages: List<MessageDto> = emptyList(),
    /** `true` once the most recently loaded page's own `nextBeforeSequence` was non-null — there is
     * more history to page backward into. `false` both before the first page has loaded and once the
     * keyset cursor is genuinely exhausted; [ThreadScreen] tells the two apart via [joining]. */
    public val canLoadOlder: Boolean = false,
    /** The initial `JoinConversationAsync` call is still in flight - distinct from [loadingOlder],
     * which is the manual "load older" affordance's own spinner and must never show for a fetch the
     * operator did not ask for (`ago-console`'s `Thread.tsx` draws the identical distinction). */
    public val joining: Boolean = true,
    public val loadingOlder: Boolean = false,
    /** A join or a "load older" call failed outright. Shown inline; retried only by the operator's own
     * explicit action, never automatically. `26-59`: [NetworkFailure]'s own classification rather than
     * a pre-rendered `String` — this class stopped deciding the operator's words the moment it stopped
     * being trustworthy enough to write an exception's own message into them; [ThreadScreen] renders
     * this into a sentence. */
    public val historyError: NetworkFailure? = null,
    public val draft: String = "",
    public val sending: Boolean = false,
    /** `true` when the most recent send's outcome is ambiguous or definitely failed to leave the
     * connection, and a retry is available — [ThreadViewModel.retrySend]'s own guard. Mirrors
     * `ago-console`'s `failedSend` banner: shown until either the retry succeeds or the operator edits
     * the draft again (`ThreadViewModel`'s own doc comment on why editing clears it). */
    public val pendingRetry: Boolean = false,
    /** The server definitively refused the send (a `HubException`) — shown once, dismissible, and
     * never retried automatically, the identical posture `ConversationRowUi.claimError` already takes
     * for a claim refusal. */
    public val sendRefusedMessage: String? = null,
    public val hubConnectionState: OperatorHubConnectionState = OperatorHubConnectionState.Disconnected,
)
