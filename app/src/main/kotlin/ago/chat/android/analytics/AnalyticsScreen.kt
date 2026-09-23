package ago.chat.android.analytics

import ago.chat.android.R
import ago.chat.android.core.domain.analytics.AnalyticsBucket
import ago.chat.android.core.domain.analytics.ConversionBucket
import ago.chat.android.core.domain.analytics.OperatorLoadSummary
import ago.chat.android.core.domain.analytics.OwnAnalytics
import ago.chat.android.core.domain.analytics.OwnAnalyticsFailure
import ago.chat.android.core.domain.analytics.endOfDayIso
import ago.chat.android.core.domain.analytics.formatDurationSeconds
import ago.chat.android.core.domain.analytics.startOfDayIso
import ago.chat.android.ui.components.SectionLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `26-57`: Аналитика, for real — replaces `ago.chat.android.shell.AnalyticsPlaceholderScreen` at
 * `AppShellScreen.kt`'s own `NavHost`. Obtains its own [AnalyticsViewModel] via [hiltViewModel] — the
 * identical wiring [ago.chat.android.bookings.BookingsRoute] already establishes for Записи.
 */
@Composable
public fun AnalyticsRoute(viewModel: AnalyticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AnalyticsScreen(state = state, onApplyRange = viewModel::load, onRetry = viewModel::retry)
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
) {
    val zone = remember { ZoneId.systemDefault() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(topBar = { TopAppBar(title = { Text(text = stringResource(R.string.nav_analytics)) }) }) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                DateRangeControl(
                    zone = zone,
                    onApply = { from, to -> onApplyRange(from, to) },
                )

                (state as? AnalyticsUiState.Loaded)?.let { loaded ->
                    RangeLabel(analytics = loaded.analytics, zone = zone)
                }

                when (state) {
                    AnalyticsUiState.Loading -> LoadingBody()
                    is AnalyticsUiState.Loaded -> AnalyticsSections(analytics = state.analytics)
                    AnalyticsUiState.InvalidRange -> InlineMessage(stringResource(R.string.analytics_invalid_range_error))
                    is AnalyticsUiState.Failed -> RefusalBody(reason = state.reason, onRetry = onRetry)
                }
            }
        }
    }
}

/**
 * **A date, never a range, is what [DatePicker] hands back.** `26-57`'s own reading of `ago-console`'s
 * two `<input type="date">` fields — one picker per bound rather than a single Material 3
 * `DateRangePicker`, which would force both bounds to be chosen together and forbid the "only `to`" /
 * "only `from`" half-open windows the server itself accepts (`docs/backlog/26-57-*.md`'s own Found
 * section: "both range bounds are optional"). Each field keeps its own chosen [LocalDate] as a
 * `rememberSaveable` ISO string — [LocalDate] itself has no built-in
 * [androidx.compose.runtime.saveable.Saver], and a plain string survives process death for free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeControl(
    zone: ZoneId,
    onApply: (from: String?, to: String?) -> Unit,
) {
    var fromDate by rememberSaveable { mutableStateOf<String?>(null) }
    var toDate by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<DateField?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField(
                label = stringResource(R.string.analytics_from_field_label),
                value = fromDate,
                modifier = Modifier.weight(1f),
                onClick = { editing = DateField.From },
            )
            DateField(
                label = stringResource(R.string.analytics_to_field_label),
                value = toDate,
                modifier = Modifier.weight(1f),
                onClick = { editing = DateField.To },
            )
        }
        Button(
            onClick = {
                onApply(
                    fromDate?.let { startOfDayIso(LocalDate.parse(it), zone) },
                    toDate?.let { endOfDayIso(LocalDate.parse(it), zone) },
                )
            },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(text = stringResource(R.string.analytics_apply_button))
        }
    }

    editing?.let { field ->
        val initialDate = if (field == DateField.From) fromDate else toDate
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialDate?.let(::epochMillisAtUtcMidnight))
        DatePickerDialog(
            onDismissRequest = { editing = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val picked = pickerState.selectedDateMillis?.let(::localDateAtUtcMidnight)
                        if (picked != null) {
                            if (field == DateField.From) fromDate = picked else toDate = picked
                        }
                        editing = null
                    },
                ) {
                    Text(text = stringResource(R.string.analytics_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text(text = stringResource(R.string.analytics_dialog_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private enum class DateField { From, To }

/** [DatePicker] speaks in UTC-midnight epoch millis regardless of the device's own zone — its own
 * documented contract, not this screen's choice. [epochMillisAtUtcMidnight]/[localDateAtUtcMidnight] are
 * the pair that keeps that one UTC-anchored round trip in one place rather than inlined at both call
 * sites above. */
private fun epochMillisAtUtcMidnight(isoLocalDate: String): Long =
    LocalDate
        .parse(isoLocalDate)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

