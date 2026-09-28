package ago.chat.android.automation

import ago.chat.android.core.domain.ai.AiReplyDraftApi
import ago.chat.android.core.domain.ai.AiReplyDraftStatusResult
import ago.chat.android.core.domain.ai.AiReplyDraftWriteResult
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
 * `26-246` (`ago-console`'s own `AiReplyDraftPage`): Автоматизация → «ИИ-подсказки» — reads the status
 * once, and turns the one shared AI switch on or off through [AiReplyDraftApi.setEnabled]. The identical
 * read-then-act shape [OfflineAutoReplyViewModel] establishes, minus its editable draft: this screen has
 * exactly one control, so [setEnabled] is the whole write surface.
 *
 * **Never an optimistic toggle** — [setEnabled] re-reads [AiReplyDraftApi.fetch] after a successful
 * write and renders from that, exactly as the console's own `run` re-fetches after every mutating call
 * rather than flipping local state on click. A failed toggle leaves the last-read status untouched.
 *
 * **[setEnabled] is a no-op while a toggle is already in flight**, read off the *current* state rather
 * than a separate guard flag — the identical discipline [OfflineAutoReplyViewModel.save]'s own doc
 * comment states.
 */
@HiltViewModel
internal class AiReplyDraftViewModel
    @Inject
    constructor(
        private val api: AiReplyDraftApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<AiReplyDraftUiState>(AiReplyDraftUiState.Loading)
        val state: StateFlow<AiReplyDraftUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, and the [AiReplyDraftUiState.Failed] retry. */
        fun refresh() {
            mutableState.update { AiReplyDraftUiState.Loading }
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.fetch() }) {
                    is AiReplyDraftStatusResult.Loaded ->
                        mutableState.update { AiReplyDraftUiState.Loaded(status = result.status) }

                    is AiReplyDraftStatusResult.Failed ->
                        mutableState.update { AiReplyDraftUiState.Failed(result.reason) }
                }
            }
        }

        /**
         * Turns the shared AI switch [enabled] and back, then re-reads the status so the screen renders
         * the server's own new position rather than an assumed one. A no-op while a toggle is already in
         * flight, or while the screen is not [AiReplyDraftUiState.Loaded] (nothing to toggle during the
         * initial load or a load failure).
         */
        fun setEnabled(enabled: Boolean) {
            val loaded = mutableState.value as? AiReplyDraftUiState.Loaded ?: return
            if (loaded.busy) return

            mutableState.update { current ->
                (current as? AiReplyDraftUiState.Loaded)?.copy(busy = true, actionError = null) ?: current
            }

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.setEnabled(enabled) }) {
                    AiReplyDraftWriteResult.Saved -> reloadAfterToggle()

                    is AiReplyDraftWriteResult.Refused ->
                        mutableState.update { current ->
                            (current as? AiReplyDraftUiState.Loaded)?.copy(
                                busy = false,
                                actionError = AiReplyDraftActionError.ServerRefusal(result.detail),
                            ) ?: current
                        }

                    is AiReplyDraftWriteResult.Failed ->
                        mutableState.update { current ->
                            (current as? AiReplyDraftUiState.Loaded)?.copy(
                                busy = false,
                                actionError = AiReplyDraftActionError.Unavailable(result.reason),
                            ) ?: current
                        }
                }
            }
        }

        /** After a `2xx` write, re-read the status. A [AiReplyDraftStatusResult.Loaded] re-seeds the
         * status and bumps `savedTick`; a [AiReplyDraftStatusResult.Failed] re-read (rare — the write
         * itself landed) keeps the last-known status on screen and surfaces the read failure as an action
         * error rather than throwing the operator to a full-screen retry over a switch that did flip. */
        private suspend fun reloadAfterToggle() {
            when (val result = withContext(ioDispatcher) { api.fetch() }) {
                is AiReplyDraftStatusResult.Loaded ->
                    mutableState.update { current ->
                        val previousTick = (current as? AiReplyDraftUiState.Loaded)?.savedTick ?: 0
                        AiReplyDraftUiState.Loaded(status = result.status, savedTick = previousTick + 1)
                    }

                is AiReplyDraftStatusResult.Failed ->
                    mutableState.update { current ->
                        (current as? AiReplyDraftUiState.Loaded)?.copy(
                            busy = false,
                            actionError = AiReplyDraftActionError.Unavailable(result.reason),
                        ) ?: current
                    }
            }
        }
    }
