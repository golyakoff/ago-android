package ago.chat.android.analytics

import ago.chat.android.core.domain.analytics.TagBreakdownReport
import ago.chat.android.core.domain.analytics.TagBreakdownReportFailure

/**
 * `26-72`: [TagBreakdownReportViewModel]'s whole state — a direct reflection of
 * [ago.chat.android.core.domain.analytics.TagBreakdownReportResult] plus the one state that result type
 * has no reason to know about, [Loading] (before the first answer for the current range has come back
 * at all) — the identical shape [ConversionReportUiState] already establishes for its sibling report.
 */
public sealed interface TagBreakdownReportUiState {
    public data object Loading : TagBreakdownReportUiState

    public data class Loaded(
        val report: TagBreakdownReport,
    ) : TagBreakdownReportUiState

    /** `Analytics.InvalidRange` — its own arm, not folded into [Failed], so [TagBreakdownReportScreen]
     * can give it the one message this screen tells apart from the rest. */
    public data object InvalidRange : TagBreakdownReportUiState

    public data class Failed(
        val reason: TagBreakdownReportFailure,
    ) : TagBreakdownReportUiState
}
