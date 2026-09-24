package ago.chat.android.analytics

import ago.chat.android.R
import ago.chat.android.core.domain.analytics.endOfDayIso
import ago.chat.android.core.domain.analytics.startOfDayIso
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `26-70`: the date-range control every Аналитика report draws, lifted verbatim out of
 * [AnalyticsScreen] (`26-57`) so the site report — and `26-71`..`26-74` after it — reuse it instead of
 * each carrying a copy.
 *
 * **Why this one is shared when `startOfDayIso`/`endOfDayIso` deliberately are not.** Those are four
 * lines with no branching, and this codebase's own precedent (`AnalyticsDayBoundary`'s doc comment,
 * quoting `ago-console`) is that restating them costs less than the coupling. This is a stateful
 * composable with two `rememberSaveable` fields, a modal dialog, a UTC-anchored millis round trip and a
 * per-field initial-selection rule — five reports each owning a copy is five chances for one of them to
 * handle "only `to` chosen" differently from the rest. The file is `internal` to `:app` and lives beside
 * its callers rather than in `ui/components`, because none of it is general UI: every line of it is
 * about *this* family of reports and the one endpoint shape they share.
 *
 * ---
 *
 * **A date, never a range, is what [DatePicker] hands back.** `26-57`'s own reading of `ago-console`'s
 * two `<input type="date">` fields — one picker per bound rather than a single Material 3
 * `DateRangePicker`, which would force both bounds to be chosen together and forbid the "only `to`" /
 * "only `from`" half-open windows the server itself accepts. Each field keeps its own chosen
 * [LocalDate] as a `rememberSaveable` ISO string — [LocalDate] has no built-in
 * [androidx.compose.runtime.saveable.Saver], and a plain string survives process death for free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnalyticsDateRangeControl(
    zone: ZoneId,
    onApply: (from: String?, to: String?) -> Unit,
) {
    var fromDate by rememberSaveable { mutableStateOf<String?>(null) }
    var toDate by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<AnalyticsDateField?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnalyticsDateFieldButton(
                label = stringResource(R.string.analytics_from_field_label),
                value = fromDate,
                modifier = Modifier.weight(1f),
                onClick = { editing = AnalyticsDateField.From },
            )
            AnalyticsDateFieldButton(
                label = stringResource(R.string.analytics_to_field_label),
                value = toDate,
                modifier = Modifier.weight(1f),
                onClick = { editing = AnalyticsDateField.To },
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
        val initialDate = if (field == AnalyticsDateField.From) fromDate else toDate
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialDate?.let(::epochMillisAtUtcMidnight))
        DatePickerDialog(
            onDismissRequest = { editing = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val picked = pickerState.selectedDateMillis?.let(::localDateAtUtcMidnight)
                        if (picked != null) {
                            if (field == AnalyticsDateField.From) fromDate = picked else toDate = picked
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

private enum class AnalyticsDateField { From, To }

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
private fun AnalyticsDateFieldButton(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text(text = value?.let { dateStampOrNull(it) } ?: label)
    }
}

/**
 * The window a report actually covers, taken from the **response's** own bounds — never from the
 * inputs, which may be blank when the server defaulted the window
 * (`docs/backlog/26-70-*.md`'s own Done-when, and `26-57`'s before it). Draws nothing at all rather than
 * a half-formed line if either bound fails to parse, which is the same "never invented, rendered
 * honestly" posture [instantDateStampOrNull] itself takes.
 */
@Composable
internal fun AnalyticsRangeLabel(
    from: String,
    to: String,
    zone: ZoneId,
    labelRes: Int = R.string.analytics_range_label,
) {
    val fromStamp = instantDateStampOrNull(from, zone)
    val toStamp = instantDateStampOrNull(to, zone)
    if (fromStamp != null && toStamp != null) {
        Text(
            text = stringResource(labelRes, fromStamp, toStamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/** `null` for anything that fails to parse - the same "never invented, rendered honestly" posture
 * [ago.chat.android.bookings.BookingsScreen]'s own `clockTimeOrNull` already takes. Rendered in the
 * device's own zone - the operator reading this screen. */
internal fun instantDateStampOrNull(
    iso: String,
    zone: ZoneId,
): String? = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(DATE_STAMP_FORMAT) }.getOrNull()

internal fun dateStampOrNull(isoLocalDate: String): String? =
    runCatching {
        LocalDate.parse(isoLocalDate).format(DATE_STAMP_FORMAT)
    }.getOrNull()

private val DATE_STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ru"))
