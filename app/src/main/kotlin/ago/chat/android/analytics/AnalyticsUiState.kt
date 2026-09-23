package ago.chat.android.analytics

import ago.chat.android.core.domain.analytics.OwnAnalytics
import ago.chat.android.core.domain.analytics.OwnAnalyticsFailure

/**
 * `26-57`: [AnalyticsViewModel]'s whole state — a direct reflection of
 * [ago.chat.android.core.domain.analytics.OwnAnalyticsResult] plus the one state that result type has
 * no reason to know about, [Loading] (before the first answer for the current range has come back at
 * all) — the identical shape [ago.chat.android.bookings.BookingsUiState] already establishes.
 */
public sealed interface AnalyticsUiState {
    public data object Loading : AnalyticsUiState

    public data class Loaded(
        val analytics: OwnAnalytics,
    ) : AnalyticsUiState

    /** `Analytics.InvalidRange` — its own arm, not folded into [Failed], so
     * [AnalyticsScreen] can give it the one message this screen tells apart from the rest
     * (`docs/backlog/26-57-*.md`'s own Scope item 5). */
    public data object InvalidRange : AnalyticsUiState

    public data class Failed(
        val reason: OwnAnalyticsFailure,
    ) : AnalyticsUiState
}
