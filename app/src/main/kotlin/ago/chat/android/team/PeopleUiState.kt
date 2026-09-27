package ago.chat.android.team

import ago.chat.android.core.domain.net.NetworkFailure
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

    /**
     * Both reads answered — [members] and [seatSummary] arrive together, since a roster with no seat
     * context (or the reverse) is not a state this screen's own Scope has anything useful to draw.
     *
     * `26-253`: [pendingWrite] and [writeRefusal] are transient overlays on the loaded roster for the
     * three operator-management writes (change role, remove, seat toggle) — never a separate top-level
     * state, the identical shape [OperatorInvitesUiState.Loaded]'s own `revokingId`/`revokeFailed` pair
     * already takes for the revoke write. [pendingWrite] marks the one operator/action in flight so its
     * row shows progress and takes no second tap (only one write runs at a time — [PeopleViewModel]'s own
     * in-flight guard). [writeRefusal] is the last write's refusal, pinned to the operator it targeted so
     * the row renders it inline; both clear the moment the next write starts, and a successful write
     * replaces the whole [Loaded] with a freshly re-read one carrying neither.
     */
    public data class Loaded(
        val members: List<OperatorTeamMember>,
        val seatSummary: List<RoleSeatSummary>,
        val pendingWrite: OperatorWriteInFlight? = null,
        val writeRefusal: OperatorWriteRefusal? = null,
    ) : PeopleUiState

    /** Either read failed — [PeopleViewModel.refresh]'s own doc comment says which reason wins when
     * both did. */
    public data class Failed(
        val reason: OperatorTeamFailure,
    ) : PeopleUiState
}

/** `26-253`: the three operator-management writes, so a row can show progress on the exact control that
 * fired it. [roleName] is set only for [OperatorWriteAction.ToggleSeat] (which seat-per-role button is in
 * flight); the other two act on the operator as a whole. */
public data class OperatorWriteInFlight(
    val operatorId: String,
    val action: OperatorWriteAction,
    val roleName: String? = null,
)

public enum class OperatorWriteAction { ChangeRole, Remove, ToggleSeat }

/** `26-253`: the last write's refusal, pinned to the operator whose row it belongs on. [reason] is the
 * typed refusal `:app` renders to a Russian sentence — the identical [InviteRefusalUi] split, restated
 * for these three writes. */
public data class OperatorWriteRefusal(
    val operatorId: String,
    val reason: OperatorWriteRefusalReason,
)

/**
 * `26-253`: why an operator-management write did not take — the same "the domain says what happened,
 * `:app` says how to word it" split [InviteRefusalUi] already draws. [LastManager] and [SeatFull] are the
 * state-conflict refusals the server names by code, worded in Russian here; [ServerRefusal] carries the
 * server's own `detail` verbatim (the near-unreachable `Operator.NotFound`/`RoleNotFound`/`AlreadyRemoved`
 * races); [Unavailable] is transport trouble, worded through
 * [ago.chat.android.ui.components.networkFailureText].
 */
public sealed interface OperatorWriteRefusalReason {
    /** `Operator.IsLastManager` — a change-role demotion or a removal that would leave the site with
     * nobody who can manage operators. */
    public data object LastManager : OperatorWriteRefusalReason

    /** A `402` seat-full: the named role's own pool has no free seats. Covers a toggle-on refused for
     * capacity and a promote-to-Admin refused by the Admin pool alike. */
    public data class SeatFull(
        val roleName: String,
    ) : OperatorWriteRefusalReason

    /** A `detail`-bearing refusal shown verbatim (the server wrote the words). */
    public data class ServerRefusal(
        val detail: String,
    ) : OperatorWriteRefusalReason

    /** Transport trouble, or a refusal with no `detail` to show. */
    public data class Unavailable(
        val reason: NetworkFailure,
    ) : OperatorWriteRefusalReason
}
