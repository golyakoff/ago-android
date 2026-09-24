package ago.chat.android.analytics

import ago.chat.android.R
import ago.chat.android.core.domain.analytics.ConversionBucket
import ago.chat.android.core.domain.analytics.ConversionReport
import ago.chat.android.core.domain.analytics.ConversionReportFailure
import ago.chat.android.core.domain.analytics.compareCounts
import ago.chat.android.core.domain.shortId
import ago.chat.android.ui.components.SectionLabel
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * `26-71`: «Конверсия» — the second of the five administrator reports behind Аналитика's own overflow.
 * Obtains its own [ConversionReportViewModel] via [hiltViewModel], the identical wiring
 * [SiteAnalyticsRoute] already establishes; because [ago.chat.android.shell.AnalyticsTabHost] composes
 * this only while the report is open, that view model — and its first network call — come into
 * existence only when an operator actually asks for the report.
 */
@Composable
public fun ConversionReportRoute(
    onBack: () -> Unit,
    viewModel: ConversionReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ConversionReportScreen(
        state = state,
        onApplyRange = viewModel::load,
        onRetry = viewModel::retry,
        onBack = onBack,
    )
}

/**
 * The stateless half — [SiteAnalyticsScreen]'s own doc comment gives the two reasons this shape
 * repeats verbatim: no [ago.chat.android.ui.components.AccountAvatarAction] (a drill-in, not a
 * top-level destination) and the date-range control staying on screen through every state (this
 * screen's own failure can be the operator's own mistake, `Analytics.InvalidRange`).
 *
 * **A preset chip row above the free-form fields, unlike [SiteAnalyticsScreen].** `docs/backlog/26-71-
 * *.md`'s own Scope item 7 names three presets this report's console sibling offers
 * (`ConversionReportPage`'s own row of buttons) that `26-70` had no equivalent of — ported here via
 * [AnalyticsPresetChipRow] rather than duplicated, since `26-72`..`26-74` may want the identical row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversionReportScreen(
    state: ConversionReportUiState,
    onApplyRange: (from: String?, to: String?) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.analytics_report_conversion)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = AgoIcons.Back,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                AnalyticsPresetChipRow(zone = zone, onSelect = { from, to -> onApplyRange(from, to) })
                AnalyticsDateRangeControl(zone = zone, onApply = onApplyRange)

                (state as? ConversionReportUiState.Loaded)?.let { loaded ->
                    AnalyticsRangeLabel(from = loaded.report.from, to = loaded.report.to, zone = zone)
                }

                when (state) {
                    ConversionReportUiState.Loading -> AnalyticsLoadingBody()
                    is ConversionReportUiState.Loaded -> ConversionReportSections(report = state.report, zone = zone)
                    ConversionReportUiState.InvalidRange ->
                        AnalyticsInlineMessage(stringResource(R.string.analytics_invalid_range_error))
                    is ConversionReportUiState.Failed -> AnalyticsRefusalBody(message = failureMessage(state.reason), onRetry = onRetry)
                }
            }
        }
    }
}

@Composable
private fun failureMessage(reason: ConversionReportFailure): String =
    when (reason) {
        ConversionReportFailure.Transport -> stringResource(R.string.analytics_load_failed_transport)
        ConversionReportFailure.Unexpected -> stringResource(R.string.analytics_load_failed_unexpected)
    }

/**
 * The report's body: the not-a-verified-sale banner first and unconditional, then the overall bucket
 * as a card, the preceding window beside it, then the per-operator breakdown.
 *
 * **The banner is drawn before the overall card, every time this state is reached** — never only when
 * the bucket has real counts in it — because `docs/backlog/26-71-*.md`'s own Scope item 4 calls a rate
 * shown without it the defect this report exists to avoid, and a banner that could be scrolled past
 * unread would be exactly that.
 */
@Composable
private fun ConversionReportSections(
    report: ConversionReport,
    zone: ZoneId,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                text = stringResource(R.string.analytics_conversion_not_verified_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item { OverallCard(report.overall) }
        item { PreviousPeriodCard(report = report, zone = zone) }
        item {
            OperatorTable(
                rows =
                    report.byOperator.map {
                        ConversionRow(
                            label = it.operatorName?.takeIf { name -> name.isNotBlank() } ?: shortId(it.operatorId),
                            bucket = it.bucket,
                        )
                    },
            )
        }
    }
}

@Composable
private fun OverallCard(overall: ConversionBucket) {
    AnalyticsStatCard(title = stringResource(R.string.analytics_overall_heading)) {
        AnalyticsKeyValueRow(stringResource(R.string.analytics_converted_label), overall.convertedCount.toString())
        AnalyticsKeyValueRow(stringResource(R.string.analytics_not_converted_label), overall.notConvertedCount.toString())
        AnalyticsKeyValueRow(stringResource(R.string.analytics_follow_up_needed_label), overall.followUpNeededCount.toString())
        AnalyticsKeyValueRow(stringResource(R.string.analytics_unset_label), overall.unsetCount.toString())
        AnalyticsKeyValueRow(stringResource(R.string.analytics_rate_label), rateOrNoData(overall))
    }
}

