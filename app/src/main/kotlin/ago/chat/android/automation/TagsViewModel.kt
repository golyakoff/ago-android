package ago.chat.android.automation

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.tags.SiteTagsApi
import ago.chat.android.core.domain.tags.TagDeleteResult
import ago.chat.android.core.domain.tags.TagMutationResult
import ago.chat.android.core.domain.tags.TagVocabularyResult
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
 * `26-225` (`docs/design/tenant-canned-tags-android.md` §2.4): Автоматизация → «Метки» — reads the site's
 * tag vocabulary once, and turns every create, rename or delete into its own [SiteTagsApi] call followed
 * by a [refresh] — unlike [ago.chat.android.automation.CannedResponsesViewModel]'s own whole-list `PUT`,
 * there is no in-memory list this view model edits itself: the server's own re-fetched vocabulary is the
 * only source of truth after a write (`docs/design/tenant-canned-tags-android.md` §2.4's own "no
 * optimistic list edit").
 *
 * **A successful [create]/[rename] bumps [TagsUiState.Loaded.savedTick]**, carried across the
 * post-mutation [refresh] rather than reset by it — [TagsScreen] reads the bump to close the create/rename
 * dialog only once the vocabulary shown already reflects the write, never before. A
 * [TagMutationResult.Refused]/[TagDeleteResult.Refused] (`Tag.AlreadyExists`, `Tag.NotFound`, …) never
 * bumps it and leaves [TagsUiState.Loaded.error] set, so the dialog stays open for the create/rename case
 * and the list shows the refusal for delete's own already-dismissed confirm.
 */
@HiltViewModel
internal class TagsViewModel
    @Inject
    constructor(
        private val api: SiteTagsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<TagsUiState>(TagsUiState.Loading)
        val state: StateFlow<TagsUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, and the [TagsUiState.Failed] retry. */
        fun refresh() {
            mutableState.update { TagsUiState.Loading }
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.fetch() }) {
                    is TagVocabularyResult.Loaded -> mutableState.update { TagsUiState.Loaded(tags = result.tags) }
                    is TagVocabularyResult.Failed -> mutableState.update { TagsUiState.Failed(result.reason) }
                }
            }
        }

        /** The create dialog's own Сохранить — the courtesy check runs first, then one `POST`, then a
         * re-fetch on success. */
        fun create(name: String) {
            val problem = validateTagNameDraft(name)
            if (problem != null) {
                setInvalid(problem)
                return
            }
            if (!beginMutation()) return

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.create(name.trim()) }) {
                    is TagMutationResult.Saved -> reloadAfterMutation(bumpSavedTick = true)
                    is TagMutationResult.Refused -> setRefused(result.detail)
                    is TagMutationResult.Failed -> setUnavailable(result.reason)
                }
            }
        }

        /** The rename dialog's own Сохранить — the identical shape [create] follows, one `PUT` instead of
         * a `POST`. */
        fun rename(
            tagId: String,
            name: String,
        ) {
            val problem = validateTagNameDraft(name)
            if (problem != null) {
                setInvalid(problem)
                return
            }
            if (!beginMutation()) return

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.rename(tagId, name.trim()) }) {
                    is TagMutationResult.Saved -> reloadAfterMutation(bumpSavedTick = true)
                    is TagMutationResult.Refused -> setRefused(result.detail)
                    is TagMutationResult.Failed -> setUnavailable(result.reason)
                }
            }
        }

        /** The delete confirm's own danger action — no courtesy check (there is nothing to validate about
         * removing a row), one `DELETE`, then a re-fetch on success. Never bumps `savedTick`: the confirm
         * dialog that triggered this already dismissed itself on the tap, before this call was even made
         * (`docs/design/tenant-canned-tags-android.md` §1.5's own delete-confirm shape, reused here). */
        fun delete(tagId: String) {
            if (!beginMutation()) return

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.delete(tagId) }) {
                    TagDeleteResult.Deleted -> reloadAfterMutation(bumpSavedTick = false)
                    is TagDeleteResult.Refused -> setRefused(result.detail)
                    is TagDeleteResult.Failed -> setUnavailable(result.reason)
                }
            }
        }

        /** The one re-entrancy guard [create]/[rename]/[delete] share: a mutation only starts from
         * [TagsUiState.Loaded] and only when none is already [TagsUiState.Loaded.busy] — the identical
         * defensive posture [ago.chat.android.automation.CannedResponsesViewModel.save] already takes for
         * its own `saving` flag. */
        private fun beginMutation(): Boolean {
            val loaded = mutableState.value as? TagsUiState.Loaded ?: return false
            if (loaded.busy) return false
            mutableState.update { current -> (current as? TagsUiState.Loaded)?.copy(busy = true, error = null) ?: current }
            return true
        }

        /** The one place [create], [rename] and [delete] converge after their own write succeeds — a
         * fresh [SiteTagsApi.fetch] rather than splicing the mutation's own echo into the in-memory list
         * (`docs/design/tenant-canned-tags-android.md` §2.4). [bumpSavedTick] is `true` only for
         * [create]/[rename], the one signal [TagsScreen] needs to close their shared dialog. A re-fetch
         * failure here does not discard the vocabulary already on screen by falling back to
         * [TagsUiState.Failed] — the write itself succeeded, so [TagsActionError.Unavailable] on the
         * still-[TagsUiState.Loaded] state is the honest shape, not a full-screen refusal for a read that
         * merely could not confirm what is already true. */
        private suspend fun reloadAfterMutation(bumpSavedTick: Boolean) {
            when (val result = withContext(ioDispatcher) { api.fetch() }) {
                is TagVocabularyResult.Loaded ->
                    mutableState.update { current ->
                        val previousTick = (current as? TagsUiState.Loaded)?.savedTick ?: 0
                        TagsUiState.Loaded(
                            tags = result.tags,
                            savedTick = if (bumpSavedTick) previousTick + 1 else previousTick,
                        )
                    }

                is TagVocabularyResult.Failed ->
                    mutableState.update { current ->
                        (current as? TagsUiState.Loaded)?.copy(busy = false, error = TagsActionError.Unavailable(result.reason))
                            ?: TagsUiState.Failed(result.reason)
                    }
            }
        }

        private fun setInvalid(problem: TagValidationProblem) {
            mutableState.update { current ->
                (current as? TagsUiState.Loaded)?.copy(error = TagsActionError.Invalid(problem)) ?: current
            }
        }

        private fun setRefused(detail: String) {
            mutableState.update { current ->
                (current as? TagsUiState.Loaded)?.copy(busy = false, error = TagsActionError.ServerRefusal(detail)) ?: current
            }
        }

        private fun setUnavailable(reason: NetworkFailure) {
            mutableState.update { current ->
                (current as? TagsUiState.Loaded)?.copy(busy = false, error = TagsActionError.Unavailable(reason)) ?: current
            }
        }
    }
