package ago.chat.android.channels

import ago.chat.android.core.domain.widgetconfig.WidgetConfig
import ago.chat.android.core.domain.widgetconfig.WidgetConfigApi
import ago.chat.android.core.domain.widgetconfig.WidgetConfigResult
import ago.chat.android.core.domain.widgetconfig.WidgetConfigWriteResult
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
 * `26-193` (`docs/design/tenant-widget-android.md` §3/§5.2): the one view model every «Виджет на сайте»
 * group editor shares — obtained once via `hiltViewModel()` at [WidgetConfigRoute], never a fresh instance
 * per editor. Holding the *committed* full [WidgetConfig] here, in exactly one place, is what makes the
 * absent-boolean trap the design doc's §3 describes structurally impossible: a save always starts from
 * [WidgetConfigUiState.Loaded.committed] (an editor's own `committed.copy(<its slice>)`, never a value the
 * editor builds any other way) and this view model re-seeds that committed value from the server's own
 * echo, never from what it sent — the identical "one reload-after-write source of truth, never an
 * optimistic flip" discipline [ago.chat.android.bookings.CalendarSetupViewModel] already follows for its
 * own writes.
 *
 * A second, equally load-bearing reason for one shared instance rather than one per editor
 * (`docs/design/tenant-widget-android.md` §3's own "alternative considered" note): three independent view
 * models would each `GET` on their own and could each hold a stale committed copy, so opening two editors
 * in sequence could have the second clobber the first's just-saved change on its own save. One instance,
 * re-seeded on every save, removes that race entirely.
 */
@HiltViewModel
internal class WidgetConfigViewModel
    @Inject
    constructor(
        private val api: WidgetConfigApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<WidgetConfigUiState>(WidgetConfigUiState.Loading)
        val state: StateFlow<WidgetConfigUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, and the retry a [WidgetConfigUiState.Failed] screen offers. */
        fun refresh() {
            mutableState.update { WidgetConfigUiState.Loading }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.fetch() }
                mutableState.update {
                    when (result) {
                        is WidgetConfigResult.Loaded -> WidgetConfigUiState.Loaded(committed = result.config)
                        is WidgetConfigResult.Failed -> WidgetConfigUiState.Failed(result.reason)
                    }
                }
            }
        }

        /**
         * Saves [updated] — a group editor's own `committed.copy(<its slice>)`. This view model trusts its
         * caller for that and never re-derives a slice itself, since it has no notion of which fields
         * belong to which editor; the structural guarantee is that whatever [updated] is, it is always the
         * *whole* [WidgetConfig] the caller started from ([WidgetConfigApi.update]'s own doc comment: the
         * port has no method that accepts a subset).
         */
        fun save(updated: WidgetConfig) {
            val loaded = mutableState.value as? WidgetConfigUiState.Loaded ?: return
            mutableState.update { loaded.copy(saving = true, saveError = null) }
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.update(updated) }) {
                    is WidgetConfigWriteResult.Saved ->
                        mutableState.update { current ->
                            (current as? WidgetConfigUiState.Loaded)?.copy(
                                committed = result.config,
                                saving = false,
                                saveError = null,
                                savedTick = current.savedTick + 1,
                            ) ?: current
                        }

                    is WidgetConfigWriteResult.Refused ->
                        mutableState.update { current ->
                            (current as? WidgetConfigUiState.Loaded)?.copy(
                                saving = false,
                                saveError = WidgetConfigSaveError.ServerRefusal(result.detail),
                            ) ?: current
                        }

                    is WidgetConfigWriteResult.Failed ->
                        mutableState.update { current ->
                            (current as? WidgetConfigUiState.Loaded)?.copy(
                                saving = false,
                                saveError = WidgetConfigSaveError.Unavailable(result.reason),
                            ) ?: current
                        }
                }
            }
        }
    }
