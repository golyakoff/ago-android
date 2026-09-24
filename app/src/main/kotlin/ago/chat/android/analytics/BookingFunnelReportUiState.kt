package ago.chat.android.analytics

import ago.chat.android.core.domain.analytics.BookingFunnelReport
import ago.chat.android.core.domain.analytics.BookingFunnelReportFailure

/**
 * `26-73`: [BookingFunnelReportViewModel]'s whole state — a direct reflection of
 * [ago.chat.android.core.domain.analytics.BookingFunnelReportResult] plus the one state that result
 * type has no reason to know about, [Loading] (before the first answer for the current range has come
 * back at all) — the identical shape [TagBreakdownReportUiState] already establishes for its sibling
 * report.
 */
public sealed interface BookingFunnelReportUiState {
    public data object Loading : BookingFunnelReportUiState

    public data class Loaded(
        val report: BookingFunnelReport,
    ) : BookingFunnelReportUiState

    /** `ModuleFlow.InvalidRange` — its own arm, not folded into [Failed], so
     * [BookingFunnelReportScreen] can give it the one message this screen tells apart from the rest,
     * distinct from the message the other analytics screens use for their own `Analytics.InvalidRange`. */
    public data object InvalidRange : BookingFunnelReportUiState

    public data class Failed(
        val reason: BookingFunnelReportFailure,
    ) : BookingFunnelReportUiState
}
