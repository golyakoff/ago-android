package ago.chat.android.analytics

import ago.chat.android.R
import ago.chat.android.core.domain.analytics.AnalyticsBucket
import ago.chat.android.core.domain.analytics.OperatorLoadSummary
import ago.chat.android.core.domain.analytics.SiteAnalytics
import ago.chat.android.core.domain.analytics.SiteAnalyticsFailure
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

/**
 * `26-70`: «Аналитика сайта» — the first of the five administrator reports behind Аналитика's own
 * overflow. Obtains its own [SiteAnalyticsViewModel] via [hiltViewModel], the identical wiring
 * [AnalyticsRoute] already establishes; because [ago.chat.android.shell.AnalyticsTabHost] composes this
 * only while the report is open, that view model — and its first network call — come into existence
 * only when an operator actually asks for the report.
 */
@Composable
public fun SiteAnalyticsRoute(
    onBack: () -> Unit,
    viewModel: SiteAnalyticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SiteAnalyticsScreen(
        state = state,
        onApplyRange = viewModel::load,
        onRetry = viewModel::retry,
        onBack = onBack,
    )
}

/**
 * The stateless half.
 *
 * **No [ago.chat.android.ui.components.AccountAvatarAction] here, and a back arrow instead.** This is a
 * drill-in, not a top-level destination: `26-77` gave the account menu to the five *tabs*, and the
 * screens one level inside them (the thread, Настройки) each draw a back control in that slot instead.
 * Repeating the avatar here would offer a second route to a menu the operator is one back-press away
 * from anyway.
 *
 * **The date-range control stays on screen through every state**, for the identical reason
 * [AnalyticsScreen] states: this screen's own failure can be the operator's own mistake
 * (`Analytics.InvalidRange`), and hiding the control behind a full-screen refusal would leave no way to
 * fix the range that caused it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SiteAnalyticsScreen(
    state: SiteAnalyticsUiState,
    onApplyRange: (from: String?, to: String?) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.analytics_report_site)) },
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
                AnalyticsDateRangeControl(zone = zone, onApply = onApplyRange)

                (state as? SiteAnalyticsUiState.Loaded)?.let { loaded ->
                    AnalyticsRangeLabel(from = loaded.analytics.from, to = loaded.analytics.to, zone = zone)
                }

                when (state) {
                    SiteAnalyticsUiState.Loading -> AnalyticsLoadingBody()
                    is SiteAnalyticsUiState.Loaded -> SiteAnalyticsSections(analytics = state.analytics, zone = zone)
                    SiteAnalyticsUiState.InvalidRange -> AnalyticsInlineMessage(stringResource(R.string.analytics_invalid_range_error))
                    is SiteAnalyticsUiState.Failed -> AnalyticsRefusalBody(message = failureMessage(state.reason), onRetry = onRetry)
                }
            }
        }
    }
}

@Composable
private fun failureMessage(reason: SiteAnalyticsFailure): String =
    when (reason) {
        SiteAnalyticsFailure.Transport -> stringResource(R.string.analytics_load_failed_transport)
        SiteAnalyticsFailure.Unexpected -> stringResource(R.string.analytics_load_failed_unexpected)
    }

/**
 * The report's body: the overall bucket as a card, the preceding window beside it as a second card,
 * then four independently-captioned tables.
 *
 * **Four tables, not one with a "dimension" column.** Each breakdown groups the *same* window a
 * different way — a conversation appears in all four and the counts do not sum across them
 * ([SiteAnalytics]'s own doc comment) — so each also says for itself when it is empty, rather than one
 * page-wide "no data" standing in for four independent facts
 * (`docs/backlog/26-70-*.md`'s own Done-when).
 *
 * **The overall bucket is a card, not a row prepended to the channel table.** The console does prepend
 * it (`OperatorAnalyticsPage`'s own `__overall` row), which works on a wide table with a visible
 * header; on a phone the numbers a site owner opens this report *for* would then be behind a horizontal
 * scroll, indistinguishable from any other row. Drawing them once, unmissably, at the top is the
 * `scope-inventory.md` §5 shape ("date-range chips, stat cards, one horizontally-scrollable table per
 * breakdown"), and it is also why the channel table below holds only real channels — no duplicated
 * total.
 */