private fun localDateAtUtcMidnight(epochMillis: Long): String =
    Instant
        .ofEpochMilli(epochMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .toString()

@Composable
private fun DateField(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text(text = value?.let { dateStampOrNull(it) } ?: label)
    }
}

@Composable
private fun RangeLabel(
    analytics: OwnAnalytics,
    zone: ZoneId,
) {
    val from = instantDateStampOrNull(analytics.from, zone)
    val to = instantDateStampOrNull(analytics.to, zone)
    if (from != null && to != null) {
        Text(
            text = stringResource(R.string.analytics_range_label, from, to),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun InlineMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}

/** `docs/backlog/26-57-*.md`'s own Done-when: "renders as a refusal with a retry" — the identical
 * [ago.chat.android.bookings.BookingsScreen] shape for every failure other than
 * `Analytics.InvalidRange`, which [InlineMessage] above handles instead. */
@Composable
private fun RefusalBody(
    reason: OwnAnalyticsFailure,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = failureMessage(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.action_retry))
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
 * The mockup's own `.card`/`.kv` shape — a [SectionLabel] heading over a stack of key/value rows, never
 * a table (`docs/backlog/26-57-*.md`'s own Scope item 3). Three sections, each saying for itself
 * whether it has data — [bucket] always does ([AnalyticsBucket] is zero-filled, never absent); [load]
 * and [conversion] each independently may not (`docs/backlog/26-57-*.md`'s own Scope item 4).
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
    StatCard(title = stringResource(R.string.analytics_bucket_heading)) {
        KeyValueRow(stringResource(R.string.analytics_conversation_count_label), bucket.conversationCount.toString())
        KeyValueRow(stringResource(R.string.analytics_average_first_response_label), durationOrNoData(bucket.averageFirstResponseSeconds))
        KeyValueRow(stringResource(R.string.analytics_average_duration_label), durationOrNoData(bucket.averageDurationSeconds))
        KeyValueRow(stringResource(R.string.analytics_missed_count_label), bucket.missedCount.toString())
    }
}

@Composable
private fun LoadSection(load: OperatorLoadSummary?) {
    StatCard(title = stringResource(R.string.analytics_load_heading)) {
        if (load == null) {
            EmptySectionText(stringResource(R.string.analytics_load_empty))
        } else {
            KeyValueRow(stringResource(R.string.analytics_held_label), load.conversationsHeld.toString())
            KeyValueRow(stringResource(R.string.analytics_standard_label), load.standardIntervals.toString())
            KeyValueRow(stringResource(R.string.analytics_additional_label), load.additionalIntervals.toString())
        }
    }
}

@Composable
private fun ConversionSection(conversion: ConversionBucket?) {
    StatCard(title = stringResource(R.string.analytics_conversion_heading)) {
        if (conversion == null) {
            EmptySectionText(stringResource(R.string.analytics_conversion_empty))
        } else {
            Text(
                text = stringResource(R.string.analytics_conversion_not_verified_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 8.dp),
            )
            KeyValueRow(stringResource(R.string.analytics_converted_label), conversion.convertedCount.toString())
            KeyValueRow(stringResource(R.string.analytics_not_converted_label), conversion.notConvertedCount.toString())
            KeyValueRow(stringResource(R.string.analytics_follow_up_needed_label), conversion.followUpNeededCount.toString())
            KeyValueRow(stringResource(R.string.analytics_unset_label), conversion.unsetCount.toString())
            KeyValueRow(stringResource(R.string.analytics_rate_label), rateOrNoData(conversion))
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        SectionLabel(text = title)
        ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

/** The mockup's own `.kv` row — a label in the softer ink, a bold value flush right. */
@Composable
private fun KeyValueRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptySectionText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun durationOrNoData(seconds: Double?): String =
    seconds?.let { formatDurationSeconds(it) } ?: stringResource(R.string.analytics_no_data_value)

@Composable
private fun rateOrNoData(conversion: ConversionBucket): String {
    val rate = conversion.conversionRate ?: return stringResource(R.string.analytics_no_data_value)
    val percent = String.format(Locale.US, "%.1f", rate * 100)
    return stringResource(R.string.analytics_rate_value, percent, conversion.convertedCount, conversion.recordedCount)
}

/** `null` for anything that fails to parse - the same "never invented, rendered honestly" posture
 * [ago.chat.android.bookings.BookingsScreen]'s own `clockTimeOrNull` already takes. Rendered in the
 * device's own zone - the operator reading this screen. */
private fun instantDateStampOrNull(
    iso: String,
    zone: ZoneId,
): String? = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(DATE_STAMP_FORMAT) }.getOrNull()

private fun dateStampOrNull(isoLocalDate: String): String? =
    runCatching {
        LocalDate.parse(isoLocalDate).format(DATE_STAMP_FORMAT)
    }.getOrNull()

private val DATE_STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ru"))
