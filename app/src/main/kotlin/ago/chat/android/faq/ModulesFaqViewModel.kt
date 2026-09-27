package ago.chat.android.faq

import ago.chat.android.core.domain.modules.ModulesApi
import ago.chat.android.core.domain.modules.ModulesResult
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
 * `26-199`/`M1`: the Модули panel's own state machine — reads through [ModulesApi] alone, the identical
 * "loads on construction, retry just asks again" shape
 * [ago.chat.android.automation.TagsViewModel]'s own doc comment states for its closest sibling read. This
 * view model is created only when the operator actually opens the Ещё → Автоматизация → «База знаний»
 * row (`MoreScreen`'s own `openRowId` gate), so the first [ModulesApi.fetch] call happens only then.
 */
@HiltViewModel
public class ModulesFaqViewModel
    @Inject
    constructor(
        private val modulesApi: ModulesApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ModulesFaqUiState>(ModulesFaqUiState.Loading)
        public val state: StateFlow<ModulesFaqUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, the top bar's own «Обновить» action, and the retry a
         * [ModulesFaqUiState.Failed] screen offers — one method for all three, the identical "asking
         * again is the whole of retry" shape
         * [ago.chat.android.analytics.PhoneRevealsReportViewModel.refresh]'s own doc comment states. */
        public fun refresh() {
            mutableState.update { ModulesFaqUiState.Loading }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { modulesApi.fetch() }
                mutableState.update {
                    when (result) {
                        is ModulesResult.Loaded -> ModulesFaqUiState.Loaded(result.modules)
                        is ModulesResult.Failed -> ModulesFaqUiState.Failed(result.reason)
                    }
                }
            }
        }
    }
