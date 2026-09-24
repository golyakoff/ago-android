package ago.chat.android.team

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.team.CreateInviteResult
import ago.chat.android.core.domain.team.OperatorTeamApi
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
 * `26-56`: the invite form's own whole state machine — email, role, submitting, and whichever refusal
 * (if any) the last attempt ended with. **Never the one-shot [CreateInviteResult.Created] itself** —
 * [ago.chat.android.team.InviteColleagueSheet]'s own doc comment says why that value lives one level up,
 * in [ago.chat.android.team.PeopleRoute]'s own `rememberSaveable`, and not here: this is an ordinary
 * in-memory `ViewModel`, with no `SavedStateHandle` of its own, so it survives a rotation (Android
 * retains a `ViewModelStore` across one for free) but not a process death — exactly the line
 * `docs/backlog/26-56-*.md`'s own Scope draws. Losing a half-typed email to a process death that also
 * happened to strike mid-form costs nothing: the administrator retypes it. Losing the invite's own
 * plaintext code once it exists is the one thing this whole item exists to prevent, and that risk ends
 * the moment [submit] hands it to its caller via [onCreated] rather than keeping it in [state] itself.
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

        public fun roleSelected(roleName: String) {
            mutableState.update { it.copy(roleName = roleName, refusal = null) }
        }

        /**
         * `docs/backlog/26-56-*.md`'s own Scope item 2: the at-capacity pre-flight runs here, against
         * [seatSummary] — already in [ago.chat.android.team.PeopleUiState.Loaded]'s own hand before this
         * sheet ever opened, never a fresh read this method takes for itself — and blocks the network
         * call entirely once the *currently chosen* role's own `heldSeats >= limit`
         * ([RoleSeatSummary]'s own doc comment: never [RoleSeatSummary.overLimit]'s `>`). An email left
         * blank is refused the identical way, before any call — the one client-side check this item's own
         * Done-when still allows ("no client-side email regex", never "no client-side check at all"); a
         * malformed-but-non-blank address still reaches the server, whose own `InvalidEmail` refusal is
         * the only format check this class ever relies on.
         *
         * [onCreated] fires exactly once, only for [CreateInviteResult.Created] — never folded into
         * [state] itself, so nothing here can lose the one-shot code to a state update this class did not
         * intend as "hand this to the caller now". Every other outcome stays inside [state] as an
         * [InviteRefusalUi] the sheet renders inline.
         */
        public fun submit(
            seatSummary: List<RoleSeatSummary>,
            onCreated: (shareUrl: String, sendFailed: Boolean) -> Unit,
        ) {
            val current = mutableState.value
            if (current.submitting) return

            val trimmedEmail = current.email.trim()
            if (trimmedEmail.isEmpty()) {
                mutableState.update { it.copy(refusal = InviteRefusalUi.EmptyEmail) }
                return
            }

            val roleSummary = seatSummary.firstOrNull { it.roleName == current.roleName }
            if (roleSummary != null && roleSummary.heldSeats >= roleSummary.limit) {
                mutableState.update {
                    it.copy(refusal = InviteRefusalUi.AtCapacity(roleName = current.roleName, limit = roleSummary.limit))
                }
                return
            }

            mutableState.update { it.copy(submitting = true, refusal = null) }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.createInvite(roleName = current.roleName, email = trimmedEmail) }
                when (result) {
                    is CreateInviteResult.Created -> {
                        // Reset now, not on the caller's own next open — `state` is this class's whole
                        // surface, and the form must not still show yesterday's submitted email the next
                        // time it opens (`InviteColleagueSheet`'s own `hiltViewModel()` may well outlive
                        // one invite, e.g. a rotation between two separate invites in the same visit).
                        mutableState.update { InviteColleagueFormState() }
                        onCreated("${oidcConfig.consoleUrl}/invite/${result.code}", result.sendFailed)
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
 * created invite itself is never one of these fields. */
public data class InviteColleagueFormState(
    val roleName: String = ROLE_OPERATOR,
    val email: String = "",
    val submitting: Boolean = false,
    val refusal: InviteRefusalUi? = null,
)

/**
 * The identical [ago.chat.android.conversations.ClaimErrorUi] split, restated for this write: a genuine
 * server refusal ([ServerRefusal], the server's own words, shown verbatim), a classification the screen
 * turns into words itself ([Unavailable], via [ago.chat.android.ui.components.networkFailureText]), and
 * two refusals this sheet decides on its own before ever calling the server — [EmptyEmail] (a UX-only
 * catch, not the email-format regex `docs/backlog/26-56-*.md`'s own Done-when forbids) and
 * [AtCapacity] (this item's own required pre-flight, naming the role).
 */
public sealed interface InviteRefusalUi {
    public data object EmptyEmail : InviteRefusalUi

    public data class AtCapacity(
        val roleName: String,
        val limit: Int,
    ) : InviteRefusalUi

    public data class ServerRefusal(
        val detail: String,
    ) : InviteRefusalUi

    public data class Unavailable(
        val reason: NetworkFailure,
    ) : InviteRefusalUi
}
