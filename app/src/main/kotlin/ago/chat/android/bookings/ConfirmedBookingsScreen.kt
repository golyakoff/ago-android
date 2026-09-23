package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.ConfirmedBooking
import ago.chat.android.core.domain.bookings.ConfirmedBookingsStripDay
import ago.chat.android.core.domain.bookings.DayGroup
import ago.chat.android.core.domain.bookings.WorkerGroup
import ago.chat.android.core.domain.bookings.businessLocalTimeOrNull
import ago.chat.android.core.domain.bookings.confirmedBookingsCountLabel
import ago.chat.android.ui.components.IdentifierText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * `26-51`: Утверждены's own body — the date strip, then the selected day's rows grouped by master. See
 * [ConfirmedBookingsViewModel]'s own doc comment for why this segment's whole state is a sibling of
 * [BookingsUiState] rather than folded into it.
 */
@Composable
internal fun ConfirmedBookingsBody(
    state: ConfirmedBookingsUiState,
    onSelectDay: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        ConfirmedBookingsUiState.Loading -> LoadingBody()
        ConfirmedBookingsUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
        is ConfirmedBookingsUiState.Failed ->
            RefusalBody(
                reason = state.reason,
                onRetry = onRetry,
                unexpectedMessageRes = R.string.bookings_confirmed_load_failed_unexpected,
            )
        is ConfirmedBookingsUiState.Loaded ->
            Column(modifier = Modifier.fillMaxSize()) {
                ConfirmedDateStrip(strip = state.strip, selectedDate = state.selectedDate, onSelectDay = onSelectDay)
                val selectedDay = state.selectedDay
                // `docs/backlog/26-51-*.md`'s own Done-when: "a day with nothing booked renders a stated
                // empty state, not a blank area" - `selectedDay == null` is exactly that day, since
                // `groupByDayThenWorker` never produces a `DayGroup` for a date with no bookings.
                if (selectedDay == null || selectedDay.workers.isEmpty()) {
                    EmptyBody(stringResource(R.string.bookings_confirmed_day_empty))
                } else {
                    ConfirmedDayList(day = selectedDay)
                }
            }
    }
}

/**
 * The mockup's own horizontal date strip — weekday, day number, a dot on any day the tenant has
 * something booked (`docs/backlog/26-51-*.md`'s own Scope item 2). [ConfirmedBookingsStripDay] already
 * carries every fact one chip needs; this composable does no computation of its own beyond formatting.
 */
@Composable
private fun ConfirmedDateStrip(
    strip: List<ConfirmedBookingsStripDay>,
    selectedDate: String,
    onSelectDay: (String) -> Unit,
) {
    val weekdayLabels = stringArrayResource(R.array.bookings_weekday_short)
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(DateStripChipGap),
    ) {
        items(strip, key = { it.date }) { day ->
            DateStripChip(
                day = day,
                weekdayLabel = weekdayLabels.getOrElse(day.weekday) { "" },
                selected = day.date == selectedDate,
                onClick = { onSelectDay(day.date) },
            )
        }
    }
}

@Composable
private fun DateStripChip(
    day: ConfirmedBookingsStripDay,
    weekdayLabel: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val dayOfMonth = runCatching { LocalDate.parse(day.date).dayOfMonth }.getOrNull()

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(DateStripChipCorner),
        modifier = Modifier.width(DateStripChipWidth).clickable(onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text(text = weekdayLabel, style = MaterialTheme.typography.labelSmall)
            Text(
                text = dayOfMonth?.toString() ?: "—",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 2.dp),
            )
            // The dot - present exactly while `hasBookings`, and otherwise still reserving its own
            // space, so a dot-less day's chip is the same height as one with a dot rather than
            // shifting the row's own baseline.
            Column(modifier = Modifier.padding(top = 4.dp).size(DateStripDotSize)) {
                if (day.hasBookings) {
                    Column(modifier = Modifier.fillMaxSize().background(color = contentColor, shape = CircleShape)) {}
                }
            }
        }
    }
}

/**
 * The selected day's rows, grouped by master — each group headed by the master's own name and count
 * (`docs/backlog/26-51-*.md`'s own Scope item 3: "Ирина Соколова · 4 записи").
 */
@Composable
private fun ConfirmedDayList(day: DayGroup) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        day.workers.forEach { worker ->
            item(key = "header-${worker.workerId}") { WorkerGroupHeader(worker = worker) }
            items(worker.rows, key = { it.bookingId }) { row ->
                ConfirmedBookingRow(row = row)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun WorkerGroupHeader(worker: WorkerGroup) {
    Text(
        text = "${worker.workerDisplayName} · ${confirmedBookingsCountLabel(worker.rows.size)}",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * The mockup's own `.rtop`/`.rmid` shape, the identical metrics
 * [ago.chat.android.conversations.ConversationListScreen]'s own `ConversationRowIdentityLine`/
 * `ConversationRowSnippetLine` already established for a visitor row (`docs/backlog/26-51-*.md`'s own
 * Scope item 3 names that file explicitly): a bold top line pairing the row's own "name" with a bold
 * instant, and a quieter second line pairing a description with a second, quieter fact. Here the "name"
 * is the customer — a person's identity is what an operator scans a booking row for first — paired with
 * the start time; the description is the service, paired with the duration.
 *
 * A customer with no [ConfirmedBooking.customerDisplayName] renders through [IdentifierText] — never a
 * raw GUID, never an invented "Клиент #4790" (`ui/components/IdentifierText.kt`'s own doc comment,
 * `docs/backlog/26-51-*.md`'s own Scope item 3). A `null` [ConfirmedBooking.serviceName] renders as an
 * em dash, the identical fallback `ago-console`'s own `CalendarBookingsPage` column render uses
 * (`calendarApi.ts`'s own `serviceName` remarks) — not [IdentifierText]: unlike the pending queue's own
 * card, this row's service id was never meant to stand in for a name here.
 */
@Composable
private fun ConfirmedBookingRow(row: ConfirmedBooking) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RowLineGap),
        ) {
            val customerDisplayName = row.customerDisplayName
            if (customerDisplayName != null) {
                Text(
                    text = customerDisplayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            } else {
                IdentifierText(
                    id = row.customerId,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = businessLocalTimeOrNull(row.startsAt) ?: "—",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = RowLineGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RowLineGap),
        ) {
            Text(
                text = row.serviceName ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            confirmedDurationMinutesOrNull(row.startsAt, row.endsAt)?.let { minutes ->
                Text(
                    text = stringResource(R.string.bookings_duration_minutes, minutes.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The identical duration computation [ago.chat.android.bookings.BookingsScreen.kt]'s own private
 * `durationMinutesOrNull` makes for the pending card, restated here rather than shared across files for
 * two composables that are each `private` to their own file. */
private fun confirmedDurationMinutesOrNull(
    startsAt: String,
    endsAt: String,
): Long? {
    val start = runCatching { OffsetDateTime.parse(startsAt) }.getOrNull() ?: return null
    val end = runCatching { OffsetDateTime.parse(endsAt) }.getOrNull() ?: return null
    return Duration.between(start, end).toMinutes().takeIf { it >= 0 }
}

// `26-51`: the mockup's own date-strip chip metrics, named once here.
private val DateStripChipWidth = 52.dp
private val DateStripChipCorner = 12.dp
private val DateStripChipGap = 8.dp
private val DateStripDotSize = 6.dp

// `.rtop{gap:8px}` - the identical gap `ConversationListScreen`'s own `RtopGap` names for the same CSS
// rule, restated here rather than imported since that value is `private` to its own file.
private val RowLineGap = 8.dp
