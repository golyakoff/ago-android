package ago.chat.android.analytics

import ago.chat.android.core.domain.analytics.ConversionReport
import ago.chat.android.core.domain.analytics.ConversionReportFailure

/**
 * `26-71`: [ConversionReportViewModel]'s whole state — a direct reflection of
 * [ago.chat.android.core.domain.analytics.ConversionReportResult] plus the one state that result type
 * has no reason to know about, [Loading] (before the first answer for the current range has come back
 * at all) — the identical shape [SiteAnalyticsUiState] already establishes for its sibling report.
 */
public sealed interface ConversionReportUiState {
    public data object Loading : ConversionReportUiState

    public data class Loaded(
        val report: ConversionReport,
    ) : ConversionReportUiState

    /** `Analytics.InvalidRange` — its own arm, not folded into [Failed], so [ConversionReportScreen]
     * can give it the one message this screen tells apart from the rest. */
    public data object InvalidRange : ConversionReportUiState

    public data class Failed(
        val reason: ConversionReportFailure,
    ) : ConversionReportUiState
}
