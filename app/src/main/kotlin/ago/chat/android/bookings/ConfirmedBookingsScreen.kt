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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.roundToInt

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
 * `26-127`: the month+year label over the day strip is now a **sticky, shrinking header** rather than a
 * label row that scrolls off with its chips.
 *
 * The day strip itself is a [LazyRow] (`26-127`'s own directive to drive the sticky/shrink from the
 * list's `firstVisibleItemIndex` + `firstVisibleItemScrollOffset`) — the plain `horizontalScroll` [Row]
 * `26-117` used carried no per-item scroll position for a sticky header to read. The labels are lifted
 * out of that scrolling row into an overlay [Box] above it and positioned by hand from the derived scroll
 * offset, so they still track the chips exactly (same `DateStripEdgePadding` origin, same chip stride)
 * while the current month can be pinned independently:
 *
 * - the **current** month (the one whose chips hold the left edge, [StickyMonthHeaderGeometry.currentIndex])
 *   is pinned at the strip's left edge, its width capped at how far the *next* month's own label still is
 *   from that edge ([StickyMonthHeaderGeometry.nextMonthStartPx]); as the next month scrolls in, that cap
 *   shrinks the current label — truncating it with an ellipsis — until it reaches zero and the next month
 *   inherits the sticky slot in the same place, at the same width it already occupied. Because the same
 *   geometry is a pure function of the scroll offset, the handoff replays identically scrolling the other
 *   way.
 * - every month **after** the current rides its chips at the identical content offset those chips occupy,
 *   sliding in from the right; the one that becomes current simply switches from this natural offset to
 *   the pinned slot at the same edge, so nothing jumps.
 *
 * The muted sub-label style ([MonthSpanLabel]) is unchanged — `26-127` asked only for the sticky/shrink
 * behaviour, not a new colour or weight. A [Box] with `clipToBounds` hides the labels that have scrolled
 * past either edge; its height is set by whichever label is at full width (there is always one — a label
 * only shrinks while its successor is present and full), so the lane never collapses during a handoff.
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
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val chipStridePx = with(density) { (DateStripChipWidth + DateStripChipGap).toPx() }
    val edgePaddingPx = with(density) { DateStripEdgePadding.toPx() }

    // The chip index at which each month's own span begins — the label's content-space origin, mirroring
    // the LazyRow item at that same index. Kept as a plain list, not re-derived per frame.
    val monthStartChip =
        remember(labels) {
            var chip = 0
            labels.map { label -> chip.also { chip += label.dayCount } }
        }
    val monthDayCounts = remember(labels) { labels.map { it.dayCount } }

    // The strip's scroll position in pixels, folded from the list's first-visible item + its offset. A
    // `derivedStateOf` so a recomposition happens only when the folded value actually moves, not on every
    // intermediate frame the raw list state ticks through.
    val scrollXPx by
        remember(chipStridePx) {
            derivedStateOf {
                listState.firstVisibleItemIndex * chipStridePx + listState.firstVisibleItemScrollOffset
            }
        }
    val geometry = stickyMonthHeaderGeometry(monthDayCounts, chipStridePx, scrollXPx)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
            labels.forEachIndexed { index, label ->
                val text = "${monthLabels.getOrElse(label.monthValue - 1) { "" }} ${label.year}"
                when {
                    // Handed off already: scrolled past the left edge, `clipToBounds` would hide it anyway.
                    index < geometry.currentIndex -> Unit
                    index == geometry.currentIndex ->
                        MonthSpanLabel(
                            text = text,
                            modifier =
                                Modifier
                                    .padding(start = DateStripEdgePadding)
                                    .then(
                                        // Cap the width at the next month's approach; an infinite cap (no
                                        // next month) leaves the label at its natural width.
                                        if (geometry.nextMonthStartPx.isFinite()) {
                                            Modifier.widthIn(max = with(density) { geometry.nextMonthStartPx.toDp() })
                                        } else {
                                            Modifier
                                        },
                                    ),
                        )
                    else ->
                        MonthSpanLabel(
                            text = text,
                            modifier =
                                Modifier.offset {
                                    IntOffset(
                                        (edgePaddingPx + monthStartChip[index] * chipStridePx - scrollXPx).roundToInt(),
                                        0,
                                    )
                                },
                        )
                }
            }
        }
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(DateStripChipGap),
            contentPadding = PaddingValues(start = DateStripEdgePadding, end = DateStripEdgePadding, top = 4.dp),
            modifier = Modifier.fillMaxWidth(),
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
}

/** The muted month+year sub-label — same font, colour and weight as the service/duration sub-label
 * ([ConfirmedBookingRow]'s own `row.serviceName` `Text`), shared by the sticky slot and the incoming
 * labels so the two can never drift in style. */
