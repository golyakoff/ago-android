package ago.chat.android.team

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.team.CreateInviteResult
import ago.chat.android.core.domain.team.OperatorTeamApi
import ago.chat.android.core.domain.team.ROLE_ADMIN
import ago.chat.android.core.domain.team.ROLE_OPERATOR
import ago.chat.android.core.domain.team.RoleSeatSummary
import ago.chat.android.di.IoDispatcher
import ago.chat.android.session.OidcConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-241`: the two seeded roles an invite can grant, in the order the sheet offers their checkboxes and
 * the order [roleNames] goes on the wire in — a single source the sheet, the pre-flight and [submit]
 * all read, so the request body is always seeded-order and never depends on the order a tenant happened
 * to tick the boxes (`ago-console`'s own `INVITE_ROLE_ORDER`, mirrored). */
internal val INVITE_ROLE_ORDER: List<String> = listOf(ROLE_OPERATOR, ROLE_ADMIN)

/** `26-241`: does this role's own seat pool have no room for one more — the per-role half of the invite
 * pre-flight, the same `heldSeats >= limit` "at capacity" predicate `OperatorRoleSeatCapacity.CheckAsync`
 * gates each role in the set with server-side (`>=`, never [RoleSeatSummary.overLimit]'s own `>`, which
 * answers the strictly-higher "already over" question). A role with no summary row yet is treated as
 * "not known to be full" so the pre-flight never blocks purely on a missing read — the server is the
 * real gate. */
internal fun roleSeatFull(
    seatSummary: List<RoleSeatSummary>,
    roleName: String,
): Boolean {
    val summary = seatSummary.firstOrNull { it.roleName == roleName }
    return summary != null && summary.heldSeats >= summary.limit
}

/**
 * `26-56`/`26-241`: the invite form's own whole state machine — email, the **set** of roles the invite
 * will grant, submitting, and whichever refusal (if any) the last attempt ended with. **Never the
 * one-shot [CreateInviteResult.Created] itself** — [ago.chat.android.team.InviteColleagueSheet]'s own
 * doc comment says why that value lives one level up, in [ago.chat.android.team.PeopleRoute]'s own
 * `rememberSaveable`, and not here: this is an ordinary in-memory `ViewModel`, with no
 * `SavedStateHandle` of its own, so it survives a rotation (Android retains a `ViewModelStore` across
 * one for free) but not a process death — exactly the line `docs/backlog/26-56-*.md`'s own Scope draws.
 * Losing a half-typed email to a process death that also happened to strike mid-form costs nothing: the
 * administrator retypes it. Losing the invite's own plaintext code once it exists is the one thing this
 * whole item exists to prevent, and that risk ends the moment [submit] hands it to its caller via
 * [onCreated] rather than keeping it in [state] itself.
 *
 * `26-241`: the single-role pick became a multi-select (a checkbox per seeded role), the Android mirror
 * of `ago-console`'s own `OperatorsTeamPage` change — one invite can grant both seeded roles at once,
 * each gated against its own seat pool at send time (`ago-chat#383`). At least one role is required, and
 * the pre-flight blocks the submit while any *selected* role's own pool is full — so a tenant whose
 * Operator seat is full unticks it and invites an Admin instead, rather than the old whole-form dead-end.
 */
