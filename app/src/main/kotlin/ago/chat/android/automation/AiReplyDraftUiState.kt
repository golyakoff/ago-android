package ago.chat.android.automation

import ago.chat.android.core.domain.ai.AiReplyDraftStatus
import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-246` (`ago-console`'s own `AiReplyDraftPage`): [AiReplyDraftViewModel]'s own state — the identical
 * loading/failed/loaded shape this app's other read-then-act screens establish
 * ([OfflineAutoReplyUiState]), sized for a single on/off switch rather than a full-DTO form: the only
 * mutation this screen makes is [AiReplyDraftViewModel.setEnabled], so there is no editable draft to
 * hold, only the last-read [AiReplyDraftStatus] and whether a toggle is in flight.
 */
internal sealed interface AiReplyDraftUiState {
    data object Loading : AiReplyDraftUiState

    /** The initial read itself failed — retry is the only action. */
    data class Failed(
        val reason: NetworkFailure,
    ) : AiReplyDraftUiState

    /**
     * @param status the server's own last-read reply-draft status — [AiReplyDraftStatus.purchased] and
     *   [AiReplyDraftStatus.setupComplete] decide which of the three console faces the screen shows
     *   (not-purchased note, finish-setup note, or the live switch), never a local guess.
     * @param busy `true` while an enable/disable is in flight — [AiReplyDraftViewModel.setEnabled] is a
     *   no-op while this is already `true`, the identical "no-op while its own kind is already in flight"
     *   discipline [OfflineAutoReplyUiState.Loaded.saving] states.
     * @param savedTick bumped on every successful toggle whose re-read landed — drives a one-shot
     *   confirmation snackbar, the identical [OfflineAutoReplyUiState.Loaded.savedTick] shape.
     * @param actionError the most recent toggle's own failure — a server refusal shown verbatim, or a
     *   transport failure. `null` once a new toggle starts or one succeeds.
     */
    data class Loaded(
        val status: AiReplyDraftStatus,
        val busy: Boolean = false,
        val savedTick: Int = 0,
        val actionError: AiReplyDraftActionError? = null,
    ) : AiReplyDraftUiState
}

/**
 * `26-246`: a toggle's own two honest failures — the identical split
 * [OfflineAutoReplyActionError]'s own `ServerRefusal`/`Unavailable` arms establish, minus its own
 * client-side `Invalid` arm: this screen sends no free text, so there is nothing to validate before the
 * round trip — the server is the only validator, and a refusal (e.g. `AiAddOn.AgreementNotAccepted`) comes
 * back as [ServerRefusal].
 */
internal sealed interface AiReplyDraftActionError {
    data class ServerRefusal(
        val detail: String,
    ) : AiReplyDraftActionError

    data class Unavailable(
        val reason: NetworkFailure,
    ) : AiReplyDraftActionError
}
