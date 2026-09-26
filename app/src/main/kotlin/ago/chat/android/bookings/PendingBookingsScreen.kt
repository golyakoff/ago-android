package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.ConfirmationCountdown
import ago.chat.android.core.domain.bookings.PendingBooking
import ago.chat.android.core.domain.bookings.businessLocalTimeOrNull
import ago.chat.android.core.domain.bookings.businessLocalWeekdayOrNull
import ago.chat.android.core.domain.bookings.confirmationCountdown
import ago.chat.android.core.domain.bookings.pendingBookingIdentity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * `26-163`: Ожидают's own list and row-tap detail sheet, redrawn to the visual language `26-117` gave
 * Утверждены ([ConfirmedBookingsScreen.kt]) — the same leading time column, the same bold client line via
 * the shared identity fallback, the same «Услуга · N мин» sub-line, and the same [ModalBottomSheet] detail
 * card with label-left/value-right rows. `docs/backlog/26-163-*.md`'s own Why: the pending queue was a raw
 * engineering view (`Услуга 01a08a02`, `Мастер 01a084ec`, `Календарь 01a084eb`) while the wire had carried
 * every name since `26-50`, and it offered «Не пришёл» on rows the server refuses it for.
 *
 * **What deliberately differs from the confirmed sheet, and why each is a fact about the pending state
 * rather than a shortcut:**
 * - **Actions are the veto pair, nothing else.** `Event.Reject` (`PendingConfirmation -> Cancelled`) and
 *   `Event.Cancel` (`PendingConfirmation | Booked -> Cancelled`) are the only transitions
 *   `Ago.Calendar.Api`'s console surface exposes for a pending row (`ConsoleEndpoints.cs`: reject, cancel,
 *   no-show; `Event.MarkNoShow` requires `Booked`). There is no operator *confirm* endpoint at all -
 *   `Event.Confirm` is the sweep's alone - so the accept path is the deadline itself, drawn here as the
 *   «Подтвердится через N ч» line rather than as a button the server would not answer.
 * - **The countdown rides the row's trailing edge** where the confirmed row carries its two icons: on a
 *   veto queue the one fact an operator scans for is how long each row still has, and the list is sorted
 *   by exactly that (`oldestDeadlineFirst`).
 * - **Each row carries its own date** under the time, because this list is deadline-ordered and spans
 *   days, where the confirmed list is already sliced to one selected day by its date strip.
 * - **No «Источник»/«Подтверждён по SMS» rows.** `PendingBookingResponse` carries no `originConversationId`
 *   (only `ConfirmedBookingResponse` does, `26-121`), so «Из чата» has nothing on the wire to bind to for
 *   a pending row - drawing a permanently "—" row here would be a fabricated affordance, not the honest
 *   gap the confirmed sheet's mockup asked for. Putting the column on the pending read model is an
 *   additive calendar change with a number of its own.
 * - **«Телефон» is drawn only when the wire sent one.** `PendingBookingResponse.Phone` is `null` exactly
 *   when the caller lacks `customer:read` ([PendingBooking.phone]'s own doc comment), and a "—" there would
 *   read as "no phone recorded", which is never what a null here means. No reveal control: the audited
 *   reveal is `26-53`'s own flow and this sheet renders the value the server already chose to send,
 *   masked or not.
 *
 * Tapping a veto **closes the sheet first**, then fires the write: the row greys out in the list while the
 * call is in flight ([BookingsUiState.Loaded.busyBookingIds]), a refusal lands in the list's own
 * [ActionErrorBanner] where it is visible, and a success re-reads the queue so the row simply leaves - all
 * three of which would be hidden behind a modal sheet that stayed open.
 */
@Composable
internal fun PendingBookingsList(
    bookings: List<PendingBooking>,
    now: OffsetDateTime,
    busyBookingIds: Set<String>,
    onReject: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    // Local navigation state, not view-model state - the identical "which sheet is open is UI, not
    // network" split [ConfirmedBookingsBody]'s own `selectedBookingId` draws. Derived from [bookings] on
    // every recomposition rather than captured once, so a row that leaves the queue (a successful veto, or
    // the sweep confirming it under the operator) takes its sheet with it instead of showing stale data.
    var selectedBookingId by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(bookings, key = { it.bookingId }) { booking ->
            PendingBookingRow(
                booking = booking,
                now = now,
                busy = booking.bookingId in busyBookingIds,
                onClick = { selectedBookingId = booking.bookingId },
            )
            HorizontalDivider()
        }
    }

    val selectedBooking = selectedBookingId?.let { id -> bookings.firstOrNull { it.bookingId == id } }
    if (selectedBooking != null) {
        PendingBookingDetailSheet(
            booking = selectedBooking,
            now = now,
            busy = selectedBooking.bookingId in busyBookingIds,
            onReject = {
                selectedBookingId = null
                onReject(selectedBooking.bookingId)
            },
            onCancel = {
                selectedBookingId = null
                onCancel(selectedBooking.bookingId)
            },
            onDismiss = { selectedBookingId = null },
        )
    }
}

