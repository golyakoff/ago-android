package ago.chat.android.analytics

import ago.chat.android.R
import ago.chat.android.core.domain.analytics.TagBreakdownBucketRow
import ago.chat.android.core.domain.analytics.TagBreakdownReport
import ago.chat.android.core.domain.analytics.TagBreakdownReportFailure
import ago.chat.android.core.domain.analytics.compareCounts
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
 * `26-72`: «По меткам» — the third of the five administrator reports behind Аналитика's own overflow.
 * Obtains its own [TagBreakdownReportViewModel] via [hiltViewModel], the identical wiring
 * [ConversionReportRoute] already establishes; because [ago.chat.android.shell.AnalyticsTabHost] composes
 * this only while the report is open, that view model — and its first network call — come into
 * existence only when an operator actually asks for the report.
 */
@Composable
public fun TagBreakdownReportRoute(
    onBack: () -> Unit,
    viewModel: TagBreakdownReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TagBreakdownReportScreen(
        state = state,
        onApplyRange = viewModel::load,
        onRetry = viewModel::retry,
        onBack = onBack,
    )
}

/**
 * The stateless half — [ConversionReportScreen]'s own doc comment gives the two reasons this shape
 * repeats verbatim: no [ago.chat.android.ui.components.AccountAvatarAction] (a drill-in, not a
 * top-level destination) and the date-range control staying on screen through every state (this
 * screen's own failure can be the operator's own mistake, `Analytics.InvalidRange`).
 *
 * The same preset chip row [ConversionReportScreen] adds above the free-form fields —
 * `docs/backlog/26-72-*.md`'s own Scope item 7 names the identical three presets, ported via
 * [AnalyticsPresetChipRow] rather than duplicated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagBreakdownReportScreen(
    state: TagBreakdownReportUiState,
    onApplyRange: (from: String?, to: String?) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.analytics_report_tag_breakdown)) },
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

                (state as? TagBreakdownReportUiState.Loaded)?.let { loaded ->
                    AnalyticsRangeLabel(from = loaded.report.from, to = loaded.report.to, zone = zone)
                }

                when (state) {
                    TagBreakdownReportUiState.Loading -> AnalyticsLoadingBody()
                    is TagBreakdownReportUiState.Loaded -> TagBreakdownReportSections(report = state.report, zone = zone)
                    TagBreakdownReportUiState.InvalidRange ->
                        AnalyticsInlineMessage(stringResource(R.string.analytics_invalid_range_error))
                    is TagBreakdownReportUiState.Failed -> AnalyticsRefusalBody(message = failureMessage(state.reason), onRetry = onRetry)
                }
            }
        }
    }
}

@Composable
private fun failureMessage(reason: TagBreakdownReportFailure): String =
    when (reason) {
        TagBreakdownReportFailure.Transport -> stringResource(R.string.analytics_load_failed_transport)
        TagBreakdownReportFailure.Unexpected -> stringResource(R.string.analytics_load_failed_unexpected)
    }

/**
 * The report's body: coverage first and unconditional, then the preceding window beside it, then the
 * per-tag breakdown — or, when there are no conversations at all in the window, one honest empty line
 * standing in for all three, the identical early branch `ago-console`'s own `TagBreakdownReportPage`
 * takes rather than a coverage card reading "0 / 0".
 *
 * **The coverage card is drawn before anything else, every time this state is reached and the window
 * holds at least one conversation** — `docs/backlog/26-72-*.md`'s own Scope item 4 calls a per-tag
 * breakdown shown without it the defect this report exists to avoid, the identical discipline
 * [ConversionReportScreen]'s own not-a-verified-sale banner already holds itself to for a different
 * number.
 */
@Composable
private fun TagBreakdownReportSections(
    report: TagBreakdownReport,
    zone: ZoneId,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (report.totalConversationCount == 0) {
            item { AnalyticsEmptySectionText(stringResource(R.string.analytics_tag_breakdown_empty)) }
            return@LazyColumn
        }

        item { CoverageCard(report) }
        item { PreviousPeriodCard(report = report, zone = zone) }
        item { TagTable(rows = report.byTag) }
    }
}

/**
 * "Tagged of total, and the percentage" — `docs/backlog/26-72-*.md`'s own Scope item 4, verbatim. A `—`
 * for [TagBreakdownReport.percentageTagged] when it is `null` — never `0%` — via [percentageOrNoData],
 * the identical never-a-fake-zero rule [ConversionReportScreen]'s own `rateOrNoData` already keeps for
 * a structurally similar field.
 */
@Composable
private fun CoverageCard(report: TagBreakdownReport) {
    AnalyticsStatCard(title = stringResource(R.string.analytics_coverage_heading)) {
        AnalyticsKeyValueRow(stringResource(R.string.analytics_conversation_count_label), report.totalConversationCount.toString())
        AnalyticsKeyValueRow(stringResource(R.string.analytics_tagged_label), report.taggedConversationCount.toString())
        AnalyticsKeyValueRow(stringResource(R.string.analytics_coverage_label), percentageOrNoData(report.percentageTagged))
    }
}

