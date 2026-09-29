package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.BookingIdentity
import ago.chat.android.core.domain.bookings.ConfirmedBooking
import ago.chat.android.core.domain.bookings.ConfirmedBookingsStripDay
import ago.chat.android.core.domain.bookings.DayGroup
import ago.chat.android.core.domain.bookings.WorkerGroup
import ago.chat.android.core.domain.bookings.businessLocalTimeOrNull
import ago.chat.android.core.domain.bookings.confirmedBookingIdentity
import ago.chat.android.core.domain.bookings.confirmedBookingsCountLabel
import ago.chat.android.core.domain.bookings.confirmedBookingsMonthLabels
import ago.chat.android.ui.components.formatRuPhoneForDisplay
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
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
 * **The two rows hard requirement 8 asks for, one now real and one still an honest gap (`26-121`).**
 * The «Источник» row is real as of `26-121`: `Ago.Calendar.Contracts.ConfirmedBookingResponse` now carries
 * `OriginConversationId`, wired through [ConfirmedBooking.originConversationId], and
 * [ConfirmedBookingDetailBody] renders «Из чата» when a booking arrived through a chat conversation, "—"
 * otherwise. That is the only source signal the calendar can honestly offer — it treats the chat origin
 * opaquely (`adr/0184`/`adr/0065`) and never learns the channel within chat (widget vs Telegram vs Max),
 * so there is no richer breakdown to bind. The «Подтверждён по SMS» row stays "—": `26-121` verified the
 * calendar records no customer-SMS-confirmation of a booking anywhere (confirmation is the operator veto
 * window or the auto-sweep; `20-05`'s SMS is outbound, not an inbound confirmation), so there is nothing
 * on the wire to fill it — making that row real is a new SMS-confirm flow, an author decision, not an
 * additive field. Both rows are still drawn, per the mockup, with the identical honest "—"
 * [ConfirmedBookingRow] already uses for a genuinely missing [ConfirmedBooking.serviceName].
 */
@Composable
internal fun ConfirmedBookingsBody(
    state: ConfirmedBookingsUiState,
    onSelectDay: (String) -> Unit,
    onJumpToDate: (String) -> Unit,
    onRetry: () -> Unit,
    onReveal: (String) -> Unit,
    onOpenDialog: (String) -> Unit,
    onPullToLoadWeek: (DateStripEdgeLoad) -> Unit,
    onJumpToToday: () -> Unit,
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

            // `26-209`/`adr/0187`: which booking «Перенести оператором» opened for, and the same worker
            // the target slot must belong to — `null` (both, together) whenever the reschedule sheet is
            // closed. The identical "which sheet is open is UI, not network" local-state split
            // `selectedBookingId` above already draws, one level deeper: this sheet stacks *over* the
            // detail sheet rather than replacing it.
            var reschedulingBookingId by rememberSaveable { mutableStateOf<String?>(null) }
            var reschedulingWorkerId by rememberSaveable { mutableStateOf<String?>(null) }

            Column(modifier = Modifier.fillMaxSize()) {
                state.actionError?.let { error -> ActionErrorBanner(error = error, modifier = Modifier.fillMaxWidth()) }
                ConfirmedDateStrip(
                    strip = state.strip,
                    selectedDate = state.selectedDate,
                    edgeLoading = state.edgeLoading,
                    onSelectDay = onSelectDay,
                    onJumpToDate = onJumpToDate,
                    onPullToLoadWeek = onPullToLoadWeek,
                    onJumpToToday = onJumpToToday,
                )
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
                    onReschedule = {
                        reschedulingBookingId = selectedBooking.bookingId
                        reschedulingWorkerId = selectedBooking.workerId
                    },
                    onDismiss = { selectedBookingId = null },
                )
            }

            // `26-209`/`adr/0187`: stacked over the detail sheet above, never in place of it - both ids
            // are set (or cleared) together, so this branch is exactly "the reschedule sheet is open".
            val reschedulingBooking = reschedulingBookingId
            val reschedulingWorker = reschedulingWorkerId
            if (reschedulingBooking != null && reschedulingWorker != null) {
                RescheduleBookingSheet(
                    bookingId = reschedulingBooking,
                    workerId = reschedulingWorker,
                    onDismiss = {
                        reschedulingBookingId = null
                        reschedulingWorkerId = null
                    },
                    onRescheduled = {
                        // The booking moved - both sheets close (the row the operator opened no longer
                        // shows the time they just left), and `onRetry` re-reads the confirmed range so
                        // the list reflects the new time on the very next frame.
                        reschedulingBookingId = null
                        reschedulingWorkerId = null
                        selectedBookingId = null
                        onRetry()
                    },
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
 *
 * `26-212`: that same sticky header [Box] is now the tap target for a full [DatePickerDialog] jump — the
 * fixed seven-day window `defaultConfirmedBookingsRange` used to hard-cap this strip at is now a *movable*
 * one, [onJumpToDate] being what moves it (wired to [ConfirmedBookingsViewModel.onDatePicked]). The
 * `LazyRow` day chips below stay exactly as `26-51`/`26-117`/`26-127` left them — a picked date lands here
 * as an ordinary [ConfirmedBookingsUiState.Loaded] carrying a new [strip] anchored on that day, the
 * identical shape a plain retry already produces, so this composable itself needs no branch for "did the
 * strip move because of a pick or a retry".
 *
 * `26-233`: the month/year jump above is for a *far* move; this item adds the near-term one, entirely
 * inside the `LazyRow` lane below rather than through a second dialog. Reaching either end of the strip
 * and continuing to drag past it now rubber-bands the whole lane (a diminishing-returns stretch,
 * [rubberBandPull]) and, once the drag has gone far enough, calls [onPullToLoadWeek] for the corresponding
 * [DateStripEdgeLoad] — [ConfirmedBookingsViewModel.onPullToLoadWeek]'s own doc comment has the full
 * "why a week, why re-anchored, why not a full-screen reload" reasoning. A [NestedScrollConnection] is the
 * chosen idiom, not a second `pointerInput` drag detector layered over the `LazyRow`: `LazyRow`'s own
 * `scrollable()` already participates in Compose's nested-scroll system and forwards exactly the
 * *unconsumed* leftover of a drag once it has hit either end — the one piece of information "how far past
 * the edge is this drag" needs, and the one thing a sibling pointer-input gesture detector has no
 * reliable way to recover once the inner `LazyRow` has already consumed the touch stream. This is the
 * identical mechanism Material3's own vertical `PullToRefreshBox`/`pullToRefresh` is built on; Compose
 * ships no horizontal equivalent, which is why this is hand-rolled rather than a library call.
 * [DateStripEdgeIndicator] draws the pulled side's own directional chevron (the ticket's own suggested
 * "elastic arrow in the pull direction") and doubles as a plain tap target for the identical load, so the
 * feature stays reachable without the drag gesture at all — for TalkBack, and for anyone who would rather
 * tap than pull; while [edgeLoading] names a side, that side's chevron is replaced by a spinner instead
 * (hard requirement 2 of `26-233`'s own brief: "a spinner spins inside the days area on the pulled side").
 * [onJumpToToday] is a plain jump back to today from anywhere, sharing none of the above; `26-233` sat its
 * «Сегодня» control beside the header, and `26-254` moved that control *inside* the
 * [ConfirmedDateStripPickerDialog] this header opens — the callback is threaded through unchanged, only
 * its trigger relocated.
 */
@Composable
private fun ConfirmedDateStrip(
    strip: List<ConfirmedBookingsStripDay>,
    selectedDate: String,
    edgeLoading: DateStripEdgeLoad?,
    onSelectDay: (String) -> Unit,
    onJumpToDate: (String) -> Unit,
    onPullToLoadWeek: (DateStripEdgeLoad) -> Unit,
    onJumpToToday: () -> Unit,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
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

    // `26-233` hard requirement 3: "snap to the new week's first day". `strip`'s own identity only
    // changes when a fresh fetch actually replaced it — a plain [onSelectDay] tap never touches this list,
    // only `selectedDate` — so keying on it here fires exactly at "new data arrived" (an edge-pulled week,
    // a date-picker jump, or a today jump), never on every recomposition. Index `0` is always the new
    // window's own first day, the same `selectedDate == range.from` invariant every load path already
    // keeps.
    LaunchedEffect(strip) {
        listState.scrollToItem(0)
    }

    val pullScope = rememberCoroutineScope()
    // Raw, effectively-unbounded finger-drag distance past whichever edge is being pulled — positive at
    // the start (Previous), negative at the end (Next). Deliberately *not* the same value the visible
    // stretch is drawn from: [rubberBandPull] derives a diminishing-returns visual from it, so the raw
    // distance can keep growing (bounded only to stop an accidental runaway) while the drawn stretch
    // itself asymptotically caps at [DateStripPullVisualMax].
    val rawPull = remember { Animatable(0f) }
    val visualMaxPx = with(density) { DateStripPullVisualMax.toPx() }
    val triggerPx = with(density) { DateStripPullTriggerDistance.toPx() }
    val rawBoundPx = with(density) { DateStripPullRawBound.toPx() }

    val overscrollConnection =
        remember(edgeLoading, onPullToLoadWeek) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // No new pull while a week from the last one is still in flight - the identical
                    // one-in-flight-at-a-time guard `onPullToLoadWeek`'s own doc comment states for why it
                    // is a no-op in that state.
                    if (edgeLoading != null || source != NestedScrollSource.UserInput || available.x == 0f) return Offset.Zero
                    val pullsPrevious = available.x > 0f && !listState.canScrollBackward
                    val pullsNext = available.x < 0f && !listState.canScrollForward
                    if (!pullsPrevious && !pullsNext) return Offset.Zero
                    pullScope.launch {
                        rawPull.snapTo((rawPull.value + available.x).coerceIn(-rawBoundPx, rawBoundPx))
                    }
                    // Consumed in full: the drag's own leftover becomes this gesture's pull rather than a
                    // system-drawn edge glow fighting it for the same touch.
                    return Offset(available.x, 0f)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    val pulled = rawPull.value
                    when {
                        pulled >= triggerPx -> onPullToLoadWeek(DateStripEdgeLoad.Previous)
                        pulled <= -triggerPx -> onPullToLoadWeek(DateStripEdgeLoad.Next)
                    }
                    rawPull.animateTo(0f, animationSpec = spring())
                    // Never claims the fling itself - only the drag that preceded it was this gesture's.
                    return Velocity.Zero
                }
            }
        }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // `26-212`: the whole sticky-header lane is the tap target, not just the current label's own
        // text — the label that owns the slot changes under a moving finger as the strip scrolls (that
        // is the whole point of `StickyMonthHeaderGeometry`), so pinning the click to one specific
        // label's own composable would move the tap target out from under an operator mid-scroll. The
        // header always shows *some* month/year, so "tap the header" reads the same regardless of
        // which one. `26-254`: the header spans the full width again — the «Сегодня» quick-jump that
        // `26-233` sat beside it has moved *inside* the date-picker dialog this header opens (a
        // near-target for a far-jump control), so nothing shares this row now.
        val jumpToDateLabel = stringResource(R.string.bookings_confirmed_jump_to_date_action)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(CONFIRMED_STRIP_HEADER_TEST_TAG)
                    .clipToBounds()
                    .clickable(onClickLabel = jumpToDateLabel) { showDatePicker = true },
        ) {
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
        Box(modifier = Modifier.fillMaxWidth().nestedScroll(overscrollConnection)) {
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(DateStripChipGap),
                contentPadding = PaddingValues(start = DateStripEdgePadding, end = DateStripEdgePadding, top = 4.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(rubberBandPull(rawPull.value, visualMaxPx).roundToInt(), 0) },
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
            DateStripEdgeIndicator(
                alignment = Alignment.CenterStart,
                pull = rubberBandPull(rawPull.value, visualMaxPx).coerceAtLeast(0f),
                maxPull = visualMaxPx,
                loading = edgeLoading == DateStripEdgeLoad.Previous,
                icon = AgoIcons.Back,
                contentDescription = stringResource(R.string.bookings_confirmed_load_previous_week_action),
                loadingDescription = stringResource(R.string.bookings_confirmed_loading_week),
                onClick = { onPullToLoadWeek(DateStripEdgeLoad.Previous) },
            )
            DateStripEdgeIndicator(
                alignment = Alignment.CenterEnd,
                pull = (-rubberBandPull(rawPull.value, visualMaxPx)).coerceAtLeast(0f),
                maxPull = visualMaxPx,
                loading = edgeLoading == DateStripEdgeLoad.Next,
                icon = AgoIcons.ChevronRight,
                contentDescription = stringResource(R.string.bookings_confirmed_load_next_week_action),
                loadingDescription = stringResource(R.string.bookings_confirmed_loading_week),
                onClick = { onPullToLoadWeek(DateStripEdgeLoad.Next) },
            )
        }
    }

    if (showDatePicker) {
        ConfirmedDateStripPickerDialog(
            initialDate = selectedDate,
            onDismiss = { showDatePicker = false },
            onPicked = { date ->
                showDatePicker = false
                onJumpToDate(date)
            },
            onJumpToToday = {
                showDatePicker = false
                onJumpToToday()
            },
        )
    }
}