/**
 * The row, laid out as [ConfirmedBookingRow] is (`26-117` hard requirements 1-2, restated for this
 * queue): a leading fixed-width "when" column, then the client line in bold over the «Услуга · N мин»
 * sub-line, then the trailing countdown. [busy] fades the whole row while a veto for it is on the network -
 * the same per-row `disabled={busyId === row.bookingId}` `CalendarQueuePage.tsx` reads, drawn as alpha
 * because there is no button left on the row to disable.
 */
@Composable
private fun PendingBookingRow(
    booking: PendingBooking,
    now: OffsetDateTime,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy, onClick = onClick)
                .alpha(if (busy) BUSY_ROW_ALPHA else 1f)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Padding OUTSIDE the width - `26-125` bug 1's own ordering, so the column really is
        // `RowTimeColumnWidth` wide for the text.
        Column(modifier = Modifier.padding(start = 8.dp).width(RowTimeColumnWidth)) {
            Text(
                text = businessLocalTimeOrNull(booking.startsAt) ?: "—",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = pendingRowDateOrNull(booking.localDate) ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = RowLineGap)) {
            Text(
                text = bookingIdentityText(pendingBookingIdentity(booking)),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The identical joined sub-line [ConfirmedBookingRow] draws (`26-125` bug 3's own middot
            // join and `weight(1f, fill = false)` reasoning).
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = RowLineGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = booking.serviceName ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                durationMinutesOrNull(booking.startsAt, booking.endsAt)?.let { minutes ->
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = stringResource(R.string.bookings_duration_minutes, minutes.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            text = confirmationCountdownShortText(booking.confirmationDeadline, now),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = RowLineGap, end = 8.dp),
        )
    }
}

/** The identical [ModalBottomSheet] shape [ConfirmedBookingDetailSheet] establishes, opened fully
 * (`skipPartiallyExpanded = true`) for the identical reason: a short fixed card whose actions must be
 * visible without a drag. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PendingBookingDetailSheet(
    booking: PendingBooking,
    now: OffsetDateTime,
    busy: Boolean,
    onReject: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        PendingBookingDetailBody(
            booking = booking,
            now = now,
            busy = busy,
            onReject = onReject,
            onCancel = onCancel,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun PendingBookingDetailBody(
    booking: PendingBooking,
    now: OffsetDateTime,
    busy: Boolean,
    onReject: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        // The header is the name - the identical fallback the row uses; this sheet must not show the hex
        // person id either (`BookingIdentity`'s own doc comment).
        Text(
            text = bookingIdentityText(pendingBookingIdentity(booking)),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
        BookingDetailDateTimeLine(
            localDate = booking.localDate,
            weekday = businessLocalWeekdayOrNull(booking.localDate),
            startsAt = booking.startsAt,
            endsAt = booking.endsAt,
        )
        // The accept path, stated: this is what happens to the row if nobody below is tapped (this file's
        // own doc comment on why there is no «Принять» button beside the two vetoes).
        Text(
            text = confirmationCountdownText(booking.confirmationDeadline, now),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )

        // `26-135`'s own row treatment, verbatim from [ConfirmedBookingDetailBody]: `bodyMedium` labels,
        // bold values pinned to the right edge by `BookingDetailRow`'s weighted spacer, a thin divider
        // between each pair.
        val detailLabelStyle = MaterialTheme.typography.bodyMedium
        val detailValueStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
        BookingDetailRow(
            label = stringResource(R.string.bookings_card_service_label),
            labelStyle = detailLabelStyle,
            modifier = Modifier.padding(top = 20.dp, bottom = 12.dp),
        ) {
            Text(text = booking.serviceName ?: "—", style = detailValueStyle)
        }
        HorizontalDivider()
        BookingDetailRow(
            label = stringResource(R.string.bookings_card_worker_label),
            labelStyle = detailLabelStyle,
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            Text(text = booking.workerDisplayName, style = detailValueStyle)
        }
        // Only when the wire sent one - this file's own doc comment on why a null phone is never "—".
        booking.phone?.takeIf { it.isNotBlank() }?.let { phone ->
            HorizontalDivider()
            BookingDetailRow(
                label = stringResource(R.string.bookings_confirmed_detail_phone_label),
                labelStyle = detailLabelStyle,
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                Text(text = phone, style = detailValueStyle)
            }
        }

        // The two vetoes side by side, equal weight - they are the same transition under two permissions
        // (`CancelBookingHandler`'s own remarks), and neither is the "primary" thing to do to a row that
        // confirms itself if left alone. [busy] disables both: a second tap while the first is on the
        // network must not send a second call (`BookingsViewModel.act`'s own one-tap-one-call rule).
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.bookings_action_reject), maxLines = 1)
            }
            OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.bookings_action_cancel), maxLines = 1)
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
            Text(text = stringResource(R.string.bookings_confirmed_close_action), maxLines = 1)
        }
    }
}

/** The mockup's own full sentence («Подтвердится через 3 ч»), for the sheet - the identical
 * [confirmationCountdown] classification the `26-48` card already rendered. */
@Composable
private fun confirmationCountdownText(
    confirmationDeadline: String,
    now: OffsetDateTime,
): String =
    when (val countdown = confirmationCountdown(confirmationDeadline, now)) {
        is ConfirmationCountdown.HoursRemaining ->
            stringResource(R.string.bookings_confirms_in_hours, countdown.value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())

        ConfirmationCountdown.Unknown -> stringResource(R.string.bookings_deadline_unknown)
    }

/** The row's trailing form of the same fact («через 3 ч») - the verb is already the screen's own caption
 * («Всё подтверждается автоматически»), so the row does not repeat it. */
@Composable
private fun confirmationCountdownShortText(
    confirmationDeadline: String,
    now: OffsetDateTime,
): String =
    when (val countdown = confirmationCountdown(confirmationDeadline, now)) {
        is ConfirmationCountdown.HoursRemaining ->
            stringResource(R.string.bookings_pending_confirms_in_hours_short, countdown.value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())

        ConfirmationCountdown.Unknown -> stringResource(R.string.bookings_deadline_unknown)
    }

/** `dd.MM` of the business-local date - a calendrical read of a date-only string, no zone involved
 * ([businessLocalWeekdayOrNull]'s own doc comment on why that is honest where an instant would not be);
 * `null` for a date that fails to parse, the identical "never invented" posture every other formatter on
 * these two screens takes. Digits only, so the 40dp time column fits it on every locale. */
private fun pendingRowDateOrNull(localDate: String): String? =
    runCatching { LocalDate.parse(localDate).format(ROW_DATE_FORMAT) }.getOrNull()

private val ROW_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

// Material's own disabled-content alpha, applied to a whole row that has no control left to disable.
private const val BUSY_ROW_ALPHA = 0.38f
