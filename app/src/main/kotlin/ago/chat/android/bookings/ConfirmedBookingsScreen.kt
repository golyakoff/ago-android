package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.ConfirmedBooking
import ago.chat.android.core.domain.bookings.ConfirmedBookingIdentity
import ago.chat.android.core.domain.bookings.ConfirmedBookingsStripDay
import ago.chat.android.core.domain.bookings.DayGroup
import ago.chat.android.core.domain.bookings.WorkerGroup
import ago.chat.android.core.domain.bookings.businessLocalTimeOrNull
import ago.chat.android.core.domain.bookings.confirmedBookingIdentity
import ago.chat.android.core.domain.bookings.confirmedBookingsCountLabel
import ago.chat.android.core.domain.bookings.confirmedBookingsMonthLabels
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * `26-51`: Утверждены's own body — the date strip, then the selected day's rows grouped by master. See
 * [ConfirmedBookingsViewModel]'s own doc comment for why this segment's whole state is a sibling of
 * [BookingsUiState] rather than folded into it.
 *
 * `26-117` redraws this whole body to the author's approved mockup — `docs/backlog/26-117-*.md` is the
 * full, hard-requirement list; the composables below cite the specific requirement number they answer
 * rather than restating the whole item at every call site.
 *
 * **A found gap, disclosed rather than papered over.** Hard requirement 8 asks for a «Подтверждён по
 * SMS» row and a «Источник» row on the booking-detail sheet. Neither fact exists anywhere on the wire:
 * `Ago.Calendar.Contracts.ConfirmedBookingResponse` carries no SMS-confirmation timestamp and no source
 * field at all (verified against `ConsoleContracts.cs` — the only `Source` field anywhere in that file
 * belongs to the unrelated customer-merge preview, `CustomerMergeCandidateResponse`), and neither field
 * is named in this item's own "Depends on" line the way `originConversationId` is. [ConfirmedBookingDetailBody]
 * still draws both rows, exactly as the mockup shows — the author's own rule against dropping a required
 * element — but with an honest "—" rather than a fabricated value, the identical `?: "—"` convention
 * [ConfirmedBookingRow] already uses for a genuinely missing [ConfirmedBooking.serviceName]. Making
 * either row real is a `ago-calendar` contract change this item's own scope never authorised.
 */
@Composable
internal fun ConfirmedBookingsBody(
    state: ConfirmedBookingsUiState,
    onSelectDay: (String) -> Unit,
    onRetry: () -> Unit,
    onReveal: (String) -> Unit,
    onOpenDialog: (String) -> Unit,
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
        is ConfirmedBookingsUiState.Loaded -> {
            // `26-117` hard requirement 6: row tap -> detail sheet. Local navigation state, not view-model
            // state - the identical "which sheet is open is UI, not network" split `PeopleRoute`'s own
            // `showInviteSheet` already draws. The booking itself is *derived* from `state.days` on every
            // recomposition rather than captured once, so a `26-117` phone reveal
            // ([ConfirmedBookingsViewModel.reveal]) unmasks the sheet in place with no second copy of the
            // row's own data to fall out of sync.
            var selectedBookingId by rememberSaveable { mutableStateOf<String?>(null) }

            Column(modifier = Modifier.fillMaxSize()) {
                state.actionError?.let { error -> ActionErrorBanner(error = error, modifier = Modifier.fillMaxWidth()) }
                ConfirmedDateStrip(strip = state.strip, selectedDate = state.selectedDate, onSelectDay = onSelectDay)
                val selectedDay = state.selectedDay
                Box(modifier = Modifier.weight(1f)) {
                    // `docs/backlog/26-51-*.md`'s own Done-when: "a day with nothing booked renders a
                    // stated empty state, not a blank area" - `selectedDay == null` is exactly that day,
                    // since `groupByDayThenWorker` never produces a `DayGroup` for a date with no
                    // bookings.
                    if (selectedDay == null || selectedDay.workers.isEmpty()) {
                        EmptyBody(stringResource(R.string.bookings_confirmed_day_empty))
                    } else {
                        ConfirmedDayList(
                            day = selectedDay,
                            onRowClick = { bookingId -> selectedBookingId = bookingId },
                            onOpenDialog = onOpenDialog,
                        )
                    }
                }
            }

            val selectedBooking =
                selectedBookingId?.let { id ->
                    state.days
                        .asSequence()
                        .flatMap { it.workers }
                        .flatMap { it.rows }
                        .firstOrNull { it.bookingId == id }
                }
            if (selectedBooking != null) {
                ConfirmedBookingDetailSheet(
                    booking = selectedBooking,
                    revealing = selectedBooking.customerId in state.revealingCustomerIds,
                    onReveal = { onReveal(selectedBooking.customerId) },
                    onOpenDialog = { selectedBooking.originConversationId?.let(onOpenDialog) },
                    onDismiss = { selectedBookingId = null },
                )
            }
        }
    }
}

