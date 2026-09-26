package ago.chat.android.channels

import ago.chat.android.core.domain.channels.VkReveal
import ago.chat.android.core.domain.net.NetworkFailure
import java.time.Instant

/**
 * `26-188`: [ChannelConnectViewModel]'s whole state — one shape shared by Telegram/MAX/VK
 * ([TelegramChannelViewModel] and its future MAX/VK siblings each fix a [ago.chat.android.core.domain.channels.ChannelKind],
 * never their own state shape).
 */
internal sealed interface ChannelConnectUiState {
    data object Loading : ChannelConnectUiState

    /** The status read itself failed — retry is the only action, the identical shape the app's other
     * read screens already establish for a [NetworkFailure]. */
    data class Failed(
        val reason: NetworkFailure,
    ) : ChannelConnectUiState

    /** `connected == false` on the last status read. [connectError] is set only while the *last*
     * connect attempt ended in a refusal or a failure - cleared the moment a new attempt starts. */
    data class Disconnected(
        val connecting: Boolean,
        val connectError: ChannelActionError?,
    ) : ChannelConnectUiState

    /**
     * `connected == true`. [verified]/[unreachable]/[refusalReason] together pick which of the three
     * connected sub-states (`verified`, `unreachable`, `refused`) the screen renders - see
     * [ago.chat.android.core.domain.channels.ChannelStatus]'s own doc comment for why exactly one of
     * the three is always the true reading.
     *
     * @param reveal VK's own shown-once `callbackUrl`/`webhookSecret`, carried only for the session that
     *   just connected - `null` on every reload, including a first status read after process death. Kept
     *   in this shared state (`showVkReveal=false` channels simply never populate it) rather than a
     *   VK-only [ChannelConnectUiState] subtype, since the reveal is UI content, not a fourth connected
     *   sub-state.
     */
    data class Connected(
        val verified: Boolean?,
        val unreachable: Boolean,
        val refusalReason: String?,
        val createdAt: Instant?,
        val checkedAt: Instant,
        val channelCredentialId: String,
        val reveal: VkReveal?,
        val disconnecting: Boolean,
        val disconnectError: ChannelActionError?,
    ) : ChannelConnectUiState
}

/**
 * `26-188`: a connect/disconnect attempt's own two honest outcomes — the identical split
 * [ago.chat.android.conversations.ClaimErrorUi]/[ago.chat.android.bookings.BookingActionErrorUi]
 * already establish, restated here rather than reused since neither of those two is a channel concern.
 * A [NetworkFailure] is a `:core:domain` classification, never a sentence - turning [Unavailable] into
 * words is `:app`'s job alone, done at the one call site
 * ([ago.chat.android.ui.components.networkFailureText]) every other screen already reads, not inside
 * this view model.
 */
internal sealed interface ChannelActionError {
    /** The server's own problem-details `detail`, shown verbatim - a bad/expired token, an
     * already-connected credential, or VK-not-available on this deployment. */
    data class ServerRefusal(
        val detail: String,
    ) : ChannelActionError

    data class Unavailable(
        val reason: NetworkFailure,
    ) : ChannelActionError
}
