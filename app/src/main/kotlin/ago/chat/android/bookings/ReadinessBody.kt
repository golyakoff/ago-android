package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.readiness.BookingPrecondition
import ago.chat.android.core.domain.readiness.CalendarReadiness
import ago.chat.android.core.domain.readiness.PreconditionState
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * `26-164`: Записи's own «Готовность» screen (menu label «Готовность», page title the full question
 * «Может ли клиент записаться прямо сейчас?») — one calendar card per server entry, in server order, the
 * identical four-arm `when`/[LoadingBody]/[EmptyBody]/[RefusalBody] idiom [MastersBody] establishes. No
 * form, no busy state of its own: the only action a card offers is «Исправить»/«Слоты», an in-hub
 * navigation swap [onFix] carries out one level up ([BookingsScreen]'s own `onConfigSelected`), never a
 * write through this screen ([ReadinessViewModel]'s own doc comment).
 */
@Composable
internal fun ReadinessBody(
    state: ReadinessUiState,
    onRetry: () -> Unit,
    onFix: (BookingPrecondition) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            ReadinessUiState.Loading -> LoadingBody()
            ReadinessUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
            is ReadinessUiState.Failed ->
                RefusalBody(
                    reason = state.reason,
                    onRetry = onRetry,
                    unexpectedMessageRes = R.string.readiness_load_failed_unexpected,
                )

            is ReadinessUiState.Loaded ->
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(state.calendars) { calendar ->
                        ReadinessCalendarCard(calendar = calendar, onFix = onFix)
                        HorizontalDivider()
                    }
                }
        }
    }
}

/**
 * One calendar's own bookability — the title row ([CalendarReadiness.calendarName], or «Календаря пока
 * нет» for the server's own no-calendar placeholder) with a trailing status word, never a colour alone
 * (the Masters `«Неактивен»` rule [MastersBody]'s own doc comment states), then the six-step chain in
 * server order.
 */
@Composable
private fun ReadinessCalendarCard(
    calendar: CalendarReadiness,
    onFix: (BookingPrecondition) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = calendar.calendarName ?: stringResource(R.string.readiness_no_calendar),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            Text(
                text =
                    stringResource(
                        if (calendar.isBookable) R.string.readiness_bookable else R.string.readiness_not_bookable,
                    ),
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (calendar.isBookable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
        }
        calendar.preconditions.forEach { row -> ReadinessRow(row = row, onFix = onFix) }
    }
}

/**
 * One precondition: [AgoIcons.Check] for met, [AgoIcons.Exclamation] tinted `error` for unmet — the
 * identical pair `SettingsScreen`'s own `StatusGlyph` already draws, restated for a plain row rather than
 * a circular badge — plus its localized label, and, only when unmet and recognised, a trailing
 * «Исправить»/«Слоты» [TextButton]. [BookingPrecondition.Unknown] renders its raw wire spelling with no
 * button at all — the same "classification lives in `:core:domain`, an unknown value is shown as-is"
 * discipline `ContactDetailsSection.fieldLabel` follows for `ContactDetail.kind`.
 */
@Composable
private fun ReadinessRow(
    row: PreconditionState,
    onFix: (BookingPrecondition) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (row.isMet) AgoIcons.Check else AgoIcons.Exclamation,
            // Decorative: the icon's own shape (never colour alone) plus the label beside it already
            // state met/unmet - the identical `StatusGlyph` precedent.
            contentDescription = null,
            tint = if (row.isMet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = readinessPreconditionLabel(row),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        val fixTarget = row.precondition.fixTargetTab()
        if (!row.isMet && fixTarget != null) {
            TextButton(onClick = { onFix(row.precondition) }) {
                Text(
                    text =
                        stringResource(
                            if (row.precondition == BookingPrecondition.SlotsMaterialized) {
                                R.string.readiness_action_slots
                            } else {
                                R.string.readiness_action_fix
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun readinessPreconditionLabel(row: PreconditionState): String =
    when (row.precondition) {
        BookingPrecondition.WorkerOnCalendar -> stringResource(R.string.readiness_precondition_worker_on_calendar)
        BookingPrecondition.ServiceOffered -> stringResource(R.string.readiness_precondition_service_offered)
        BookingPrecondition.WorkingHoursConfigured ->
            stringResource(R.string.readiness_precondition_working_hours_configured)
        BookingPrecondition.ScheduleSaved -> stringResource(R.string.readiness_precondition_schedule_saved)
        BookingPrecondition.SlotsMaterialized -> stringResource(R.string.readiness_precondition_slots_materialized)
        BookingPrecondition.CalendarPublished -> stringResource(R.string.readiness_precondition_calendar_published)
        // The classification lives in `:core:domain`; an unrecognised wire spelling is shown as-is
        // rather than dropped - this function's own doc comment.
        BookingPrecondition.Unknown -> row.rawPrecondition
    }

/**
 * `26-164`'s own «Исправить» map (`docs/design/26-154-*.md` §2) — Android's own screens, not the
 * console's: [BookingPrecondition.WorkingHoursConfigured] points at Часы (a screen the console has no
 * counterpart for — `26-139`'s own deliberate deviation, the console points at Setup because Setup owns
 * its hours form there), and [BookingPrecondition.ScheduleSaved]/[BookingPrecondition.SlotsMaterialized]
 * point at Мастера as a stated interim until `26-155`'s own График/Слоты drill-downs exist.
 * [BookingPrecondition.Unknown] has no target — an unrecognised fact has no screen this client knows to
 * send anyone to, which is why [ReadinessRow] draws no button for it at all.
 */
internal fun BookingPrecondition.fixTargetTab(): BookingsTab? =
    when (this) {
        BookingPrecondition.CalendarPublished -> BookingsTab.Calendars
        BookingPrecondition.WorkerOnCalendar -> BookingsTab.Masters
        BookingPrecondition.ServiceOffered -> BookingsTab.Masters
        BookingPrecondition.WorkingHoursConfigured -> BookingsTab.Hours
        BookingPrecondition.ScheduleSaved -> BookingsTab.Masters
        BookingPrecondition.SlotsMaterialized -> BookingsTab.Masters
        BookingPrecondition.Unknown -> null
    }