@Composable
private fun SiteAnalyticsSections(
    analytics: SiteAnalytics,
    zone: ZoneId,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { OverallCard(analytics.overall) }
        item { PreviousWindowCard(analytics = analytics, zone = zone) }

        item {
            BreakdownTable(
                title = stringResource(R.string.analytics_by_channel_heading),
                emptyText = stringResource(R.string.analytics_by_channel_empty),
                firstColumnHeader = stringResource(R.string.analytics_channel_column),
                rows = analytics.byChannel.map { BreakdownTableRow(channelLabel(it.channel), it.bucket) },
            )
        }

        item {
            // One `Column` rather than two siblings emitted straight into the item slot: a lazy item's
            // content is one layout node, and the note belongs to the table directly above it anyway.
            Column {
                BreakdownTable(
                    title = stringResource(R.string.analytics_by_operator_heading),
                    emptyText = stringResource(R.string.analytics_by_operator_empty),
                    firstColumnHeader = stringResource(R.string.analytics_operator_column),
                    rows =
                        analytics.byOperator.map {
                            BreakdownTableRow(
                                label = it.operatorName?.takeIf { name -> name.isNotBlank() } ?: shortId(it.operatorId),
                                bucket = it.bucket,
                                load = it.load,
                            )
                        },
                    showLoadColumns = true,
                )
                // `26-70`'s Scope item 5, first of the two notes that survive verbatim in meaning:
                // stated once, directly under the table it explains. "Всего вели" counts conversations;
                // "Штатно" and "Сверх нормы" count assignment intervals, where one conversation held
                // twice counts twice. A reader must not be left to infer that from three column
                // headers. Drawn only when there is a table for it to explain - a caveat about columns
                // nobody is looking at is noise.
                if (analytics.byOperator.isNotEmpty()) {
                    AnalyticsNote(stringResource(R.string.analytics_load_interval_note))
                }
            }
        }

        // The second of the two: referrer and campaign are both what the visitor's browser reported -
        // a client-supplied header and a client-supplied query parameter - never confirmed against a
        // second source. Placed *above* both tables rather than below either, because it qualifies the
        // numbers before they are read, and stated once rather than twice for two facts of identical
        // strength.
        item { AnalyticsNote(stringResource(R.string.analytics_traffic_source_note)) }

        item {
            BreakdownTable(
                title = stringResource(R.string.analytics_by_referrer_heading),
                emptyText = stringResource(R.string.analytics_by_referrer_empty),
                firstColumnHeader = stringResource(R.string.analytics_referrer_column),
                rows = analytics.byReferrer.map { BreakdownTableRow(referrerLabel(it.referrerHost), it.bucket) },
            )
        }

        item {
            BreakdownTable(
                title = stringResource(R.string.analytics_by_campaign_heading),
                emptyText = stringResource(R.string.analytics_by_campaign_empty),
                firstColumnHeader = stringResource(R.string.analytics_campaign_column),
                rows = analytics.byCampaign.map { BreakdownTableRow(it.utmCampaign, it.bucket) },
            )
        }
    }
}

@Composable
private fun OverallCard(overall: AnalyticsBucket) {
    AnalyticsStatCard(title = stringResource(R.string.analytics_overall_heading)) {
        AnalyticsKeyValueRow(stringResource(R.string.analytics_conversation_count_label), overall.conversationCount.toString())
        AnalyticsKeyValueRow(
            stringResource(R.string.analytics_average_first_response_label),
            durationOrNoAverage(overall.averageFirstResponseSeconds),
        )
        AnalyticsKeyValueRow(
            stringResource(R.string.analytics_average_duration_label),
            durationOrNoAverage(overall.averageDurationSeconds),
        )
        AnalyticsKeyValueRow(stringResource(R.string.analytics_missed_count_label), overall.missedCount.toString())
    }
}

