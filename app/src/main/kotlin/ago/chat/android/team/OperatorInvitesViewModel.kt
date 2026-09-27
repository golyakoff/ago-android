package ago.chat.android.team

import ago.chat.android.core.domain.team.OperatorInvitesResult
import ago.chat.android.core.domain.team.OperatorTeamApi
import ago.chat.android.core.domain.team.RevokeInviteResult
import ago.chat.android.di.IoDispatcher
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
 * `26-242`: the sent/pending-invite list beneath Люди's roster, and the revoke action on each still-open
 * one. Its own view model, separate from [PeopleViewModel] — the invite list is a genuinely different
 * concern from the roster ([OperatorInvitesUiState]'s own doc comment), read through the same
 * [OperatorTeamApi] port, gated server-side by the same `site:manage_operators` the whole «Люди»
 * segment is already only reachable behind.
 *
 * Not folded into [PeopleViewModel]: keeping the two reads independent means a failed invite read never
 * blanks a roster that loaded fine, and a revoke refreshes only this list. The dependency rule is why
 * the port lives in `:core:domain` and this view model holds only that interface — a view model holding
 * an `HttpClient` could not be tested without one; the alternative, an in-view-model `fetch`, is exactly
 * what [ago.chat.android.core.network.team.KtorOperatorTeamApi] exists to keep out of here.
 */
@HiltViewModel
public class OperatorInvitesViewModel
    @Inject
    constructor(
        private val api: OperatorTeamApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<OperatorInvitesUiState>(OperatorInvitesUiState.Loading)
        public val state: StateFlow<OperatorInvitesUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load and the retry a [OperatorInvitesUiState.Failed] screen offers — one function
         * for both, the same "a retry is just asking again" shape [PeopleViewModel.refresh] states. */
        public fun refresh() {
            mutableState.update { OperatorInvitesUiState.Loading }
            viewModelScope.launch {
                mutableState.update { load() }
            }
        }

        /**
         * Revoke one invite, then refresh the list so the row shows its new (Revoked) status. [revokingId]
         * marks the row in flight so a second tap cannot fire while the first is outstanding. On success
         * the whole list is re-read — never mutated in place — so the row's status, and whether it is
         * still revocable at all, comes from the server rather than being guessed here. On failure the
         * list is left exactly as it was (the invite is still open and still revocable) and [revokeFailed]
         * is raised for an inline, retryable error, the same posture `OperatorsTeamPage`'s own revoke
         * error alert takes. Only ever acts on the currently loaded list; a revoke requested while not in
         * [OperatorInvitesUiState.Loaded] is ignored, since there is no row to mark.
         */
        public fun revoke(operatorInviteId: String) {
            val current = mutableState.value
            if (current !is OperatorInvitesUiState.Loaded || current.revokingId != null) return

            mutableState.update { OperatorInvitesUiState.Loaded(current.invites, revokingId = operatorInviteId, revokeFailed = false) }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.revokeInvite(operatorInviteId) }
                when (result) {
                    RevokeInviteResult.Revoked -> mutableState.update { load() }
                    is RevokeInviteResult.Failed ->
                        mutableState.update {
                            val latest = it
                            if (latest is OperatorInvitesUiState.Loaded) {
                                latest.copy(revokingId = null, revokeFailed = true)
                            } else {
                                latest
                            }
                        }
                }
            }
        }

        private suspend fun load(): OperatorInvitesUiState =
            when (val result = withContext(ioDispatcher) { api.listInvites() }) {
                is OperatorInvitesResult.Loaded -> OperatorInvitesUiState.Loaded(result.invites)
                is OperatorInvitesResult.Failed -> OperatorInvitesUiState.Failed(result.reason)
            }
    }
