package ago.chat.android.analytics

import ago.chat.android.core.domain.analytics.OwnAnalyticsApi
import ago.chat.android.core.domain.analytics.OwnAnalyticsResult
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
 * `26-57`: Аналитика's own «Мои показатели» state — one real network call per range, no cache and no
 * hub overlay, the identical "this screen's one promise is a read" shape
 * [ago.chat.android.bookings.BookingsViewModel] already establishes.
 *
 * **Loads the server's own default window on construction, with no range chosen** — [load] runs with
 * `from = null, to = null` from [init], the same "useful with no interaction" first paint
 * `ago-console`'s own `MyNumbersPage` gives every operator (`docs/backlog/26-57-*.md`'s own Scope
 * item 2).
 *
 * [lastFrom]/[lastTo] remember the range the *operator* last asked for — never the server's own
 * [ago.chat.android.core.domain.analytics.OwnAnalytics.from]/`to`, which can differ from the request
 * whenever either bound was omitted and the server defaulted it. [retry] repeats that same request
 * rather than silently falling back to the default window, so a transient failure on a chosen range
 * does not quietly discard the choice.
 */
@HiltViewModel
public class AnalyticsViewModel
    @Inject
    constructor(
        private val api: OwnAnalyticsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<AnalyticsUiState>(AnalyticsUiState.Loading)
        public val state: StateFlow<AnalyticsUiState> = mutableState.asStateFlow()

        private var lastFrom: String? = null
        private var lastTo: String? = null

        init {
            load(from = null, to = null)
        }

        /** The initial load, and every later "apply this range" action — [AnalyticsScreen]'s own date
         * inputs call this directly rather than going through a second, separate function. */
        public fun load(
            from: String?,
            to: String?,
        ) {
            lastFrom = from
            lastTo = to
            mutableState.update { AnalyticsUiState.Loading }
            viewModelScope.launch {
                mutableState.update {
                    when (val result = withContext(ioDispatcher) { api.fetchOwnAnalytics(from, to) }) {
                        is OwnAnalyticsResult.Loaded -> AnalyticsUiState.Loaded(result.analytics)
                        OwnAnalyticsResult.InvalidRange -> AnalyticsUiState.InvalidRange
                        is OwnAnalyticsResult.Failed -> AnalyticsUiState.Failed(result.reason)
                    }
                }
            }
        }

        /** The retry action a [AnalyticsUiState.Failed] screen offers — "ask again" for the identical
         * range, never a silent reset to the default window (this class's own doc comment). */
        public fun retry() {
            load(lastFrom, lastTo)
        }
    }
