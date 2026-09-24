package ago.chat.android.analytics

import ago.chat.android.core.domain.analytics.SiteAnalyticsApi
import ago.chat.android.core.domain.analytics.SiteAnalyticsResult
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
 * `26-70`: «По сайту»'s own state — one real network call per range, no cache and no hub overlay, the
 * identical "this screen's one promise is a read" shape [AnalyticsViewModel] already establishes for
 * the personal report.
 *
 * **Loads the server's own default window on construction, with no range chosen** — [load] runs with
 * `from = null, to = null` from [init], so the report is useful the instant it opens and before any
 * interaction (`docs/backlog/26-70-*.md`'s own Done-when). This view model is created when the report is
 * *opened*, not when Аналитика is, so that first call costs an operator who never taps `⋮` nothing:
 * [ago.chat.android.shell.AnalyticsTabHost] only composes [SiteAnalyticsRoute] — and therefore only
 * reaches `hiltViewModel()` — while the report is on screen.
 *
 * [lastFrom]/[lastTo] remember the range the *operator* last asked for — never the server's own
 * [ago.chat.android.core.domain.analytics.SiteAnalytics.from]/`to`, which can differ from the request
 * whenever either bound was omitted and the server defaulted it. [retry] repeats that same request
 * rather than silently falling back to the default window, so a transient failure on a chosen range does
 * not quietly discard the choice.
 */
@HiltViewModel
public class SiteAnalyticsViewModel
    @Inject
    constructor(
        private val api: SiteAnalyticsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<SiteAnalyticsUiState>(SiteAnalyticsUiState.Loading)
        public val state: StateFlow<SiteAnalyticsUiState> = mutableState.asStateFlow()

        private var lastFrom: String? = null
        private var lastTo: String? = null

        init {
            load(from = null, to = null)
        }

        /** The initial load, and every later "apply this range" action — [SiteAnalyticsScreen]'s own
         * date inputs call this directly rather than going through a second, separate function. */
        public fun load(
            from: String?,
            to: String?,
        ) {
            lastFrom = from
            lastTo = to
            mutableState.update { SiteAnalyticsUiState.Loading }
            viewModelScope.launch {
                mutableState.update {
                    when (val result = withContext(ioDispatcher) { api.fetchSiteAnalytics(from, to) }) {
                        is SiteAnalyticsResult.Loaded -> SiteAnalyticsUiState.Loaded(result.analytics)
                        SiteAnalyticsResult.InvalidRange -> SiteAnalyticsUiState.InvalidRange
                        is SiteAnalyticsResult.Failed -> SiteAnalyticsUiState.Failed(result.reason)
                    }
                }
            }
        }

        /** The retry action a [SiteAnalyticsUiState.Failed] screen offers — "ask again" for the
         * identical range, never a silent reset to the default window (this class's own doc comment). */
        public fun retry() {
            load(lastFrom, lastTo)
        }
    }