@Composable
private fun MonthSpanLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * `26-127`: the pure geometry behind the sticky, shrinking month header — extracted from the composable so
 * the sticky/shrink/handoff can be tested with plain numbers, no Compose UI test.
 *
 * @property currentIndex index into the month list of the label that owns the sticky slot — the last month
 *   whose span has reached the left edge; `-1` when there are no months at all.
 * @property nextMonthStartPx how far the *next* month's own label still is from the left edge, in pixels;
 *   this is the width cap that shrinks the sticky label, reaching `0` exactly at the handoff.
 *   [Float.POSITIVE_INFINITY] when [currentIndex] is the last month (nothing left to shrink it).
 */
internal data class StickyMonthHeaderGeometry(
    val currentIndex: Int,
    val nextMonthStartPx: Float,
)

/**
 * Fold a horizontal scroll offset into which month owns the sticky slot and how much room its successor has
 * left it. Content-space is chip-uniform: month `m` begins at `(chips before m) * chipStridePx`. The
 * current month is the last whose start has passed under the left edge ([scrollXPx]); the next month's
 * remaining distance to that edge is its start minus the scroll, floored at `0` so the handoff frame reads
 * exactly zero rather than a tiny negative. Stateless in [scrollXPx], so scrolling back replays the same
 * handoffs in reverse.
 */