/**
 * The preceding window of equal length, as its own card — [ConversionReportScreen]'s own
 * `PreviousPeriodCard` doc comment gives the full reasoning this one repeats unchanged.
 *
 * **[TagBreakdownReport.totalConversationCount] and [TagBreakdownReport.taggedConversationCount] are
 * what is compared**, not [TagBreakdownReport.percentageTagged] itself — the same "only the counts the
 * server actually measured for both windows" restraint [ConversionReportScreen]'s own `PreviousPeriodCard`
 * already applies to [ago.chat.android.core.domain.analytics.ConversionBucket.conversionRate]. A
 * percentage-point comparison would be a second computation this app has not built or tested, and the
 * current window's own coverage percentage already sits one card above this one.
 */
@Composable
private fun PreviousPeriodCard(
    report: TagBreakdownReport,
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
            stringResource(R.string.analytics_conversation_count_label),
            comparisonText(compareCounts(report.totalConversationCount, report.previousTotalConversationCount)),
        )
        AnalyticsKeyValueRow(
            stringResource(R.string.analytics_tagged_label),
            comparisonText(compareCounts(report.taggedConversationCount, report.previousTaggedConversationCount)),
        )
    }
}

/** [TagBreakdownReport.percentageTagged]/[TagBreakdownReport.previousPercentageTagged] share this one
 * rendering — a percentage with no fraction pair, unlike [TagBreakdownBucketRow.conversionRate] below,
 * because the counts it is built from ([TagBreakdownReport.totalConversationCount]/
 * [TagBreakdownReport.taggedConversationCount]) are already drawn as their own rows immediately above it
 * in [CoverageCard]. */
@Composable
private fun percentageOrNoData(percentage: Double?): String {
    val value = percentage ?: return stringResource(R.string.analytics_no_data_value)
    return stringResource(R.string.analytics_percentage_value, String.format(Locale.US, "%.1f", value * 100))
}

@Composable
private fun tagRateOrNoData(row: TagBreakdownBucketRow): String {
    val rate = row.conversionRate ?: return stringResource(R.string.analytics_no_data_value)
    val percent = String.format(Locale.US, "%.1f", rate * 100)
    return stringResource(R.string.analytics_rate_value, percent, row.convertedCount, row.recordedCount)
}

// -------------------------------------------------------------------------------------- the tag table

/**
 * A captioned, horizontally-scrollable table — the identical shape [ConversionReportScreen]'s own
 * `OperatorTable` establishes, built fresh here rather than reused because the column set is genuinely
 * different (a tag's own name and count, not an operator attribution), the same argument that
 * composable's own doc comment already makes for not folding into [ago.chat.android.analytics.SiteAnalyticsScreen]'s
 * `BreakdownTable`.
 *
 * **The multi-tag note is drawn immediately above the table, and only when there is a table to draw** —
 * `docs/backlog/26-72-*.md`'s own Done-when: the note about [TagBreakdownBucketRow.conversationCount] not
 * summing to [TagBreakdownReport.totalConversationCount] must render wherever the tag rows do, the
 * identical placement `ago-console`'s own `TagBreakdownReportPage` already uses.
 */
@Composable
private fun TagTable(rows: List<TagBreakdownBucketRow>) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        SectionLabel(text = stringResource(R.string.analytics_report_tag_breakdown))
        if (rows.isEmpty()) {
            AnalyticsEmptySectionText(stringResource(R.string.analytics_by_tag_empty))
            return@Column
        }
        AnalyticsNote(stringResource(R.string.analytics_multi_tag_note))
        ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Row {
                    AnalyticsTableHeaderCell(
                        stringResource(R.string.analytics_tag_column),
                        AnalyticsTableLabelColumnWidth,
                        alignEnd = false,
                    )
                    AnalyticsTableHeaderCell(stringResource(R.string.analytics_conversation_count_label), AnalyticsTableNumberColumnWidth)
                    AnalyticsTableHeaderCell(stringResource(R.string.analytics_converted_label), AnalyticsTableNumberColumnWidth)
                    AnalyticsTableHeaderCell(stringResource(R.string.analytics_not_converted_label), AnalyticsTableNumberColumnWidth)
                    AnalyticsTableHeaderCell(stringResource(R.string.analytics_rate_label), AnalyticsTableNumberColumnWidth)
                }
                HorizontalDivider()
                rows.forEach { row ->
                    Row {
                        AnalyticsTableBodyCell(row.tagName, AnalyticsTableLabelColumnWidth, alignEnd = false)
                        AnalyticsTableBodyCell(row.conversationCount.toString(), AnalyticsTableNumberColumnWidth)
                        AnalyticsTableBodyCell(row.convertedCount.toString(), AnalyticsTableNumberColumnWidth)
                        AnalyticsTableBodyCell(row.notConvertedCount.toString(), AnalyticsTableNumberColumnWidth)
                        AnalyticsTableBodyCell(tagRateOrNoData(row), AnalyticsTableNumberColumnWidth)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
