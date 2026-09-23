package ago.chat.android.team

import ago.chat.android.core.domain.team.OperatorTeamFailure
import ago.chat.android.core.domain.team.OperatorTeamMember
import ago.chat.android.core.domain.team.RoleSeatSummary

/**
 * `26-55`: [PeopleViewModel]'s whole state — a direct reflection of
 * [ago.chat.android.core.domain.team.OperatorTeamResult]/[ago.chat.android.core.domain.team.SeatSummaryResult]
 * folded into one three-arm shape, plus the one state neither result type has a reason to know about,
 * [Loading] (before either answer has come back at all). [BookingsUiState][ago.chat.android.bookings.BookingsUiState]'s
 * own doc comment states the identical reasoning for the identical shape.
 */
public sealed interface PeopleUiState {
    public data object Loading : PeopleUiState

    /** Both reads answered — [members] and [seatSummary] arrive together, since a roster with no seat
     * context (or the reverse) is not a state this screen's own Scope has anything useful to draw. */
    public data class Loaded(
        val members: List<OperatorTeamMember>,
        val seatSummary: List<RoleSeatSummary>,
    ) : PeopleUiState

    /** Either read failed — [PeopleViewModel.refresh]'s own doc comment says which reason wins when
     * both did. */
    public data class Failed(
        val reason: OperatorTeamFailure,
    ) : PeopleUiState
}