internal fun stickyMonthHeaderGeometry(
    monthDayCounts: List<Int>,
    chipStridePx: Float,
    scrollXPx: Float,
): StickyMonthHeaderGeometry {
    if (monthDayCounts.isEmpty()) {
        return StickyMonthHeaderGeometry(currentIndex = -1, nextMonthStartPx = Float.POSITIVE_INFINITY)
    }
    val scroll = scrollXPx.coerceAtLeast(0f)
    var chipsBefore = 0
    var currentIndex = 0
    val monthStartPx = FloatArray(monthDayCounts.size)
    for (i in monthDayCounts.indices) {
        monthStartPx[i] = chipsBefore * chipStridePx
        if (monthStartPx[i] <= scroll) currentIndex = i
        chipsBefore += monthDayCounts[i]
    }
    val nextMonthStartPx =
        if (currentIndex + 1 < monthDayCounts.size) {
            (monthStartPx[currentIndex + 1] - scroll).coerceAtLeast(0f)
        } else {
            Float.POSITIVE_INFINITY
        }
    return StickyMonthHeaderGeometry(currentIndex = currentIndex, nextMonthStartPx = nextMonthStartPx)
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

/**
 * `26-125` bug 2: the mockup's own `.grouphdr` renders this whole line through CSS
 * `text-transform: uppercase` (`docs/design/assets/26-112-bookings-refined-mockup.html`) — the *source*
 * string stays sentence case (`confirmedBookingsCountLabel`'s own Russian plural agreement is computed
 * on the un-uppercased word), and the transform is applied here at render time, the identical split
 * [ago.chat.android.ui.components.SectionLabel]'s own doc comment states for its own `.slabel` uppercase.
 * `Locale.forLanguageTag("ru")` rather than the no-arg locale-invariant overload
 * ([ago.chat.android.ui.components.AccountAvatarAction]'s own reasoning for avoiding a fixed locale
 * doesn't apply here — Cyrillic has no Turkish-style dotless-I ambiguity, and this app is Russian-only)
 * — the identical explicit `"ru"` tag [ago.chat.android.analytics.PhoneRevealsReportScreen]'s own
 * `OCCURRED_AT_FORMAT` and [ago.chat.android.analytics.AnalyticsDateRange]'s own `DATE_STAMP_FORMAT`
 * already pin for the same reason.
 */
@Composable
private fun WorkerGroupHeader(worker: WorkerGroup) {
    Text(
        text = "${worker.workerDisplayName} · ${confirmedBookingsCountLabel(worker.rows.size)}".uppercase(RU_LOCALE),
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
            // `26-125` bug 1: `.padding(start = 8.dp).width(RowTimeColumnWidth)` — padding OUTSIDE the
            // width, not inside it. `Modifier.width(w).padding(start = p)` (the order this shipped with)
            // makes the *outer* box exactly `w` wide and then insets the `Text`'s own measured space by
            // `p` inside that same box, so the text itself only ever got `w - p` — 32.dp for the shipped
            // 40.dp/8.dp pair, not enough for bold "10:00" at `bodySmall`, clipping to "10:0". Reordering
            // gives the padding its own space outside the box the `Text` measures into, so `RowTimeColumnWidth`
            // means what its own doc comment below already claims: the text's full width, not
            // text-plus-eaten-padding.
            modifier = Modifier.padding(start = 8.dp).width(RowTimeColumnWidth),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = RowLineGap)) {
            Text(
                text = confirmedBookingIdentityText(confirmedBookingIdentity(row)),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            // `26-125` bug 3: the shipped `Arrangement.spacedBy` + `Modifier.weight(1f)` on the service
            // `Text` alone (no `fill = false`) forced that `Text` to claim the *entire* remaining row
            // width regardless of how short "Стрижка" actually is, then left-aligned its own short string
            // inside that oversized box — so the duration landed hard against the row's trailing edge with
            // a wide empty gap before it ("Услуга␣␣длительность"), never touching it. `26-117`'s own hard
            // requirement 1 asks for one joined sub-line («Стрижка · 60 мин»), so this restores a literal
            // middot between the two — the identical `"$a · $b"` join this same file's own
            // [WorkerGroupHeader] and [ago.chat.android.ui.components.VisitorEmojiPairName] already use —
            // rather than two ends of a space-between row. `weight(1f, fill = false)` (not the shipped
            // `weight(1f)`) lets a long service name still ellipsize instead of pushing the dot/duration
            // off-row, without forcing a short one to stretch.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = RowLineGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.serviceName ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                confirmedDurationMinutesOrNull(row.startsAt, row.endsAt)?.let { minutes ->
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
 * with none of that sheet's own drag-lock: nothing here is a one-shot secret a swipe could lose.
 * `skipPartiallyExpanded = true` opens it at full height so the action buttons are visible without a
 * drag — the sheet's content is a short fixed card, not a long list, so the Material default
 * half-expanded state would just hide the actions below the fold.
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
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
        // row. `26-135`: the labels ride `bodyMedium` (matching the date line above, not the smaller
        // `labelMedium` default the pending-card call sites keep), the values are bold and pinned to the
        // row's right edge by `BookingDetailRow`'s own weighted spacer, and a thin `HorizontalDivider`
        // separates each pair, exactly as the mockup draws them.
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
        HorizontalDivider()
        // Hard requirement 9: masked value + «Показать», reusing `26-53`'s own reveal control verbatim
        // (`bookings_contacts_reveal_phone`/`_revealing_phone`) rather than a second copy of that string
        // pair for a second surface. `26-135`: the masked value + «Показать» group is right-aligned as one
        // unit (it is `BookingDetailRow`'s own trailing content), the value bold like every other row.
        BookingDetailRow(
            label = stringResource(R.string.bookings_confirmed_detail_phone_label),
            labelStyle = detailLabelStyle,
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            Text(text = booking.phone.ifBlank { "—" }, style = detailValueStyle)
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
        HorizontalDivider()
        // `26-117`: a real backend gap, disclosed rather than hidden - see this file's own top-of-file
        // doc comment for the full explanation. Both rows are always drawn, per the mockup's own hard
        // requirement 8, with the identical honest "—" `ConfirmedBookingRow` already uses for a missing
        // `serviceName` - never a fabricated confirmation state or channel name. `26-135`: the «—»
        // placeholder is bold and right-aligned like every real value, so an empty row still lines up.
        BookingDetailRow(
            label = stringResource(R.string.bookings_confirmed_detail_sms_label),
            labelStyle = detailLabelStyle,
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            Text(text = "—", style = detailValueStyle)
        }
        HorizontalDivider()
        BookingDetailRow(
            label = stringResource(R.string.bookings_confirmed_detail_source_label),
            labelStyle = detailLabelStyle,
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            // Hard requirement 10: plain text, no styled pill - a bare `Text`, the identical treatment
            // every other row's own value gets on this sheet.
            Text(text = "—", style = detailValueStyle)
        }

        // Hard requirement 11 (as revised by `26-135`): «К диалогу» (primary) and «Закрыть» (secondary)
        // sit side by side in ONE row rather than stacked full-width, each taking half the width via
        // `weight(1f)` so the pair definitely fits at ~360dp. The primary label is the shortened
        // `bookings_confirmed_open_dialog_short_action` (not the row/full-sheet `..._open_dialog_action`)
        // precisely so «Перейти к диалогу» cannot overflow its half. The primary stays disabled while
        // `originConversationId` is absent - the "Dialog link" section's own no-op/disabled rule.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onOpenDialog,
                enabled = booking.originConversationId != null,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.bookings_confirmed_open_dialog_short_action), maxLines = 1)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.bookings_confirmed_close_action), maxLines = 1)
            }
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

// `26-127`: the strip's leading/trailing inset, shared verbatim between the `LazyRow` `contentPadding` and
// the sticky label's own pinned left edge so a label sits flush over its first chip. One name, so the two
// can never drift apart.
private val DateStripEdgePadding = 16.dp

// `.rtop{gap:8px}` - the identical gap `ConversationListScreen`'s own `RtopGap` names for the same CSS
// rule, restated here rather than imported since that value is `private` to its own file.
private val RowLineGap = 8.dp

// `26-117`: the row's own leading time column - wide enough for "10:00" in `bodySmall`, bold, with no
// truncation on any locale this app renders (Russian-only today, `docs/architecture.md`).
private val RowTimeColumnWidth = 40.dp

// `26-125` bug 2: pinned rather than the device's own configured locale - this screen is Russian-only
// (`docs/architecture.md`), the identical reason `PhoneRevealsReportScreen`'s own `OCCURRED_AT_FORMAT`
// and `AnalyticsDateRange`'s own `DATE_STAMP_FORMAT` pin the same tag for their own locale-sensitive
// formatting.
private val RU_LOCALE: Locale = Locale.forLanguageTag("ru")
