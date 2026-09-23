package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmationCountdown
import ago.chat.android.core.domain.bookings.PendingBooking
import ago.chat.android.core.domain.bookings.confirmationCountdown
import ago.chat.android.ui.components.IdentifierText
import ago.chat.android.ui.components.rememberTickingNow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-48`: Записи, for real — replaces `ago.chat.android.shell.BookingsPlaceholderScreen` at
 * `AppShellScreen.kt`'s own `NavHost`. Obtains its own [BookingsViewModel] via [hiltViewModel] — the
 * identical wiring [ago.chat.android.conversations.ConversationListRoute] already establishes for
 * Диалоги.
 */
@Composable
public fun BookingsRoute(
    showConfirmedSegment: Boolean,
    showClientsSegment: Boolean,
    viewModel: BookingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(BookingsTab.Pending) }

    // `26-51`: [ConfirmedBookingsViewModel] is obtained by `hiltViewModel()` only inside this branch, so
    // an operator lacking `customer:read` never constructs it and never triggers its `init`-time read
    // (that class's own doc comment). Calling a `@Composable` conditionally like this is safe here
    // specifically because `hiltViewModel()` is keyed by the requesting class against the surrounding
    // `NavBackStackEntry`'s own `ViewModelStore`, not by the call's position in the composition, so
    // toggling this branch never recreates the view model underneath it.
    val confirmedState: ConfirmedBookingsUiState?
    val onSelectDay: (String) -> Unit
    val onRetryConfirmed: () -> Unit
    if (showConfirmedSegment) {
        val confirmedViewModel: ConfirmedBookingsViewModel = hiltViewModel()
        val collectedConfirmedState by confirmedViewModel.state.collectAsStateWithLifecycle()
        confirmedState = collectedConfirmedState
        onSelectDay = confirmedViewModel::onDaySelected
        onRetryConfirmed = confirmedViewModel::refresh
    } else {
        confirmedState = null
        onSelectDay = {}
        onRetryConfirmed = {}
    }

    // `26-52`: the identical Hilt-avoidance-when-ungated shape [confirmedState] above already
    // establishes, applied to [ContactsViewModel] for the same reason - an operator lacking both
    // `calendar:configure` and `customer:read` never triggers this read either.
    val contactsState: ContactsUiState?
    val onRetryContacts: () -> Unit
    if (showClientsSegment) {
        val contactsViewModel: ContactsViewModel = hiltViewModel()
        val collectedContactsState by contactsViewModel.state.collectAsStateWithLifecycle()
        contactsState = collectedContactsState
        onRetryContacts = contactsViewModel::refresh
    } else {
        contactsState = null
        onRetryContacts = {}
    }

    BookingsScreen(
        state = state,
        showConfirmedSegment = showConfirmedSegment,
        showClientsSegment = showClientsSegment,
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it },
        onRetry = viewModel::refresh,
        confirmedState = confirmedState,
        onSelectDay = onSelectDay,
        onRetryConfirmed = onRetryConfirmed,
        contactsState = contactsState,
        onRetryContacts = onRetryContacts,
    )
}

/**
 * The stateless half — see [BookingsRoute]'s own doc comment for why [BookingsRoute] exists at all
 * ([AppShellScreen]'s own `bookingsTab` slot needs a Hilt-free substitute for the back-button-contract
 * tests, the identical reason that file's own `conversationsTab`/`settingsScreen` slots exist).
 *
 * **The segmented control is built from [showConfirmedSegment]/[showClientsSegment], never drawn with a
 * fixed shape.** `26-48` shipped this with exactly one hard-coded [SegmentedButton]; `26-51` replaced
 * that with [visibleBookingsTabs] for Утверждены — the same "compute the list, don't draw a fixed
 * shape" correction `visibleBottomDestinations` already models one level up; `26-52` lands the mockup's
 * third segment, Клиенты, as a third entry in that same function, computed from its own independent
 * gate rather than a third hand-written [SegmentedButton] here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookingsScreen(
    state: BookingsUiState,
    showConfirmedSegment: Boolean,
    showClientsSegment: Boolean,
    selectedTab: BookingsTab,
    onTabSelected: (BookingsTab) -> Unit,
    onRetry: () -> Unit,
    confirmedState: ConfirmedBookingsUiState?,
    onSelectDay: (String) -> Unit,
    onRetryConfirmed: () -> Unit,
    contactsState: ContactsUiState?,
    onRetryContacts: () -> Unit,
) {
    // `ago-console`'s own `useNow` hook, restated - the one clock read this screen makes, so every
    // deadline countdown on it re-renders together rather than each row reading `OffsetDateTime.now()`
    // on its own recomposition schedule (`ConversationListScreen`'s own identical reasoning for
    // `rememberTickingNow`).
    val now = rememberTickingNow()
    val tabs = visibleBookingsTabs(showConfirmedSegment, showClientsSegment)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(topBar = { TopAppBar(title = { Text(text = stringResource(R.string.nav_bookings)) }) }) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    tabs.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = selectedTab == tab,
                            onClick = { onTabSelected(tab) },
                            shape = SegmentedButtonDefaults.itemShape(index, tabs.size),
                            label = { Text(text = bookingsTabLabel(tab = tab, pendingState = state)) },
                            icon = {},
                        )
                    }
                }

                when (selectedTab) {
                    BookingsTab.Pending ->
                        when (state) {
                            BookingsUiState.Loading -> LoadingBody()
                            is BookingsUiState.Loaded ->
                                if (state.bookings.isEmpty()) {
                                    EmptyBody(stringResource(R.string.bookings_queue_empty))
                                } else {
                                    PendingBookingsList(bookings = state.bookings, now = now)
                                }

                            BookingsUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
                            is BookingsUiState.Failed -> RefusalBody(reason = state.reason, onRetry = onRetry)
                        }

                    // `confirmedState` is non-null exactly when `showConfirmedSegment` is true - the only
                    // condition under which this tab even appears in `tabs` for `onTabSelected` to have
                    // been able to select it in the first place.
                    BookingsTab.Confirmed ->
                        confirmedState?.let {
                            ConfirmedBookingsBody(state = it, onSelectDay = onSelectDay, onRetry = onRetryConfirmed)
                        }

                    // `26-52`: the identical "non-null exactly when selectable" invariant
                    // `BookingsTab.Confirmed`'s own comment above states, for `showClientsSegment`.
                    BookingsTab.Clients ->
                        contactsState?.let { ContactsBody(state = it, onRetry = onRetryContacts) }
                }
            }
        }
    }
}

@Composable
private fun bookingsTabLabel(
    tab: BookingsTab,
    pendingState: BookingsUiState,
): AnnotatedString =
    when (tab) {
        BookingsTab.Pending -> pendingSegmentLabel(countFor(pendingState))
        BookingsTab.Confirmed -> buildAnnotatedString { append(stringResource(R.string.bookings_tab_confirmed)) }
        BookingsTab.Clients -> buildAnnotatedString { append(stringResource(R.string.bookings_tab_clients)) }
    }

/** `null` before [BookingsUiState.Loaded] is known, exactly the "no digit for a count not yet known"
 * rule [ago.chat.android.conversations.segmentedCountFor] already states for Диалоги's own two tabs. */
private fun countFor(state: BookingsUiState): Int? = (state as? BookingsUiState.Loaded)?.bookings?.size

@Composable
private fun pendingSegmentLabel(count: Int?): AnnotatedString =
    buildAnnotatedString {
        append(stringResource(R.string.bookings_tab_pending))
        if (count != null) {
            append(" ")
            withStyle(
                MaterialTheme.typography.labelMedium
                    .copy(fontWeight = FontWeight.Bold)
                    .toSpanStyle(),
            ) {
                append(count.toString())
            }
        }
    }

/** `internal`, not `private`: [ConfirmedBookingsScreen.kt][ConfirmedBookingsBody] reuses this and the
 * three composables below it for the identical loading/empty/refusal states, rather than a second copy
 * of each. */
@Composable
internal fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun EmptyBody(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** `docs/backlog/26-48-*.md`'s own Done-when: "renders as a refusal with a retry, and never as a raw
 * exception class name" — [failureMessage] is the one place this screen turns
 * [ago.chat.android.core.domain.bookings.BookingsQueueFailure] into the Russian sentence, never the
 * adapter that produced it (`KtorBookingsApi`'s own doc comment: classification lives there, wording
 * lives here).
 *
 * [unexpectedMessageRes] is a parameter, not hard-coded, because `26-51`'s own Утверждены segment reads
 * a different endpoint than the pending queue and needs its own wording for
 * [BookingsQueueFailure.Unexpected] (`bookings_load_failed_unexpected`'s own Russian text names "очередь
 * записей" specifically) — [BookingsQueueFailure.Transport]'s own wording is shared as-is, since it is
 * already worded generically about reaching AGO Calendar at all, not about either read's own shape.
 */
@Composable
internal fun RefusalBody(
    reason: BookingsQueueFailure,
    onRetry: () -> Unit,
    unexpectedMessageRes: Int = R.string.bookings_load_failed_unexpected,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = failureMessage(reason, unexpectedMessageRes),
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
private fun failureMessage(
    reason: BookingsQueueFailure,
    unexpectedMessageRes: Int,
): String =
    when (reason) {
        BookingsQueueFailure.Transport -> stringResource(R.string.bookings_load_failed_transport)
        BookingsQueueFailure.Unexpected -> stringResource(unexpectedMessageRes)
    }

@Composable
private fun PendingBookingsList(
    bookings: List<PendingBooking>,
    now: OffsetDateTime,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(bookings, key = { it.bookingId }) { booking ->
            PendingBookingCard(booking = booking, now = now)
            HorizontalDivider()
        }
    }
}

/**
 * The mockup's own card — service and duration, when and with whom, the deadline, and the calendar's
 * short id (`docs/backlog/26-48-*.md`'s own Scope item 4). Every one of `serviceId`/`workerId`/
 * `calendarId` is an id, rendered through [IdentifierText] — never a name, because
 * `PendingBookingResponse` does not carry one yet (`PendingBooking`'s own doc comment).
 */
@Composable
private fun PendingBookingCard(
    booking: PendingBooking,
    now: OffsetDateTime,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // "Service and duration" (`docs/backlog/26-48-*.md`'s own Scope item 4) - one row.
        BookingDetailRow(label = stringResource(R.string.bookings_card_service_label)) {
            IdentifierText(id = booking.serviceId, style = MaterialTheme.typography.bodyMedium)
            durationMinutesOrNull(booking.startsAt, booking.endsAt)?.let { minutes ->
                Text(
                    text = stringResource(R.string.bookings_duration_minutes, minutes.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        // "When and with whom" - two rows rather than one shared line, so each has its own label; the
        // mockup groups them visually, but nothing in this item's Scope requires one Compose `Row`
        // over two, and two labelled rows read at least as clearly on a phone-width card.
        clockTimeOrNull(booking.startsAt)?.let { time ->
            BookingDetailRow(label = stringResource(R.string.bookings_card_when_label), modifier = Modifier.padding(top = 4.dp)) {
                Text(text = time, style = MaterialTheme.typography.bodyMedium)
            }
        }
        BookingDetailRow(label = stringResource(R.string.bookings_card_worker_label), modifier = Modifier.padding(top = 4.dp)) {
            IdentifierText(id = booking.workerId, style = MaterialTheme.typography.bodyMedium)
        }
        // The deadline - a full sentence on its own, so it carries no separate label.
        Text(
            text = confirmationCountdownText(booking.confirmationDeadline, now),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        BookingDetailRow(
            label = stringResource(R.string.bookings_card_calendar_label),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            IdentifierText(id = booking.calendarId, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** `SettingsScreen`'s own `AboutLine` shape, restated: a label, then whatever the row actually needs to
 * show beside it - here a slot rather than a single value, since some rows carry two pieces (a time and
 * a worker id; a service id and its duration). */
@Composable
private fun BookingDetailRow(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        content()
    }
}

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

/** `null` for anything that fails to parse - the same "never invented, rendered honestly" posture
 * `ThreadScreen`'s own `clockTimeOrNull` already takes for a malformed timestamp. Rendered in the
 * device's own zone - the operator reading this screen, not the calendar's own business time zone. */
private fun clockTimeOrNull(startsAt: String): String? =
    runCatching {
        OffsetDateTime.parse(startsAt).atZoneSameInstant(ZoneId.systemDefault()).format(CLOCK_FORMAT)
    }.getOrNull()

private fun durationMinutesOrNull(
    startsAt: String,
    endsAt: String,
): Long? {
    val start = runCatching { OffsetDateTime.parse(startsAt) }.getOrNull() ?: return null
    val end = runCatching { OffsetDateTime.parse(endsAt) }.getOrNull() ?: return null
    return Duration.between(start, end).toMinutes().takeIf { it >= 0 }
}

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