/**
 * `26-212`: the month/year header's own tap target — a native Material3 [DatePickerDialog], the closest
 * Compose-native fit to "tap a month/year label, get a calendar" (a third-party picker library is ruled
 * out by this ticket's own scope; `DatePicker`/`rememberDatePickerState` already ship with the
 * `material3` dependency every other screen here uses). [initialDate] opens the calendar on the day the
 * strip is already showing, so the picker starts where the operator's eyes already are rather than on
 * today.
 *
 * Restates the identical UTC-midnight epoch-millis round trip
 * [ago.chat.android.bookings.SingleDatePickerField]'s own doc comment (`WorkerScheduleScreen.kt`) already
 * restates from [ago.chat.android.analytics.AnalyticsDateRangeControl] — a *third* copy of the same
 * four-line conversion, which that doc comment's own reasoning calls overdue for a shared function once a
 * third caller shows up. Left as a restatement here rather than fixed, since extracting it would touch two
 * files outside this ticket's own scope (`docs/backlog/26-212-*.md` is this screen and its view model) for
 * no behaviour change — flagged, not fixed, the same "report don't fix" call this ticket's own brief asks
 * for any out-of-lane finding.
 *
 * `26-254`: this dialog also carries the «Сегодня» quick-jump [onJumpToToday] that `26-233` had sat in the
 * day-strip header — a near-target for a far-jump control, so a jump-to-today and a jump-to-a-picked-date
 * live in one place. Material3's `DatePickerDialog` ships no built-in today shortcut, so it is added as a
 * plain leading `TextButton` in the [confirmButton] slot's own row alongside OK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmedDateStripPickerDialog(
    initialDate: String,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
    onJumpToToday: () -> Unit,
) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = epochMillisAtUtcMidnight(initialDate))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            // `26-254`: «Сегодня» rides the dialog's own button row alongside OK, no longer the day
            // strip's header. Material3's `DatePickerDialog` ships no built-in today shortcut, so it is
            // added here as a plain leading action in the `confirmButton` slot (the slot Material lays out
            // as a right-aligned row, so «Сегодня» sits just left of OK). It fires the identical
            // today-jump the header button used to — `onJumpToToday` is threaded through unchanged, the
            // trigger just relocated — and closes the dialog, since a today jump is itself a picked date.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onJumpToToday) {
                    Text(text = stringResource(R.string.bookings_confirmed_today_action))
                }
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { onPicked(localDateAtUtcMidnight(it)) }
                        onDismiss()
                    },
                ) {
                    Text(text = stringResource(R.string.analytics_dialog_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.analytics_dialog_cancel))
            }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

/** [DatePicker] speaks in UTC-midnight epoch millis regardless of the device's own zone — its own
 * documented contract, the identical pair [ago.chat.android.bookings.SingleDatePickerField]'s own doc
 * comment already restates a copy of (see [ConfirmedDateStripPickerDialog]'s own doc comment on why this
 * is a third restatement, not a shared import). */