/**
 * The preceding window of equal length, as its own card rather than as an annotation squeezed beside
 * each figure — on a phone the comparison text is longer than the figure it qualifies, and a `.kv` row
 * with "12" on the right and "Предыдущий период: 9 (+3, +33.3%)" underneath would be two facts fighting
 * for one line.
 *
 * Only conversation count and missed count are compared, matching the console exactly: the server
 * computes no previous-window average, and deriving a comparison for one here would be a number nobody
 * measured ([SiteAnalytics]'s own doc comment).
 *
 * The window's own dates are drawn above the two rows, because "the preceding period" is meaningless
 * without saying which days it was — and because the server, not this screen, decides where it starts.
 */
@Composable
private fun PreviousWindowCard(
    analytics: SiteAnalytics,
    zone: ZoneId,
) {
    AnalyticsStatCard(title = stringResource(R.string.analytics_previous_period_heading)) {
        AnalyticsRangeLabel(
            from = analytics.previousFrom,
            to = analytics.previousTo,
            zone = zone,
            labelRes = R.string.analytics_previous_range_value,
        )
        AnalyticsKeyValueRow(
            stringResource(R.string.analytics_conversation_count_label),
            comparisonText(compareCounts(analytics.overall.conversationCount, analytics.previousOverall.conversationCount)),
        )
        AnalyticsKeyValueRow(
            stringResource(R.string.analytics_missed_count_label),
            comparisonText(compareCounts(analytics.overall.missedCount, analytics.previousOverall.missedCount)),
        )
    }
}

// --------------------------------------------------------------------------------- the breakdown table

/**
 * One row of any of the four tables. [label] is already resolved to what an operator reads — a channel's
 * translated name, an operator's own name or their truncated id, a referrer host — because the four
 * dimensions resolve it four different ways and a table that knew about all four would be four tables
 * with one name.
 *
 * [load] is the operator table's third state, and the reason it is nullable *here* rather than absent:
 * a `null` in a table that draws load columns means **"no assignment data at all"**, which is neither a
 * real `0` nor the "nothing to average" an absent average means. A table that draws no load columns at
 * all leaves this `null` too, which is why [BreakdownTable.showLoadColumns] exists as a separate flag —
 * the two "no load here" situations are not the same fact and must not be decided by the same test.
 */
private data class BreakdownTableRow(
    val label: String,
    val bucket: AnalyticsBucket,
    val load: OperatorLoadSummary? = null,
)

/**
 * A captioned, horizontally-scrollable table — `scope-inventory.md` §5's own suggestion for exactly this
 * data, and the only honest way to show five-to-eight numeric columns on a phone. The alternative, a
 * card per row, would cost a full screen height per channel and make two rows impossible to compare,
 * which is the one thing a breakdown exists for.
 *
 * Every column has a fixed width so the header and the rows cannot drift apart under the shared
 * scroll — a single [horizontalScroll] wrapping the whole stack, not one per row, is what keeps the
 * header aligned with the cells beneath it while it moves.
 */