/**
 * `26-117` hard requirement 4: the month+year label now rides *inside* the identical horizontally
 * scrolling container the day chips already use, rather than a fixed row of its own — a plain
 * `Modifier.horizontalScroll` [Column] wrapping two [Row]s in place of the old `LazyRow`, so both rows
 * are children of the one scroll container and necessarily move together; there is no second scroll
 * state to keep in sync. [Modifier.horizontalScroll] draws no scrollbar of its own, which is what
 * satisfies "no visible scrollbar" with no extra code. A plain `Row`/`forEach` replaces the old `LazyRow`/
 * `items` because the strip is never more than a handful of chips wide (`RANGE_HORIZON_DAYS`,
 * `:core:domain`) — nothing here is large enough to need lazy layout or item keys.
 */
@Composable
private fun ConfirmedDateStrip(
    strip: List<ConfirmedBookingsStripDay>,
    selectedDate: String,
    onSelectDay: (String) -> Unit,
) {
    val weekdayLabels = stringArrayResource(R.array.bookings_weekday_short)
    val monthLabels = stringArrayResource(R.array.bookings_month_full)
    val labels = remember(strip) { confirmedBookingsMonthLabels(strip) }
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState).padding(vertical = 8.dp)) {
        // One label per month, each under its own day span, no "|" divider between two labels - the gap
        // between adjacent labels is the identical `DateStripChipGap` the day row below uses between
        // adjacent chips, via the identical `Arrangement.spacedBy`, so the one gap a month boundary needs
        // (between the last day of one month and the first of the next) lines up with the day row's own
        // gap at that same boundary rather than doubling or dropping it.
        Row(
            horizontalArrangement = Arrangement.spacedBy(DateStripChipGap),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            labels.forEach { label ->
                Text(
                    text = "${monthLabels.getOrElse(label.monthValue - 1) { "" }} ${label.year}",
                    // Same muted font, size and weight as the service/duration sub-label
                    // (`ConfirmedBookingRow`'s own `row.serviceName` `Text`) - no new colour, size or
                    // weight invented for "a month style" (hard requirement 4).
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(monthLabelSpanWidth(label.dayCount)),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(DateStripChipGap),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
        ) {
            strip.forEach { day ->
                DateStripChip(
                    day = day,
                    weekdayLabel = weekdayLabels.getOrElse(day.weekday) { "" },
                    selected = day.date == selectedDate,
                    onClick = { onSelectDay(day.date) },
                )
            }
        }
    }
}

/** The exact pixel width [dayCount] day chips plus the gaps *between* them occupy in the day row below —
 * the width a month label spanning that many days must claim so its own text sits flush under exactly
 * those chips, no more and no less. */
private fun monthLabelSpanWidth(dayCount: Int): Dp = DateStripChipWidth * dayCount + DateStripChipGap * (dayCount - 1)

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
private fun ConfirmedDayList(
    day: DayGroup,
    onRowClick: (String) -> Unit,
    onOpenDialog: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        day.workers.forEach { worker ->
            item(key = "header-${worker.workerId}") { WorkerGroupHeader(worker = worker) }
            items(worker.rows, key = { it.bookingId }) { row ->
                ConfirmedBookingRow(
                    row = row,
                    onClick = { onRowClick(row.bookingId) },
                    // Hard requirement 5/"Dialog link": both icons always render, but the chat icon is
                    // only ever a real tap while a real id is present - see `ConfirmedBooking`'s own doc
                    // comment for why `originConversationId` is `null` on every row today.
                    onOpenDialog = { row.originConversationId?.let(onOpenDialog) },
                )
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
 * `26-117` redraw of this row, per `docs/backlog/26-117-*.md`'s own hard requirements 1, 2 and 5:
 *
 * 1. **Time moves to the row's leading edge** (a fixed-width column), no longer trailing the name — the
 *    mockup's own layout, a leading "when" column followed by a two-line "who/what" block, the shape a
 *    booking list reads naturally (a call log, an appointment book), unlike the old right-aligned instant
 *    this row inherited from [ago.chat.android.conversations.ConversationListScreen]'s own visitor-row
 *    shape (a conversation's *last activity* trails the row; an appointment's *start time* leads it).
 * 2. **The client line is the name**, via [confirmedBookingIdentity] — never
 *    [ago.chat.android.ui.components.IdentifierText] again; that composable's own hex output must not
 *    appear on this screen as an identity (`ConfirmedBookingIdentity`'s own doc comment, `:core:domain`).
 * 5. **Two Material icons, always both, chat then phone** — [AgoIcons.Chat] (the mockup's own
 *    `chat_bubble` glyph shape) and [AgoIcons.Call] (`call`), an [IconButton] pair at the row's trailing
 *    edge. The phone icon's own tap opens the identical detail sheet the row itself opens (that sheet is
 *    where the reveal control lives, hard requirement 9) — it carries no separate action of its own.
 */
@Composable
private fun ConfirmedBookingRow(
    row: ConfirmedBooking,
    onClick: () -> Unit,
    onOpenDialog: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = businessLocalTimeOrNull(row.startsAt) ?: "—",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.width(RowTimeColumnWidth).padding(start = 8.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = RowLineGap)) {
            Text(
                text = confirmedBookingIdentityText(confirmedBookingIdentity(row)),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
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
        // Hard requirement 5: always both, chat (left) then phone (right) - `enabled` is the "no-op/
        // disabled" state the "Dialog link" section asks for while `originConversationId` is absent,
        // never a hidden icon.
        IconButton(onClick = onOpenDialog, enabled = row.originConversationId != null) {
            Icon(imageVector = AgoIcons.Chat, contentDescription = stringResource(R.string.bookings_confirmed_open_dialog_action))
        }
        IconButton(onClick = onClick) {
            Icon(imageVector = AgoIcons.Call, contentDescription = stringResource(R.string.bookings_confirmed_call_action))
        }
    }
}

/** [ConfirmedBookingIdentity]'s own three arms, rendered — shared verbatim between the row
 * ([ConfirmedBookingRow]) and the detail sheet's own header ([ConfirmedBookingDetailBody]) so the two
 * surfaces can never word the identical fallback differently. */
@Composable
private fun confirmedBookingIdentityText(identity: ConfirmedBookingIdentity): String =
    when (identity) {
        is ConfirmedBookingIdentity.Name -> identity.displayName
        is ConfirmedBookingIdentity.MaskedPhone -> identity.phone
        ConfirmedBookingIdentity.NoName -> stringResource(R.string.bookings_confirmed_identity_no_name)
    }

/**
 * `26-117` hard requirements 6-11: the row-tap booking detail — a bottom sheet over the list, the
 * identical [ModalBottomSheet] shape [ago.chat.android.team.InviteColleagueSheet] already establishes,
 * with none of that sheet's own drag-lock: nothing here is a one-shot secret a swipe could lose, so the
 * plain default [androidx.compose.material3.rememberModalBottomSheetState] is enough.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmedBookingDetailSheet(
    booking: ConfirmedBooking,
    revealing: Boolean,
    onReveal: () -> Unit,
    onOpenDialog: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ConfirmedBookingDetailBody(
            booking = booking,
            revealing = revealing,
            onReveal = onReveal,
            onOpenDialog = onOpenDialog,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ConfirmedBookingDetailBody(
    booking: ConfirmedBooking,
    revealing: Boolean,
    onReveal: () -> Unit,
    onOpenDialog: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        // Hard requirement 7: the header is the name (the identical fallback the list row uses - this
        // sheet must not show the hex id either).
        Text(
            text = confirmedBookingIdentityText(confirmedBookingIdentity(booking)),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
        // Hard requirement 7: date LEFT, time RIGHT, no dot/separator between the two containers - two
        // `Text`s at the opposite ends of one `Row`, not one interpolated sentence.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = confirmedBookingDetailDateText(booking),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = confirmedBookingDetailTimeRangeText(booking),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            )
        }

        // Hard requirement 8: Услуга / Мастер / Телефон / Подтверждён по SMS / Источник, each its own
        // row.
        BookingDetailRow(
            label = stringResource(R.string.bookings_card_service_label),
            modifier = Modifier.padding(top = 20.dp),
        ) {
            Text(text = booking.serviceName ?: "—", style = MaterialTheme.typography.bodyMedium)
        }
        BookingDetailRow(label = stringResource(R.string.bookings_card_worker_label), modifier = Modifier.padding(top = 12.dp)) {
            Text(text = booking.workerDisplayName, style = MaterialTheme.typography.bodyMedium)
        }
        // Hard requirement 9: masked value + «Показать», reusing `26-53`'s own reveal control verbatim
        // (`bookings_contacts_reveal_phone`/`_revealing_phone`) rather than a second copy of that string
        // pair for a second surface.
        BookingDetailRow(label = stringResource(R.string.bookings_confirmed_detail_phone_label), modifier = Modifier.padding(top = 12.dp)) {
            Text(text = booking.phone.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
            if (booking.masked && booking.phone.isNotBlank()) {
                TextButton(onClick = onReveal, enabled = !revealing) {
                    Text(
                        text =
                            stringResource(
                                if (revealing) R.string.bookings_contacts_revealing_phone else R.string.bookings_contacts_reveal_phone,
                            ),
                    )
                }
            }
        }
        // `26-117`: a real backend gap, disclosed rather than hidden - see this file's own top-of-file
        // doc comment for the full explanation. Both rows are always drawn, per the mockup's own hard
        // requirement 8, with the identical honest "—" `ConfirmedBookingRow` already uses for a missing
        // `serviceName` - never a fabricated confirmation state or channel name.
        BookingDetailRow(
            label = stringResource(R.string.bookings_confirmed_detail_sms_label),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Text(text = "—", style = MaterialTheme.typography.bodyMedium)
        }
        BookingDetailRow(
            label = stringResource(R.string.bookings_confirmed_detail_source_label),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            // Hard requirement 10: plain text, no styled pill - a bare `Text`, the identical treatment
            // every other row's own value gets on this sheet.
            Text(text = "—", style = MaterialTheme.typography.bodyMedium)
        }

        // Hard requirement 11: «Перейти к диалогу» (primary) then «Закрыть» (secondary). Disabled while
        // `originConversationId` is absent - the "Dialog link" section's own no-op/disabled rule, restated
        // here for the sheet's own copy of the affordance the row's chat icon already carries.
        Button(
            onClick = onOpenDialog,
            enabled = booking.originConversationId != null,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            Text(text = stringResource(R.string.bookings_confirmed_open_dialog_action))
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
            Text(text = stringResource(R.string.bookings_confirmed_close_action))
        }
    }
}

/** Hard requirement 7's own "the date carries the year" - weekday (full name) + day + genitive month +
 * year, e.g. «Вторник, 29 сентября 2026». Falls back to the weekday alone if [ConfirmedBooking.localDate]
 * fails to parse - the identical "never invented, rendered honestly" posture
 * [ago.chat.android.thread.ThreadScreen]'s own `clockTimeOrNull` already takes for a malformed
 * timestamp, restated here for a malformed date. */
@Composable
private fun confirmedBookingDetailDateText(booking: ConfirmedBooking): String {
    val weekdayLabels = stringArrayResource(R.array.bookings_weekday_full)
    val monthGenitiveLabels = stringArrayResource(R.array.bookings_month_genitive)
    val weekday = weekdayLabels.getOrElse(booking.weekday) { "" }
    val date = runCatching { LocalDate.parse(booking.localDate) }.getOrNull() ?: return weekday
    val month = monthGenitiveLabels.getOrElse(date.monthValue - 1) { "" }
    return "$weekday, ${date.dayOfMonth} $month ${date.year}"
}

/** Hard requirement 7's own time container - business-local start–end, the identical
 * [businessLocalTimeOrNull] the row itself already uses for [ConfirmedBooking.startsAt], applied to both
 * bounds. An em dash on either side that fails to parse, never a blank container. */
private fun confirmedBookingDetailTimeRangeText(booking: ConfirmedBooking): String {
    val start = businessLocalTimeOrNull(booking.startsAt) ?: "—"
    val end = businessLocalTimeOrNull(booking.endsAt) ?: "—"
    return "$start–$end"
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

// `26-117`: the row's own leading time column - wide enough for "10:00" in `bodySmall`, bold, with no
// truncation on any locale this app renders (Russian-only today, `docs/architecture.md`).
private val RowTimeColumnWidth = 40.dp
