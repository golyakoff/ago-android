package ago.chat.android.automation

import ago.chat.android.core.domain.cannedresponses.CannedResponse
import ago.chat.android.core.domain.cannedresponses.CannedResponsesApi
import ago.chat.android.core.domain.cannedresponses.CannedResponsesResult
import ago.chat.android.core.domain.cannedresponses.CannedResponsesWriteResult
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
 * `26-220` (`docs/design/tenant-canned-tags-android.md` §1.6): Автоматизация → «Готовые ответы» — reads
 * the library once, and turns every add, edit or delete into one whole-list
 * [CannedResponsesApi.save] call, the identical full-DTO shape
 * [ago.chat.android.automation.OfflineAutoReplyViewModel] already establishes for its own site-scoped
 * settings form — except there is no separate draft here at all:
 * [CannedResponsesUiState.Loaded.responses] *is* the next request, since
 * [CannedResponsesApi]'s own doc comment states there is no per-item endpoint to send anything smaller
 * to.
 *
 * **Every mutation re-seeds from the echo, never the request.** [addOrReplace] and [delete] each compute
 * the *next* list locally and hand it to [save], which PUTs it whole and, on
 * [CannedResponsesWriteResult.Saved], replaces [CannedResponsesUiState.Loaded.responses] with the
 * server's own echoed list - the identical reload-after-write discipline
 * [ago.chat.android.automation.OfflineAutoReplyViewModel]'s own doc comment states for its own save. On
 * [CannedResponsesWriteResult.Refused]/[CannedResponsesWriteResult.Failed] the in-memory list is left
 * exactly as it was and the error surfaces - the editor stays open so the operator can retry or back out
 * (`docs/design/tenant-canned-tags-android.md` §1.5).
 */
@HiltViewModel
internal class CannedResponsesViewModel
    @Inject
    constructor(
        private val api: CannedResponsesApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<CannedResponsesUiState>(CannedResponsesUiState.Loading)
        val state: StateFlow<CannedResponsesUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, and the [CannedResponsesUiState.Failed] retry. */
        fun refresh() {
            mutableState.update { CannedResponsesUiState.Loading }
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.fetch() }) {
                    is CannedResponsesResult.Loaded -> mutableState.update { CannedResponsesUiState.Loaded(responses = result.responses) }
                    is CannedResponsesResult.Failed -> mutableState.update { CannedResponsesUiState.Failed(result.reason) }
                }
            }
        }

        /**
         * Appends a new response ([index] `null`, the FAB's own effect) or replaces the one already at
         * [index] (an existing row's own editor) - either way, one whole-list [save]. An out-of-range
         * [index] is ignored rather than crashing, the identical defensive posture
         * [ago.chat.android.automation.OfflineAutoReplyViewModel.moveRule] already takes for its own
         * index arguments.
         */
        fun addOrReplace(
            index: Int?,
            title: String,
            body: String,
        ) {
            val loaded = mutableState.value as? CannedResponsesUiState.Loaded ?: return
            if (loaded.saving) return

            val response = CannedResponse(title = title, body = body)
            val next = loaded.responses.toMutableList()
            if (index == null) {
                next.add(response)
            } else {
                if (index !in next.indices) return
                next[index] = response
            }
            save(next)
        }

        /** Removes the response at [index] - the delete confirm's own effect. An out-of-range [index] is
         * ignored rather than crashing. */
        fun delete(index: Int) {
            val loaded = mutableState.value as? CannedResponsesUiState.Loaded ?: return
            if (loaded.saving) return
            if (index !in loaded.responses.indices) return

            save(loaded.responses.toMutableList().apply { removeAt(index) })
        }

        /**
         * The one place [addOrReplace] and [delete] share: run [validateCannedResponsesDraft] first - a
         * problem stops here as [CannedResponsesActionError.Invalid], no network call made. Otherwise
         * builds the request from [meaningfulCannedResponses] (blank entries dropped, order preserved,
         * title trimmed, body sent as typed - the identical trimming asymmetry
         * [validateCannedResponsesDraft]'s own doc comment states) and calls [CannedResponsesApi.save].
         */
        private fun save(nextResponses: List<CannedResponse>) {
            val problem = validateCannedResponsesDraft(nextResponses)
            if (problem != null) {
                mutableState.update { current ->
                    (current as? CannedResponsesUiState.Loaded)?.copy(
                        error = CannedResponsesActionError.Invalid(problem),
                    ) ?: current
                }
                return
            }

            mutableState.update { current ->
                (current as? CannedResponsesUiState.Loaded)?.copy(saving = true, error = null) ?: current
            }

            val request = meaningfulCannedResponses(nextResponses).map { it.copy(title = it.title.trim()) }

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.save(request) }) {
                    is CannedResponsesWriteResult.Saved ->
                        mutableState.update { current ->
                            val previousTick = (current as? CannedResponsesUiState.Loaded)?.savedTick ?: 0
                            CannedResponsesUiState.Loaded(responses = result.responses, savedTick = previousTick + 1)
                        }

                    is CannedResponsesWriteResult.Refused ->
                        mutableState.update { current ->
                            (current as? CannedResponsesUiState.Loaded)?.copy(
                                saving = false,
                                error = CannedResponsesActionError.ServerRefusal(result.detail),
                            ) ?: current
                        }

                    is CannedResponsesWriteResult.Failed ->
                        mutableState.update { current ->
                            (current as? CannedResponsesUiState.Loaded)?.copy(
                                saving = false,
                                error = CannedResponsesActionError.Unavailable(result.reason),
                            ) ?: current
                        }
                }
            }
        }
    }