@Composable
private fun BreakdownTable(
    title: String,
    emptyText: String,
    firstColumnHeader: String,
    rows: List<BreakdownTableRow>,
    showLoadColumns: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        SectionLabel(text = title)
        if (rows.isEmpty()) {
            AnalyticsEmptySectionText(emptyText)
            return@Column
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Row {
                    AnalyticsTableHeaderCell(firstColumnHeader, AnalyticsTableLabelColumnWidth, alignEnd = false)
                    AnalyticsTableHeaderCell(stringResource(R.string.analytics_conversation_count_label), AnalyticsTableNumberColumnWidth)
                    AnalyticsTableHeaderCell(
                        stringResource(R.string.analytics_average_first_response_label),
                        AnalyticsTableNumberColumnWidth,
                    )
                    AnalyticsTableHeaderCell(stringResource(R.string.analytics_average_duration_label), AnalyticsTableNumberColumnWidth)
                    AnalyticsTableHeaderCell(stringResource(R.string.analytics_missed_count_label), AnalyticsTableNumberColumnWidth)
                    if (showLoadColumns) {
                        AnalyticsTableHeaderCell(stringResource(R.string.analytics_held_label), AnalyticsTableNumberColumnWidth)
                        AnalyticsTableHeaderCell(stringResource(R.string.analytics_standard_label), AnalyticsTableNumberColumnWidth)
                        AnalyticsTableHeaderCell(stringResource(R.string.analytics_additional_label), AnalyticsTableNumberColumnWidth)
                    }
                }
                HorizontalDivider()
                rows.forEach { row ->
                    Row {
                        AnalyticsTableBodyCell(row.label, AnalyticsTableLabelColumnWidth, alignEnd = false)
                        AnalyticsTableBodyCell(row.bucket.conversationCount.toString(), AnalyticsTableNumberColumnWidth)
                        AnalyticsTableBodyCell(durationOrNoAverage(row.bucket.averageFirstResponseSeconds), AnalyticsTableNumberColumnWidth)
                        AnalyticsTableBodyCell(durationOrNoAverage(row.bucket.averageDurationSeconds), AnalyticsTableNumberColumnWidth)
                        AnalyticsTableBodyCell(row.bucket.missedCount.toString(), AnalyticsTableNumberColumnWidth)
                        if (showLoadColumns) {
                            AnalyticsTableBodyCell(loadCellValue(row.load) { it.conversationsHeld }, AnalyticsTableNumberColumnWidth)
                            AnalyticsTableBodyCell(loadCellValue(row.load) { it.standardIntervals }, AnalyticsTableNumberColumnWidth)
                            // Deliberately the same plain cell every other number gets - an operator
                            // whose "сверх нормы" is `0` renders as the digit `0`, with no colour and
                            // no icon, because a zero here is a fact and not a criticism
                            // (`docs/design/decisions.md` §2, and `23-17`'s own Done-when said so
                            // explicitly for the console).
                            AnalyticsTableBodyCell(loadCellValue(row.load) { it.additionalIntervals }, AnalyticsTableNumberColumnWidth)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * **The third of the three values this item exists to keep apart.** A row whose [load] is absent renders
 * «Нет данных» — its own word, distinct from a real `0` (the digit) and from "nothing to average" (the
 * em dash `durationOrNoAverage` uses). One function decides it for all three load columns, so they
 * cannot drift out of step with each other on that call — the same reason `ago-console`'s own
 * `loadCellValue` exists.
 */
@Composable
private fun loadCellValue(
    load: OperatorLoadSummary?,
    select: (OperatorLoadSummary) -> Int,
): String = load?.let { select(it).toString() } ?: stringResource(R.string.analytics_load_no_data_value)

/**
 * `Ago.Chat.Domain.ChannelKind`'s own member names, plus the read-time `"Widget"` label — the wire value
 * is never shown to an operator unlabelled. Falls back to the raw wire value for anything this table
 * does not recognise, so a channel added server-side renders as itself rather than disappearing (the
 * identical fallback `ago-console`'s own `channelLabel` takes). The `when` is on a `String` rather than
 * an enum for exactly that reason — see [ago.chat.android.core.domain.permissions.Permission]'s own doc
 * comment for the same argument applied to permission names.
 */
@Composable
private fun channelLabel(channel: String): String =
    when (channel) {
        "Widget" -> stringResource(R.string.analytics_channel_widget)
        "Sms" -> stringResource(R.string.analytics_channel_sms)
        "Max" -> stringResource(R.string.analytics_channel_max)
        "Telegram" -> stringResource(R.string.analytics_channel_telegram)
        "WhatsApp" -> stringResource(R.string.analytics_channel_whatsapp)
        else -> channel
    }

/** The server's own `DirectReferrerLabel` wire literal is English by construction — like `"Widget"`
 * above it is a read-time label, not a domain value, so it maps to this locale's own word rather than
 * showing an English one inside a Russian report. Any other value is a real host, shown exactly as
 * captured: hosts are not translatable text. */
@Composable
private fun referrerLabel(referrerHost: String): String =
    if (referrerHost == "Direct") stringResource(R.string.analytics_direct_referrer_label) else referrerHost
