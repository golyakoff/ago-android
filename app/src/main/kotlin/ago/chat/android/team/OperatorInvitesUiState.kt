package ago.chat.android.team

import ago.chat.android.core.domain.team.OperatorInviteListItem
import ago.chat.android.core.domain.team.OperatorTeamFailure

/**
 * `26-242`: [OperatorInvitesViewModel]'s whole state — the sent-invite list beneath Люди's roster, its
 * own load lifecycle kept separate from [PeopleUiState] on purpose. The invite list is "a genuinely
 * different concern" (`OperatorsTeamPage.tsx`'s own `loadInvites` comment, split from its `load`): a
 * roster that loaded fine should still show its people even when the invite read failed, and revoking
 * an invite must refresh *this* list without disturbing the roster beside it. Two view models, two
 * states — the same shape [PeopleUiState] itself already takes, restated for the second read.
 */
public sealed interface OperatorInvitesUiState {
    public data object Loading : OperatorInvitesUiState

    /**
     * The list came back. [revokingId] is the invite currently being revoked (its row shows progress
     * and offers no second tap); [revokeFailed] flags that the last revoke attempt did not take effect,
     * rendered as an inline error the operator can retry from — the list itself is left untouched, since
     * the invite is still exactly as revocable as before. Both are transient overlays on the loaded
     * list, never a separate top-level state: a failed revoke does not blank a list that loaded fine.
     */
    public data class Loaded(
        val invites: List<OperatorInviteListItem>,
        val revokingId: String? = null,
        val revokeFailed: Boolean = false,
    ) : OperatorInvitesUiState

    public data class Failed(
        val reason: OperatorTeamFailure,
    ) : OperatorInvitesUiState
}
