package ago.chat.android.analytics

import ago.chat.android.core.domain.analytics.ConversionReportApi
import ago.chat.android.core.domain.analytics.ConversionReportResult
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
 * `26-71`: «Конверсия»'s own state — one real network call per range, no cache and no hub overlay, the
 * identical "this screen's one promise is a read" shape [SiteAnalyticsViewModel] already establishes
 * for its sibling report.
 *
 * **Loads the server's own default window on construction, with no range chosen** — [load] runs with
 * `from = null, to = null` from [init], so the report is useful the instant it opens and before any
 * interaction (`docs/backlog/26-71-*.md`'s own Scope item 3). This view model is created when the
 * report is *opened*, not when Аналитика is, the same lazy-construction shape
 * [ago.chat.android.shell.AnalyticsTabHost] already gives [SiteAnalyticsRoute].
 *
 * [lastFrom]/[lastTo] remember the range the *operator* last asked for — never the server's own
 * [ago.chat.android.core.domain.analytics.ConversionReport.from]/`to`, which can differ from the
 * request whenever either bound was omitted and the server defaulted it. [retry] repeats that same
 * request rather than silently falling back to the default window, so a transient failure on a chosen
 * range does not quietly discard the choice.
 */
@HiltViewModel
public class ConversionReportViewModel
    @Inject
    constructor(
        private val api: ConversionReportApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ConversionReportUiState>(ConversionReportUiState.Loading)
        public val state: StateFlow<ConversionReportUiState> = mutableState.asStateFlow()

        private var lastFrom: String? = null
        private var lastTo: String? = null

        init {
            load(from = null, to = null)
        }

        /** The initial load, and every later "apply this range" action — [ConversionReportScreen]'s own
         * date inputs and preset chips call this directly rather than going through a second, separate
         * function. */
        public fun load(
            from: String?,
            to: String?,
        ) {
            lastFrom = from
            lastTo = to
            mutableState.update { ConversionReportUiState.Loading }
            viewModelScope.launch {
                mutableState.update {
                    when (val result = withContext(ioDispatcher) { api.fetchConversionReport(from, to) }) {
                        is ConversionReportResult.Loaded -> ConversionReportUiState.Loaded(result.report)
                        ConversionReportResult.InvalidRange -> ConversionReportUiState.InvalidRange
                        is ConversionReportResult.Failed -> ConversionReportUiState.Failed(result.reason)
                    }
                }
            }
        }

        /** The retry action a [ConversionReportUiState.Failed] screen offers — "ask again" for the
         * identical range, never a silent reset to the default window (this class's own doc comment). */
        public fun retry() {
            load(lastFrom, lastTo)
        }
    }
