package ago.chat.android.analytics

import ago.chat.android.core.domain.analytics.SiteAnalytics
import ago.chat.android.core.domain.analytics.SiteAnalyticsFailure

/**
 * `26-70`: [SiteAnalyticsViewModel]'s whole state — a direct reflection of
 * [ago.chat.android.core.domain.analytics.SiteAnalyticsResult] plus the one state that result type has
 * no reason to know about, [Loading] (before the first answer for the current range has come back at
 * all) — the identical shape [AnalyticsUiState] already establishes for the personal report.
 */
public sealed interface SiteAnalyticsUiState {
    public data object Loading : SiteAnalyticsUiState

    public data class Loaded(
        val analytics: SiteAnalytics,
    ) : SiteAnalyticsUiState

    /** `Analytics.InvalidRange` — its own arm, not folded into [Failed], so [SiteAnalyticsScreen] can
     * give it the one message this screen tells apart from the rest
     * (`docs/backlog/26-70-*.md`'s own Scope item 6). */
    public data object InvalidRange : SiteAnalyticsUiState

    public data class Failed(
        val reason: SiteAnalyticsFailure,
    ) : SiteAnalyticsUiState
}