private fun epochMillisAtUtcMidnight(isoLocalDate: String): Long? =
    runCatching {
        LocalDate
            .parse(isoLocalDate)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

private fun localDateAtUtcMidnight(epochMillis: Long): String =
    Instant
        .ofEpochMilli(epochMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .toString()

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
 * `26-233`: [rawPull]'s own diminishing-returns visual — `0` at `rawPull == 0`, approaching but never
 * reaching [max] as [rawPull] grows, sign preserved. The classic rubber-band constant formula
 * (`f(x) = max·|x| / (|x| + max)`, the same shape iOS's own `UIScrollView` overscroll uses): easy to reason
 * about (a pure function of two numbers, no easing curve to tune) and it naturally saturates near [max]
 * without a hard clamp of its own, which is what lets [DateStripPullTriggerDistance] sit *past* [max] in
 * raw terms — the visual stretch is already most of the way to its cap by the time the gesture actually
 * triggers a week load, the same "resistance builds, then it gives" feel a real rubber band has.
 */
private fun rubberBandPull(
    rawPull: Float,
    max: Float,
): Float {
    if (max <= 0f) return 0f
    val magnitude = max * (abs(rawPull) / (abs(rawPull) + max))
    return if (rawPull >= 0f) magnitude else -magnitude
}

/**
 * `26-233`: one edge of the day strip's own rubber-band affordance — a directional chevron that fades in
 * as [pull] grows toward [maxPull], swapped for a spinner while [loading] (hard requirement 2: "a spinner
 * spins inside the days area on the pulled side"). Drawn as a `BoxScope` extension so it can
 * [androidx.compose.foundation.layout.BoxScope.align] itself to the caller's own edge inside the `Box`
 * that also holds the `LazyRow` — the identical positioning shape [ConfirmedDateStrip]'s own sticky-header
 * labels already use for a fixed offset, applied here through `align` instead since this indicator sits at
 * a corner rather than a scroll-derived pixel position.
 *
 * Also a real tap target throughout, never only a gesture hint — [onClick] fires the identical
 * [ago.chat.android.bookings.ConfirmedBookingsViewModel.onPullToLoadWeek] call a completed pull would, so
 * the whole feature stays reachable with a plain tap: for TalkBack (which cannot perform a rubber-band
 * drag), and for anyone who would simply rather tap than pull. Disabled while [loading] — the identical
 * one-in-flight-at-a-time guard the view model itself already enforces, stated again here so a double-tap
 * cannot even attempt a second request while the first is still in flight.
 */
@Composable
private fun BoxScope.DateStripEdgeIndicator(
    alignment: Alignment,
    pull: Float,
    maxPull: Float,
    loading: Boolean,
    icon: ImageVector,
    contentDescription: String,
    loadingDescription: String,
    onClick: () -> Unit,
) {
    if (!loading && pull <= 0f) return
    val progress = if (maxPull > 0f) (pull / maxPull).coerceIn(0f, 1f) else 0f
    Box(
        modifier =
            Modifier
                .align(alignment)
                .size(width = DateStripChipWidth, height = DateStripEdgeIndicatorHeight)
                .clickable(onClickLabel = contentDescription, enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(DateStripEdgeSpinnerSize).semantics { this.contentDescription = loadingDescription },
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                // The function returns above while `pull <= 0f`, so this branch only ever draws mid-pull -
                // opacity ramps from barely-visible at the first pixel of pull to fully opaque as `pull`
                // approaches `maxPull`, rather than popping in at full strength the instant the drag starts.
                modifier = Modifier.alpha(progress.coerceAtLeast(DATE_STRIP_EDGE_INDICATOR_MIN_ALPHA)),
            )
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
                    // only ever a real tap while a real id is present - `26-121` puts a real
                    // `originConversationId` on the wire for a chat-origin booking, so the tap is now live
                    // for those rows and stays a no-op for a booking with no chat origin.
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
 *    appear on this screen as an identity (`BookingIdentity`'s own doc comment, `:core:domain`).
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
                text = bookingIdentityText(confirmedBookingIdentity(row)),
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
                durationMinutesOrNull(row.startsAt, row.endsAt)?.let { minutes ->
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

/** [BookingIdentity]'s own three arms, rendered — shared verbatim between the row
 * ([ConfirmedBookingRow]) and the detail sheet's own header ([ConfirmedBookingDetailBody]) so the two
 * surfaces can never word the identical fallback differently. `internal`, not `private`: `26-163`'s own
 * pending row and sheet ([PendingBookingsScreen.kt][PendingBookingRow]) render the identical three arms,
 * and a fourth surface wording the fallback on its own is exactly the drift this function exists to
 * rule out. */
@Composable
internal fun bookingIdentityText(identity: BookingIdentity): String =
    when (identity) {
        is BookingIdentity.Name -> identity.displayName
        is BookingIdentity.MaskedPhone -> identity.phone
        BookingIdentity.NoName -> stringResource(R.string.bookings_confirmed_identity_no_name)
    }

/**
 * `26-117` hard requirements 6-11: the row-tap booking detail — a bottom sheet over the list, the
 * identical [ModalBottomSheet] shape [ago.chat.android.team.InviteColleagueSheet] already establishes,
 * with none of that sheet's own drag-lock: nothing here is a one-shot secret a swipe could lose.
 * `skipPartiallyExpanded = true` opens it at full height so the action buttons are visible without a
 * drag — the sheet's content is a short fixed card, not a long list, so the Material default
 * half-expanded state would just hide the actions below the fold.
 *
 * `26-269` polish (B9), `26-279` (B8): `internal`, not `private` — the client-detail hub's own
 * booking-detail cards (`ClientDetailScreen.kt`), past (read-only) and, since `26-279`, upcoming
 * (reschedule/cancel) alike, reuse this exact sheet for a [PersonBooking][ago.chat.android.core.domain.bookings.PersonBooking]
 * mapped onto [ConfirmedBooking] ([ago.chat.android.bookings.asConfirmedBooking]) rather than a second,
 * drifting copy of the same Услуга/Мастер/Телефон/Источник rows. [readOnly] is the one switch that reuse
 * needs — see its own doc comment below; [onCancel] is `26-279`'s own addition for the upcoming card only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfirmedBookingDetailSheet(
    booking: ConfirmedBooking,
    revealing: Boolean,
    onReveal: () -> Unit,
    onOpenDialog: () -> Unit,
    onReschedule: () -> Unit,
    onDismiss: () -> Unit,
    readOnly: Boolean = false,
    // `26-279` (B8): `null` (the default) draws no «Отменить» at all - `ConfirmedBookingsBody`'s own
    // Утверждены call site passes none and keeps compiling and behaving unchanged. Non-`null` only from
    // the client-detail hub's own upcoming card, and only once `booking:cancel` is granted - see
    // [ConfirmedBookingDetailBody]'s own doc comment on this parameter for the "hide, don't disable" gate.
    onCancel: (() -> Unit)? = null,
    cancelling: Boolean = false,
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
            onReschedule = onReschedule,
            onDismiss = onDismiss,
            readOnly = readOnly,
            onCancel = onCancel,
            cancelling = cancelling,
        )
    }
}

@Composable
internal fun ConfirmedBookingDetailBody(
    booking: ConfirmedBooking,
    revealing: Boolean,
    onReveal: () -> Unit,
    onOpenDialog: () -> Unit,
    onReschedule: () -> Unit,
    onDismiss: () -> Unit,
    // `26-269` polish (B9): `true` for a past booking opened read-only from the client-detail hub — hides
    // «Перенести» below (a visit that already happened has nothing left to move) while every other row
    // this body draws stays exactly as-is. Defaulted `false` so `ConfirmedBookingsBody`'s own call site —
    // a tap on an Утверждены row, always reschedulable — keeps compiling and behaving unchanged.
    readOnly: Boolean = false,
    // `26-279` (B8): the client-detail hub's own upcoming card passes a real callback exactly when the
    // operator holds `booking:cancel` — the identical "hide the affordance entirely, never merely disable
    // it" rule `ContactsBody`'s own swipe-to-delete gesture already states for a missing permission,
    // restated here for a button instead of a gesture. `null` draws no «Отменить» row at all: every other
    // call site (`ConfirmedBookingsBody`'s own Утверждены sheet, and this body's own `readOnly = true`
    // past card) passes none.
    onCancel: (() -> Unit)? = null,
    // Which text/enabled state «Отменить» shows while [onCancel]'s own write is on the network - the
    // identical `cancelling` flag [ClientBookingRow]'s own inline cancel button already threads through,
    // restated here so the two "cancel this same booking" affordances never word an in-flight write
    // differently.
    cancelling: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        // Hard requirement 7: the header is the name (the identical fallback the list row uses - this
        // sheet must not show the hex id either).
        Text(
            text = bookingIdentityText(confirmedBookingIdentity(booking)),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
        // Hard requirement 7: date LEFT, time RIGHT, no dot/separator between the two containers - two
        // `Text`s at the opposite ends of one `Row`, not one interpolated sentence.
        BookingDetailDateTimeLine(
            localDate = booking.localDate,
            weekday = booking.weekday,
            startsAt = booking.startsAt,
            endsAt = booking.endsAt,
        )

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
            // `26-307`: a masked value (`booking.masked`) never has the full 10 digits, so
            // [formatRuPhoneForDisplay] passes it through unchanged - only a real, complete number is
            // reformatted.
            Text(text = formatRuPhoneForDisplay(booking.phone).ifBlank { "—" }, style = detailValueStyle)
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
        // «Подтверждён по SMS» stays honestly "—": `26-121` verified the calendar records no
        // customer-SMS-confirmation of a booking at all (confirmation is the operator veto window or the
        // auto-sweep, and `20-05`'s SMS is an outbound notice, not an inbound confirmation), so there is
        // nothing on the wire to bind here - `ConfirmedBookingResponse` carries no such field. Making this
        // row real is a new SMS-confirm flow, an author decision, not an additive field - see `26-121`'s
        // own report. The row is still drawn (mockup hard requirement 8), with the identical bold,
        // right-aligned "—" `26-135` gave every empty value.
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
            // `26-121`: this row is now real. `ConfirmedBookingResponse.OriginConversationId` (wired
            // through [ConfirmedBooking.originConversationId]) tells us whether the booking arrived through
            // a chat conversation - the only source signal the calendar can honestly offer, since it treats
            // the chat origin opaquely (`adr/0184`/`adr/0065`) and never learns the channel within chat.
            // Present -> «Из чата»; absent -> the identical honest "—" every other genuinely-missing value
            // uses, never a fabricated channel name. Hard requirement 10: plain text, no styled pill.
            Text(
                text =
                    if (booking.originConversationId != null) {
                        stringResource(R.string.bookings_confirmed_detail_source_chat)
                    } else {
                        "—"
                    },
                style = detailValueStyle,
            )
        }

        // `26-209`/`adr/0187`: «Перенести оператором» - a full-width secondary action of its own row,
        // above the dialog/close pair rather than sharing their row, since a three-way split at ~360dp
        // would leave every label cramped. Never disabled: every confirmed booking has a worker
        // (`ConfirmedBooking.workerId` is non-nullable), so there is no "cannot reschedule this one" state
        // for this button to reflect - unlike the dialog action's own `originConversationId`-gated enable.
        //
        // `26-269` polish (B9): absent entirely, not merely disabled, when [readOnly] - the past-booking
        // card's own "hide, don't grey" rule for an action that makes no sense at all for a visit that
        // already happened, the identical posture [ContactsBody]'s own swipe-to-delete gesture already
        // takes for a missing permission (`SwipeableContactRow`'s own doc comment).
        //
        // `26-279` (B8): «Отменить» sits beside «Перенести» in the same row, each taking half the width,
        // exactly when [onCancel] is non-`null` (the client-detail hub's own upcoming card, permission
        // already checked by its caller) - `ConfirmedBookingsBody`'s own Утверждены sheet passes `null` and
        // keeps drawing the single full-width reschedule button unchanged.
        if (!readOnly) {
            val cancel = onCancel
            if (cancel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onReschedule, modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.bookings_confirmed_reschedule_action), maxLines = 1)
                    }
                    OutlinedButton(onClick = cancel, enabled = !cancelling, modifier = Modifier.weight(1f)) {
                        Text(
                            text =
                                stringResource(
                                    if (cancelling) {
                                        R.string.bookings_client_detail_cancelling_booking
                                    } else {
                                        R.string.bookings_client_detail_cancel_booking_action
                                    },
                                ),
                            maxLines = 1,
                            color = if (cancelling) LocalContentColor.current else agoStatusColors().dangerText,
                        )
                    }
                }
            } else {
                OutlinedButton(onClick = onReschedule, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    Text(text = stringResource(R.string.bookings_confirmed_reschedule_action), maxLines = 1)
                }
            }
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

/**
 * Hard requirement 7: the detail sheet's own date/time line - date LEFT, time RIGHT, no dot/separator
 * between the two containers; two `Text`s at the opposite ends of one `Row`, not one interpolated
 * sentence. `internal`, not `private`: `26-163`'s own pending sheet ([PendingBookingDetailBody]) draws the
 * identical line over [ago.chat.android.core.domain.bookings.PendingBooking]'s own fields, which is why
 * this takes the four bare values rather than a [ConfirmedBooking]. [weekday] is `null` when the caller
 * could not derive one (the pending response carries none, and
 * [ago.chat.android.core.domain.bookings.businessLocalWeekdayOrNull] returns `null` for a malformed date) -
 * the date then renders without its weekday rather than with a wrong one.
 */
@Composable
internal fun BookingDetailDateTimeLine(
    localDate: String,
    weekday: Int?,
    startsAt: String,
    endsAt: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = bookingDetailDateText(localDate, weekday),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = bookingDetailTimeRangeText(startsAt, endsAt),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        )
    }
}

/** Hard requirement 7's own "the date carries the year" - weekday (full name) + day + genitive month +
 * year, e.g. «Вторник, 29 сентября 2026». Falls back to the weekday alone if [localDate] fails to parse -
 * the identical "never invented, rendered honestly" posture [ago.chat.android.thread.ThreadScreen]'s own
 * `clockTimeOrNull` already takes for a malformed timestamp, restated here for a malformed date. A `null`
 * [weekday] drops the weekday and its comma, never substitutes a guessed one. */
@Composable
private fun bookingDetailDateText(
    localDate: String,
    weekday: Int?,
): String {
    val weekdayLabels = stringArrayResource(R.array.bookings_weekday_full)
    val monthGenitiveLabels = stringArrayResource(R.array.bookings_month_genitive)
    val weekdayLabel = weekday?.let { weekdayLabels.getOrElse(it) { "" } }.orEmpty()
    val date = runCatching { LocalDate.parse(localDate) }.getOrNull() ?: return weekdayLabel
    val month = monthGenitiveLabels.getOrElse(date.monthValue - 1) { "" }
    val dateText = "${date.dayOfMonth} $month ${date.year}"
    return if (weekdayLabel.isEmpty()) dateText else "$weekdayLabel, $dateText"
}

/** Hard requirement 7's own time container - business-local start–end, the identical
 * [businessLocalTimeOrNull] the row itself already uses for [ConfirmedBooking.startsAt], applied to both
 * bounds. An em dash on either side that fails to parse, never a blank container. */
private fun bookingDetailTimeRangeText(
    startsAt: String,
    endsAt: String,
): String {
    val start = businessLocalTimeOrNull(startsAt) ?: "—"
    val end = businessLocalTimeOrNull(endsAt) ?: "—"
    return "$start–$end"
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

// `26-233`: the rubber-band edge-pull's own metrics. `DateStripPullVisualMax` is the stretch's own asymptotic
// cap (`rubberBandPull`'s own doc comment) - how far the strip and the edge chevron ever visibly move.
// `DateStripPullTriggerDistance` is a *raw* finger-drag distance, deliberately larger than the visual cap: by
// the time a real drag has gone this far past the edge the visual stretch already reads as "fully pulled",
// so the trigger lands on the same "resistance, then it gives" beat a real elastic band has, rather than
// firing the instant the visual indicator looks maxed out. `DateStripPullRawBound` only stops the raw
// accumulator (never itself drawn) from growing without limit while a finger keeps dragging well past the
// trigger distance.
private val DateStripPullVisualMax = 28.dp
private val DateStripPullTriggerDistance = 72.dp
private val DateStripPullRawBound = 240.dp

// `26-233`: the edge indicator's own footprint - `DateStripChipWidth` wide (the same lane a day chip
// occupies, so the chevron/spinner sits where the stretched-away chip would have been) and tall enough to
// clear the chip's own weekday/number/dot column without needing an intrinsic-height measurement pass
// (`LazyRow` does not support one). `DateStripEdgeSpinnerSize` is Material's own small-spinner size.
private val DateStripEdgeIndicatorHeight = 64.dp
private val DateStripEdgeSpinnerSize = 20.dp

// `26-233`: the chevron's own opacity floor mid-pull, before `progress` has climbed far from zero - just
// enough that the very first pixel of a pull already shows *something*, rather than the icon popping in
// abruptly once `progress` clears some invisible threshold.
private const val DATE_STRIP_EDGE_INDICATOR_MIN_ALPHA = 0.25f

// `26-254`: the sticky month/year header's own test tag — the tap target that opens the date-picker
// dialog (which now also carries the «Сегодня» quick-jump). The header's click is labelled, not
// content-described, and its month/year text is dynamic, so a stable tag is the only non-brittle handle a
// Compose UI test has to open the picker. `internal`, mirroring `MENU_SCRIM_TEST_TAG`'s own file-scope
// const, so the bookings androidTest can reference it without a second copy of the literal.
internal const val CONFIRMED_STRIP_HEADER_TEST_TAG: String = "confirmedStripHeader"

// `.rtop{gap:8px}` - the identical gap `ConversationListScreen`'s own `RtopGap` names for the same CSS
// rule, restated here rather than imported since that value is `private` to its own file. `internal`:
// `26-163`'s own pending row (`PendingBookingsScreen.kt`) shares both metrics so the two lists' rows line
// up to the pixel when an operator flips between the segments.
internal val RowLineGap = 8.dp

// `26-117`: the row's own leading time column - wide enough for "10:00" in `bodySmall`, bold, with no
// truncation on any locale this app renders (Russian-only today, `docs/architecture.md`).
internal val RowTimeColumnWidth = 40.dp

// `26-125` bug 2: pinned rather than the device's own configured locale - this screen is Russian-only
// (`docs/architecture.md`), the identical reason `PhoneRevealsReportScreen`'s own `OCCURRED_AT_FORMAT`
// and `AnalyticsDateRange`'s own `DATE_STAMP_FORMAT` pin the same tag for their own locale-sensitive
// formatting.
private val RU_LOCALE: Locale = Locale.forLanguageTag("ru")
