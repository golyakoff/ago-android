package ago.chat.android.analytics

import ago.chat.android.R
import ago.chat.android.core.domain.analytics.AnalyticsBucket
import ago.chat.android.core.domain.analytics.ConversionBucket
import ago.chat.android.core.domain.analytics.OperatorLoadSummary
import ago.chat.android.core.domain.analytics.OwnAnalytics
import ago.chat.android.core.domain.analytics.OwnAnalyticsFailure
import ago.chat.android.core.domain.navigation.AnalyticsReport
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.ui.components.AccountAvatarAction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.util.Locale

/**
 * `26-57`: Аналитика, for real — replaces `ago.chat.android.shell.AnalyticsPlaceholderScreen` at
 * `AppShellScreen.kt`'s own `NavHost`. Obtains its own [AnalyticsViewModel] via [hiltViewModel] — the
 * identical wiring [ago.chat.android.bookings.BookingsRoute] already establishes for Записи.
 *
 * `26-70`: this is now Аналитика's *landing* screen rather than the whole destination — the
 * administrator reports sit behind the app-bar overflow it draws, and
 * [ago.chat.android.shell.AnalyticsTabHost] one level up owns the switch between the two. [reports] and
 * [onOpenReport] are that host's, passed straight through: this screen decides nothing about which
 * reports exist or who may see them (`visibleAnalyticsReports`, `:core:domain`), only where the control
 * sits.
 */
@Composable
public fun AnalyticsRoute(
    hubConnectionState: OperatorHubConnectionState,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
    reports: List<AnalyticsReport>,
    onOpenReport: (AnalyticsReport) -> Unit,
    operatorDisplayName: String? = null,
    operatorEmail: String? = null,
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AnalyticsScreen(
        state = state,
        onApplyRange = viewModel::load,
        onRetry = viewModel::retry,
        reports = reports,
        onOpenReport = onOpenReport,
        hubConnectionState = hubConnectionState,
        operatorDisplayName = operatorDisplayName,
        operatorEmail = operatorEmail,
        onOpenSettings = onOpenSettings,
        onSignOut = onSignOut,
    )
}

/**
 * The stateless half — see [AnalyticsRoute]'s own doc comment for why [AnalyticsRoute] exists at all.
 *
 * **The date-range control is always on screen, never swapped out for a refusal.** Unlike
 * [ago.chat.android.bookings.BookingsScreen] (a read with no input of its own), this screen's own
 * failure can be the operator's own mistake (`Analytics.InvalidRange`) — hiding the control behind a
 * full-screen refusal would leave no way to fix the very range that caused it. So only the content
 * *below* the control changes with [state]; the control itself is this function's own, not part of
 * [AnalyticsUiState] at all, the same "UI-only state stays in the composable, never in the view model"
 * split `ago-console`'s own `MyNumbersPage` (`fromInput`/`toInput` vs. `effectiveFrom`/`effectiveTo`)
 * already draws.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnalyticsScreen(
    state: AnalyticsUiState,
    onApplyRange: (from: String?, to: String?) -> Unit,
    onRetry: () -> Unit,
    reports: List<AnalyticsReport> = emptyList(),
    onOpenReport: (AnalyticsReport) -> Unit = {},
    hubConnectionState: OperatorHubConnectionState = OperatorHubConnectionState.Disconnected,
    operatorDisplayName: String? = null,
    operatorEmail: String? = null,
    onOpenSettings: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    val zone = remember { ZoneId.systemDefault() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.nav_analytics)) },
                    actions = {
                        // `26-70`: the overflow sits *before* the account avatar, so the avatar keeps
                        // the trailing position it occupies on every other top-level screen - an
                        // account control that moved whenever a report became available would be the
                        // kind of inconsistency `26-77` existed to remove. Draws nothing at all when
                        // this operator can open no report (that composable's own first line).
                        AnalyticsReportsOverflowMenu(reports = reports, onOpenReport = onOpenReport)
                        // `26-77`: Аналитика had neither a presence dot nor a menu before this item -
                        // the same "first `actions` content" gap Записи had (`BookingsScreen`'s own
                        // identical comment).
                        AccountAvatarAction(
                            displayName = operatorDisplayName,
                            email = operatorEmail,
                            hubConnectionState = hubConnectionState,
                            onOpenSettings = onOpenSettings,
                            onSignOut = onSignOut,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                AnalyticsDateRangeControl(
                    zone = zone,
                    onApply = { from, to -> onApplyRange(from, to) },
                )

                (state as? AnalyticsUiState.Loaded)?.let { loaded ->
                    AnalyticsRangeLabel(from = loaded.analytics.from, to = loaded.analytics.to, zone = zone)
                }

                when (state) {
                    AnalyticsUiState.Loading -> AnalyticsLoadingBody()
                    is AnalyticsUiState.Loaded -> AnalyticsSections(analytics = state.analytics)
                    AnalyticsUiState.InvalidRange -> AnalyticsInlineMessage(stringResource(R.string.analytics_invalid_range_error))
                    is AnalyticsUiState.Failed -> AnalyticsRefusalBody(message = failureMessage(state.reason), onRetry = onRetry)
                }
            }
        }
    }
}

@Composable
private fun failureMessage(reason: OwnAnalyticsFailure): String =
    when (reason) {
        OwnAnalyticsFailure.Transport -> stringResource(R.string.analytics_load_failed_transport)
        OwnAnalyticsFailure.Unexpected -> stringResource(R.string.analytics_load_failed_unexpected)
    }

/**
 * The mockup's own `.card`/`.kv` shape — a heading over a stack of key/value rows, never a table
 * (`docs/backlog/26-57-*.md`'s own Scope item 3). Three sections, each saying for itself whether it has
 * data — [bucket][OwnAnalytics.bucket] always does ([AnalyticsBucket] is zero-filled, never absent);
 * [load][OwnAnalytics.load] and [conversion][OwnAnalytics.conversion] each independently may not
 * (`docs/backlog/26-57-*.md`'s own Scope item 4).
 */