@HiltViewModel
public class InviteColleagueViewModel
    @Inject
    constructor(
        private val api: OperatorTeamApi,
        private val oidcConfig: OidcConfig,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(InviteColleagueFormState())
        public val state: StateFlow<InviteColleagueFormState> = mutableState.asStateFlow()

        public fun emailChanged(email: String) {
            mutableState.update { it.copy(email = email, refusal = null) }
        }

        /** `26-241`: tick or untick one role — the sheet's checkbox `onCheckedChange`. Held as a set, so
         * a double-tick cannot double-add; a stale refusal is cleared the same way [emailChanged] clears
         * one, since changing the selection changes what the pre-flight would say. */
        public fun roleToggled(
            roleName: String,
            selected: Boolean,
        ) {
            mutableState.update {
                val nextRoles = if (selected) it.roleNames + roleName else it.roleNames - roleName
                it.copy(roleNames = nextRoles, refusal = null)
            }
        }

        /**
         * `docs/backlog/26-56-*.md`'s own Scope item 2, generalised to a set by `26-241`: the pre-flight
         * runs here, against [seatSummary] — already in [ago.chat.android.team.PeopleUiState.Loaded]'s
         * own hand before this sheet ever opened, never a fresh read this method takes for itself. It
         * blocks the network call entirely when no role is selected at all, or when *any* selected role's
         * own pool is already full ([roleSeatFull], `>=`). An email left blank is refused the identical
         * way, before any call — the one client-side check this item's own Done-when still allows ("no
         * client-side email regex", never "no client-side check at all"); a malformed-but-non-blank
         * address still reaches the server, whose own `InvalidEmail` refusal is the only format check
         * this class ever relies on.
         *
         * [onCreated] fires exactly once, only for [CreateInviteResult.Created] — never folded into
         * [state] itself, so nothing here can lose the one-shot code to a state update this class did not
         * intend as "hand this to the caller now". Every other outcome stays inside [state] as an
         * [InviteRefusalUi] the sheet renders inline — a server `402` naming a full role
         * ([CreateInviteResult.RoleSeatFull]) surfaces as the same [InviteRefusalUi.RolesAtCapacity] the
         * client-side pre-flight raises, defense in depth behind it.
         */
        public fun submit(
            seatSummary: List<RoleSeatSummary>,
            onCreated: (shareUrl: String, sendFailed: Boolean) -> Unit,
        ) {
            val current = mutableState.value
            if (current.submitting) return

            if (current.roleNames.isEmpty()) {
                mutableState.update { it.copy(refusal = InviteRefusalUi.NoRoleSelected) }
                return
            }

            val trimmedEmail = current.email.trim()
            if (trimmedEmail.isEmpty()) {
                mutableState.update { it.copy(refusal = InviteRefusalUi.EmptyEmail) }
                return
            }

            val fullRoles = INVITE_ROLE_ORDER.filter { it in current.roleNames && roleSeatFull(seatSummary, it) }
            if (fullRoles.isNotEmpty()) {
                mutableState.update { it.copy(refusal = InviteRefusalUi.RolesAtCapacity(fullRoles)) }
                return
            }

            // The ticked roles in seeded order (`INVITE_ROLE_ORDER`), so an ordinary Operator-only invite
            // still sends exactly `["Operator"]` and the wire order never depends on tick order.
            val orderedRoles = INVITE_ROLE_ORDER.filter { it in current.roleNames }.toSet()

            mutableState.update { it.copy(submitting = true, refusal = null) }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.createInvite(roleNames = orderedRoles, email = trimmedEmail) }
                when (result) {
                    is CreateInviteResult.Created -> {
                        // Reset now, not on the caller's own next open — `state` is this class's whole
                        // surface, and the form must not still show yesterday's submitted email the next
                        // time it opens (`InviteColleagueSheet`'s own `hiltViewModel()` may well outlive
                        // one invite, e.g. a rotation between two separate invites in the same visit).
                        mutableState.update { InviteColleagueFormState() }
                        onCreated("${oidcConfig.consoleUrl}/invite/${result.code}", result.sendFailed)
                    }
                    is CreateInviteResult.RoleSeatFull ->
                        mutableState.update {
                            it.copy(submitting = false, refusal = InviteRefusalUi.RolesAtCapacity(listOf(result.roleName)))
                        }
                    is CreateInviteResult.Refused ->
                        mutableState.update { it.copy(submitting = false, refusal = InviteRefusalUi.ServerRefusal(result.detail)) }
                    is CreateInviteResult.Failed ->
                        mutableState.update { it.copy(submitting = false, refusal = InviteRefusalUi.Unavailable(result.reason)) }
                }
            }
        }
    }

/** [InviteColleagueViewModel]'s own transient form state — see that class's own doc comment for why the
 * created invite itself is never one of these fields. [roleNames] defaults to just the Operator role,
 * the console's original only-offer before `23-72`; a one-role invite is simply a one-element set. */
public data class InviteColleagueFormState(
    val roleNames: Set<String> = setOf(ROLE_OPERATOR),
    val email: String = "",
    val submitting: Boolean = false,
    val refusal: InviteRefusalUi? = null,
)

/**
 * The identical [ago.chat.android.conversations.ClaimErrorUi] split, restated for this write: a genuine
 * server refusal ([ServerRefusal], the server's own words, shown verbatim), a classification the screen
 * turns into words itself ([Unavailable], via [ago.chat.android.ui.components.networkFailureText]), and
 * the refusals this sheet decides on its own before ever calling the server — [EmptyEmail] and
 * [NoRoleSelected] (UX-only catches, not the email-format regex `docs/backlog/26-56-*.md`'s own Done-when
 * forbids) and [RolesAtCapacity] (`26-241`'s own required pre-flight, naming every selected full role;
 * a server `402`'s [CreateInviteResult.RoleSeatFull] surfaces through this same arm, naming the one role
 * it refused).
 */
public sealed interface InviteRefusalUi {
    public data object EmptyEmail : InviteRefusalUi

    public data object NoRoleSelected : InviteRefusalUi

    public data class RolesAtCapacity(
        val roleNames: List<String>,
    ) : InviteRefusalUi

    public data class ServerRefusal(
        val detail: String,
    ) : InviteRefusalUi

    public data class Unavailable(
        val reason: NetworkFailure,
    ) : InviteRefusalUi
}