/**
 * The preceding window of equal length, the identical "its own card, not an annotation squeezed
 * beside each figure" shape [SiteAnalyticsScreen]'s own `PreviousWindowCard` establishes.
 *
 * **[ConversionBucket.convertedCount] and [ConversionBucket.unsetCount] are what is compared**, not a
 * rate delta — [CountComparison][ago.chat.android.core.domain.analytics.CountComparison] only ever
 * compares two whole numbers ([SiteAnalyticsScreen]'s own `PreviousWindowCard` compares exactly two
 * for the identical reason), and a percentage-point comparison would be a second computation this app
 * has not built or tested. `unsetCount` sits beside `convertedCount` here rather than `recordedCount`
 * because it is this report's own honesty figure — whether operators are recording more or fewer
 * outcomes is as worth tracking period-over-period as the headline count itself.
 */
@Composable
private fun PreviousPeriodCard(
    report: ConversionReport,
    zone: ZoneId,
) {
    AnalyticsStatCard(title = stringResource(R.string.analytics_previous_period_heading)) {
        AnalyticsRangeLabel(
            from = report.previousFrom,
            to = report.previousTo,
            zone = zone,
            labelRes = R.string.analytics_previous_range_value,
        )
        AnalyticsKeyValueRow(
            stringResource(R.string.analytics_converted_label),
            comparisonText(compareCounts(report.overall.convertedCount, report.previousOverall.convertedCount)),
        )
        AnalyticsKeyValueRow(
            stringResource(R.string.analytics_unset_label),
            comparisonText(compareCounts(report.overall.unsetCount, report.previousOverall.unsetCount)),
        )
    }
}

@Composable
private fun rateOrNoData(bucket: ConversionBucket): String {
    val rate = bucket.conversionRate ?: return stringResource(R.string.analytics_no_data_value)
    val percent = String.format(Locale.US, "%.1f", rate * 100)
    return stringResource(R.string.analytics_rate_value, percent, bucket.convertedCount, bucket.recordedCount)
}

// --------------------------------------------------------------------------------- the operator table

/** One row of the operator breakdown. [label] is already resolved to what an operator reads — a real
 * name or the truncated id — the identical "resolved before the table sees it" shape
 * [SiteAnalyticsScreen]'s own `BreakdownTableRow.label` establishes. */
private data class ConversionRow(
    val label: String,
    val bucket: ConversionBucket,
)

/**
 * A captioned, horizontally-scrollable table — the identical shape
 * [SiteAnalyticsScreen]'s own `BreakdownTable` establishes for its four breakdowns, built fresh here
 * rather than reused because the column set is genuinely different (five outcome counts and a rate,
 * not a duration/miss-rate bucket) — reusing that composable would mean either a second, unrelated
 * column shape hidden behind a boolean flag, or forcing [ConversionBucket] to pretend to be an
 * [ago.chat.android.core.domain.analytics.AnalyticsBucket]. The header/body cells and column widths
 * are shared ([AnalyticsTableHeaderCell]/[AnalyticsTableBodyCell]), so only the column *set* differs,
 * never the visual shape of a cell.
 */
@Composable
private fun OperatorTable(rows: List<ConversionRow>) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        SectionLabel(text = stringResource(R.string.analytics_by_operator_heading))
        if (rows.isEmpty()) {
            AnalyticsEmptySectionText(stringResource(R.string.analytics_conversion_by_operator_empty))
            return@Column
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Row {
                    AnalyticsTableHeaderCell(
                        stringResource(R.string.analytics_operator_column),
                        AnalyticsTableLabelColumnWidth,
                        alignEnd = false,
                    )
                    AnalyticsTableHeaderCell(stringResource(R.string.analytics_converted_label), AnalyticsTableNumberColumnWidth)
                    AnalyticsTableHeaderCell(stringResource(R.string.analytics_not_converted_label), AnalyticsTableNumberColumnWidth)
                    AnalyticsTableHeaderCell(stringResource(R.string.analytics_follow_up_needed_label), AnalyticsTableNumberColumnWidth)
                    AnalyticsTableHeaderCell(stringResource(R.string.analytics_unset_label), AnalyticsTableNumberColumnWidth)
                    AnalyticsTableHeaderCell(stringResource(R.string.analytics_rate_label), AnalyticsTableNumberColumnWidth)
                }
                HorizontalDivider()
                rows.forEach { row ->
                    Row {
                        AnalyticsTableBodyCell(row.label, AnalyticsTableLabelColumnWidth, alignEnd = false)
                        AnalyticsTableBodyCell(row.bucket.convertedCount.toString(), AnalyticsTableNumberColumnWidth)
                        AnalyticsTableBodyCell(row.bucket.notConvertedCount.toString(), AnalyticsTableNumberColumnWidth)
                        AnalyticsTableBodyCell(row.bucket.followUpNeededCount.toString(), AnalyticsTableNumberColumnWidth)
                        AnalyticsTableBodyCell(row.bucket.unsetCount.toString(), AnalyticsTableNumberColumnWidth)
                        AnalyticsTableBodyCell(rateOrNoData(row.bucket), AnalyticsTableNumberColumnWidth)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