@Composable
private fun AnalyticsSections(analytics: OwnAnalytics) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BucketSection(analytics.bucket) }
        item { LoadSection(analytics.load) }
        item { ConversionSection(analytics.conversion) }
    }
}

@Composable
private fun BucketSection(bucket: AnalyticsBucket) {
    AnalyticsStatCard(title = stringResource(R.string.analytics_bucket_heading)) {
        AnalyticsKeyValueRow(stringResource(R.string.analytics_conversation_count_label), bucket.conversationCount.toString())
        AnalyticsKeyValueRow(
            stringResource(R.string.analytics_average_first_response_label),
            durationOrNoAverage(bucket.averageFirstResponseSeconds),
        )
        AnalyticsKeyValueRow(stringResource(R.string.analytics_average_duration_label), durationOrNoAverage(bucket.averageDurationSeconds))
        AnalyticsKeyValueRow(stringResource(R.string.analytics_missed_count_label), bucket.missedCount.toString())
    }
}

@Composable
private fun LoadSection(load: OperatorLoadSummary?) {
    AnalyticsStatCard(title = stringResource(R.string.analytics_load_heading)) {
        if (load == null) {
            AnalyticsEmptySectionText(stringResource(R.string.analytics_load_empty))
        } else {
            AnalyticsKeyValueRow(stringResource(R.string.analytics_held_label), load.conversationsHeld.toString())
            AnalyticsKeyValueRow(stringResource(R.string.analytics_standard_label), load.standardIntervals.toString())
            AnalyticsKeyValueRow(stringResource(R.string.analytics_additional_label), load.additionalIntervals.toString())
        }
    }
}

@Composable
private fun ConversionSection(conversion: ConversionBucket?) {
    AnalyticsStatCard(title = stringResource(R.string.analytics_conversion_heading)) {
        if (conversion == null) {
            AnalyticsEmptySectionText(stringResource(R.string.analytics_conversion_empty))
        } else {
            Text(
                text = stringResource(R.string.analytics_conversion_not_verified_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 8.dp),
            )
            AnalyticsKeyValueRow(stringResource(R.string.analytics_converted_label), conversion.convertedCount.toString())
            AnalyticsKeyValueRow(stringResource(R.string.analytics_not_converted_label), conversion.notConvertedCount.toString())
            AnalyticsKeyValueRow(stringResource(R.string.analytics_follow_up_needed_label), conversion.followUpNeededCount.toString())
            AnalyticsKeyValueRow(stringResource(R.string.analytics_unset_label), conversion.unsetCount.toString())
            AnalyticsKeyValueRow(stringResource(R.string.analytics_rate_label), rateOrNoData(conversion))
        }
    }
}

@Composable
private fun rateOrNoData(conversion: ConversionBucket): String {
    val rate = conversion.conversionRate ?: return stringResource(R.string.analytics_no_data_value)
    val percent = String.format(Locale.US, "%.1f", rate * 100)
    return stringResource(R.string.analytics_rate_value, percent, conversion.convertedCount, conversion.recordedCount)
}
