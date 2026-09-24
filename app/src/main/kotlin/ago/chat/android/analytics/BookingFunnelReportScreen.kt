package ago.chat.android.analytics

import ago.chat.android.R
import ago.chat.android.core.domain.analytics.BookingFunnelReport
import ago.chat.android.core.domain.analytics.BookingFunnelReportFailure
import ago.chat.android.core.domain.analytics.compareCounts
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId

/**
 * `26-73`: «Воронка записи» — the fourth of the five administrator reports behind Аналитика's own
 * overflow, and **the smallest screen in the whole batch**: two counts and a caveat, nothing to break
 * down by (`docs/backlog/26-73-*.md`'s own Found section). Obtains its own
 * [BookingFunnelReportViewModel] via [hiltViewModel], the identical wiring
 * [TagBreakdownReportRoute] already establishes; because [ago.chat.android.shell.AnalyticsTabHost]
 * composes this only while the report is open, that view model — and its first network call — come
 * into existence only when an operator actually asks for the report.
 */
@Composable
public fun BookingFunnelReportRoute(
    onBack: () -> Unit,
    viewModel: BookingFunnelReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BookingFunnelReportScreen(
        state = state,
        onApplyRange = viewModel::load,
        onRetry = viewModel::retry,
        onBack = onBack,
    )
}

/**
 * The stateless half — [TagBreakdownReportScreen]'s own doc comment gives the two reasons this shape
 * repeats verbatim: no [ago.chat.android.ui.components.AccountAvatarAction] (a drill-in, not a
 * top-level destination) and the date-range control staying on screen through every state (this
 * screen's own failure can be the operator's own mistake, `ModuleFlow.InvalidRange`).
 *
 * **No preset chip row, unlike [ConversionReportScreen]/[TagBreakdownReportScreen].**
 * `docs/backlog/26-73-*.md`'s own Scope item 7: the console's own `BookingFlowConversionPage` has no
 * date presets at all — two `<input type="date">` fields and an apply button only — and inventing three
 * presets here that screen's console sibling does not have would be a design change riding along with a
 * port, exactly what this item's own Found section warns `scope-inventory.md` §5 is wrong to copy
 * literally for this one report.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookingFunnelReportScreen(
    state: BookingFunnelReportUiState,
    onApplyRange: (from: String?, to: String?) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.analytics_report_booking_funnel)) },
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

                (state as? BookingFunnelReportUiState.Loaded)?.let { loaded ->
                    AnalyticsRangeLabel(from = loaded.report.from, to = loaded.report.to, zone = zone)
                }

                when (state) {
                    BookingFunnelReportUiState.Loading -> AnalyticsLoadingBody()
                    is BookingFunnelReportUiState.Loaded -> BookingFunnelReportSections(report = state.report, zone = zone)
                    BookingFunnelReportUiState.InvalidRange ->
                        AnalyticsInlineMessage(stringResource(R.string.analytics_booking_funnel_invalid_range_error))
                    is BookingFunnelReportUiState.Failed -> AnalyticsRefusalBody(message = failureMessage(state.reason), onRetry = onRetry)
                }
            }
        }
    }
}

@Composable
private fun failureMessage(reason: BookingFunnelReportFailure): String =
    when (reason) {
        BookingFunnelReportFailure.Transport -> stringResource(R.string.analytics_load_failed_transport)
        BookingFunnelReportFailure.Unexpected -> stringResource(R.string.analytics_load_failed_unexpected)
    }

/**
 * The report's body: a `flowsStarted == 0` window renders one honest empty line and nothing else — the
 * same specific-count check (never a null check) `docs/backlog/26-73-*.md`'s own Done-when requires,
 * mirroring `BookingFlowConversionPage`'s own `flowsStarted === 0` branch. Otherwise: the caveat first
 * and unconditional, then the two stat cards named in `docs/backlog/26-73-*.md`'s own Scope item 4, then
 * the previous-window comparison for both numbers (Scope item 5).
 *
 * **The caveat is drawn before the two count cards, every time this state is reached** — the identical
 * "never a caption a reader can scroll past unread" discipline [ConversionReportScreen]'s own
 * not-a-verified-sale banner already holds itself to, restated here for the "closed is not confirmed"
 * caveat this report exists to keep visible.
 */
@Composable
private fun BookingFunnelReportSections(
    report: BookingFunnelReport,
    zone: ZoneId,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (report.flowsStarted == 0) {
            item { AnalyticsEmptySectionText(stringResource(R.string.analytics_booking_funnel_empty)) }
            return@LazyColumn
        }

        item {
            Text(
                text = stringResource(R.string.analytics_booking_funnel_caveat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item { BookingFunnelCountCard(titleRes = R.string.analytics_booking_funnel_started_label, count = report.flowsStarted) }
        item { BookingFunnelCountCard(titleRes = R.string.analytics_booking_funnel_closed_label, count = report.flowsClosed) }
        item { PreviousPeriodCard(report = report, zone = zone) }
    }
}

/**
 * One of [BookingFunnelReportSections]'s own two stat cards — a single big count, not a table row: this
 * report has no dimension to break either number down by, so a card with one number is the honest shape
 * (`docs/backlog/26-73-*.md`'s own Found section, quoting the console's own `<dl>` of exactly two
 * entries).
 */
@Composable
private fun BookingFunnelCountCard(
    titleRes: Int,
    count: Int,
) {
    AnalyticsStatCard(title = stringResource(titleRes)) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * The preceding window of equal length, the identical "its own card, not an annotation squeezed beside
 * each figure" shape [ConversionReportScreen]'s own `PreviousPeriodCard` establishes — restated here for
 * this report's own pair of counts, the way `docs/backlog/26-73-*.md`'s own Scope item 5 asks for "the
 * previous-window comparison for both numbers".
 */
@Composable
private fun PreviousPeriodCard(
    report: BookingFunnelReport,
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
            stringResource(R.string.analytics_booking_funnel_started_label),
            comparisonText(compareCounts(report.flowsStarted, report.previousFlowsStarted)),
        )
        AnalyticsKeyValueRow(
            stringResource(R.string.analytics_booking_funnel_closed_label),
            comparisonText(compareCounts(report.flowsClosed, report.previousFlowsClosed)),
        )
    }
}
