package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.core.domain.bookings.PersonBooking
import ago.chat.android.core.domain.bookings.PersonBookingStatus
import ago.chat.android.core.domain.bookings.businessLocalTimeOrNull
import ago.chat.android.ui.components.VisitorIdentityText
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `26-269`: the client-detail hub — a [ModalBottomSheet] opened *over* Клиенты
 * (`docs/backlog/26-269-clients-redesign.md` §4), the identical `skipPartiallyExpanded = true` shape
 * [ConfirmedBookingDetailSheet]/[RescheduleBookingSheet] already establish for a sheet whose own content
 * is a short, fixed-height card rather than a long scrolling list (the Material default half-expanded
 * state would just hide the action row below the fold). [ClientDetailViewModel] is obtained here, inside
 * this composable, the identical `hiltViewModel()`-only-while-open discipline every sibling sheet on this
 * screen already states — closing this sheet (a plain "changed my mind", not a write) discards the view
 * model along with it, and reopening the same client re-reads fresh rather than resuming stale state.
 *
 * [onOpenDialog] is passed straight through with no handling here — the identical
 * [ago.chat.android.shell.PendingConversationOpener] call [ConfirmedBookingsBody] already makes, reused
 * rather than a second navigation mechanism grown just for this hub.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClientDetailSheet(
    contact: Contact,
    onDismiss: () -> Unit,
    onOpenDialog: (String) -> Unit,
) {
    val viewModel: ClientDetailViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Keyed on the id alone, not the whole `contact` - a reveal/confirm this same hub just performed
    // mutates `contact` field-for-field on every recomposition of the caller (`ContactsBody`'s own
    // re-derivation from `state.contacts`), and re-keying on that changed instance would restart the
    // whole read the moment its own write finished.
    LaunchedEffect(contact.customerId) { viewModel.open(contact) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ClientDetailBody(
            state = state,
            onRetry = viewModel::retry,
            onReveal = viewModel::reveal,
            onConfirmPhone = viewModel::confirmPhone,
            onSegmentSelected = viewModel::onSegmentSelected,
            onOpenDialog = onOpenDialog,
        )
    }
}

@Composable
private fun ClientDetailBody(
    state: ClientDetailUiState,
    onRetry: () -> Unit,
    onReveal: () -> Unit,
    onConfirmPhone: () -> Unit,
    onSegmentSelected: (ClientDetailSegment) -> Unit,
    onOpenDialog: (String) -> Unit,
) {
    when (state) {
        ClientDetailUiState.Loading ->
            Row(modifier = Modifier.fillMaxWidth().padding(48.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }

        ClientDetailUiState.NotConfigured ->
            Text(
                text = stringResource(R.string.bookings_not_configured),
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

        is ClientDetailUiState.Failed ->
            RefusalBody(
                reason = state.reason,
                onRetry = onRetry,
                unexpectedMessageRes = R.string.bookings_client_detail_load_failed_unexpected,
            )

        is ClientDetailUiState.Loaded ->
            ClientDetailLoadedBody(
                state = state,
                onReveal = onReveal,
                onConfirmPhone = onConfirmPhone,
                onSegmentSelected = onSegmentSelected,
                onOpenDialog = onOpenDialog,
            )
    }
}

/**
 * `26-269`: `docs/backlog/26-269-clients-redesign.md` §4's own hub layout, in order — header (name, phone
 * + reveal, warning glyph + «Подтвердить телефон», no-show pill), «Диалог» when the client has one,
 * Предстоящие/Прошедшие segmented control, then that segment's own booking list. «Записать» (`26-268`) is
 * a later slice and is not drawn here, per this item's own scope.
 */
@Composable
private fun ClientDetailLoadedBody(
    state: ClientDetailUiState.Loaded,
    onReveal: () -> Unit,
    onConfirmPhone: () -> Unit,
    onSegmentSelected: (ClientDetailSegment) -> Unit,
    onOpenDialog: (String) -> Unit,
) {
    // `26-209`/`adr/0187`: a booking row's own reschedule - the identical `RescheduleBookingSheet` this
    // item reuses verbatim, stacked over this sheet the same way `ConfirmedBookingsBody`'s own detail sheet
    // stacks its reschedule sheet over itself.
    var reschedulingBookingId by rememberSaveable { mutableStateOf<String?>(null) }
    var reschedulingWorkerId by rememberSaveable { mutableStateOf<String?>(null) }

    val contact = state.contact

    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        val displayName = contact.displayName
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (displayName != null) {
                Text(text = displayName, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            } else {
                VisitorIdentityText(
                    id = contact.customerId,
                    emojiCreature = contact.emojiCreature,
                    emojiFood = contact.emojiFood,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = contact.phone,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (contact.masked) {
                TextButton(onClick = onReveal, enabled = !state.revealing) {
                    Text(
                        text =
                            stringResource(
                                if (state.revealing) {
                                    R.string.bookings_contacts_revealing_phone
                                } else {
                                    R.string.bookings_contacts_reveal_phone
                                },
                            ),
                    )
                }
            }
        }

        if (contact.phoneNeedsAttention) {
            ConfirmPhoneBanner(confirming = state.confirmingPhone, onConfirmPhone = onConfirmPhone)
        }
        if (contact.noShowCount > 0) {
            Row(modifier = Modifier.padding(top = 12.dp)) { NoShowPill(count = contact.noShowCount) }
        }

        state.actionError?.let { error -> ActionErrorBanner(error = error, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) }

        // `docs/backlog/26-269-*.md` §4: "hidden, not greyed, when the person has no conversation at all" -
        // the identical rule the confirmed-bookings screen's own dialog icon already applies to a null
        // `originConversationId`, restated here for a whole button rather than an `enabled` flag.
        if (state.hasDialog) {
            OutlinedButton(
                onClick = { state.dialogConversationId?.let(onOpenDialog) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Icon(imageVector = AgoIcons.Chat, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(text = stringResource(R.string.bookings_client_detail_open_dialog_action))
            }
        }

        BookingSegmentedControl(
            selected = state.selectedSegment,
            upcomingCount = state.upcoming.size,
            pastCount = state.past.size,
            onSelected = onSegmentSelected,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        )

        val visible = state.visibleBookings
        if (visible.isEmpty()) {
            val emptyRes =
                if (state.selectedSegment == ClientDetailSegment.Upcoming) {
                    R.string.bookings_client_detail_upcoming_empty
                } else {
                    R.string.bookings_client_detail_past_empty
                }
            Text(
                text = stringResource(emptyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )
        } else {
            // `docs/backlog/26-269-*.md` §2 case 1/2: only an *upcoming* booking is a reschedule target -
            // a visit that already happened has nothing left to move, and the design's own mockup draws
            // the tap affordance (the `#i-chev` chevron) on the Предстоящие row alone.
            val canReschedule = state.selectedSegment == ClientDetailSegment.Upcoming
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                contentPadding = PaddingValues(top = 8.dp),
            ) {
                items(visible, key = { it.bookingId }) { booking ->
                    ClientBookingRow(
                        booking = booking,
                        clickable = canReschedule,
                        onClick = {
                            reschedulingBookingId = booking.bookingId
                            reschedulingWorkerId = booking.workerId
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

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
                // The booking moved - close only the reschedule sheet, and re-read this client's own
                // bookings so the hub reflects the new time on the next frame; the hub itself stays open,
                // unlike `ConfirmedBookingsBody`'s own reschedule-from-detail flow, which closes both -
                // here the operator's own next likely action is still on this same client.
                reschedulingBookingId = null
                reschedulingWorkerId = null
            },
        )
    }
}

@Composable
private fun ConfirmPhoneBanner(
    confirming: Boolean,
    onConfirmPhone: () -> Unit,
) {
    Surface(
        color = agoStatusColors().warningTint,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = AgoIcons.Exclamation,
                contentDescription = stringResource(R.string.bookings_contacts_phone_unverified_description),
                tint = agoStatusColors().warning,
                modifier = Modifier.padding(end = 8.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.bookings_client_detail_confirm_phone_banner),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onConfirmPhone, enabled = !confirming, modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text =
                            stringResource(
                                if (confirming) {
                                    R.string.bookings_client_detail_confirming_phone
                                } else {
                                    R.string.bookings_client_detail_confirm_phone_action
                                },
                            ),
                    )
                }
            }
        }
    }
}

/** `docs/backlog/26-269-*.md` §3.5: Предстоящие/Прошедшие, each carrying its own count — the identical
 * `SegmentedButton`/`SingleChoiceSegmentedButtonRow` shape the top-level Записи tab bar already uses
 * ([BookingsScreen]'s own segment row), restated here for two entries rather than three. */
@Composable
private fun BookingSegmentedControl(
    selected: ClientDetailSegment,
    upcomingCount: Int,
    pastCount: Int,
    onSelected: (ClientDetailSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        SegmentedButton(
            selected = selected == ClientDetailSegment.Upcoming,
            onClick = { onSelected(ClientDetailSegment.Upcoming) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) {
            Text(text = "${stringResource(R.string.bookings_client_detail_upcoming_segment)} $upcomingCount")
        }
        SegmentedButton(
            selected = selected == ClientDetailSegment.Past,
            onClick = { onSelected(ClientDetailSegment.Past) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) {
            Text(text = "${stringResource(R.string.bookings_client_detail_past_segment)} $pastCount")
        }
    }
}

/**
 * One row of the visible segment — time leads (the identical leading-time-column reasoning
 * [ConfirmedBookingRow]'s own doc comment states for its own appointment row), then service name and a
 * "date · master · duration" sub-line, with a «Неявка» badge for [PersonBookingStatus.NoShow] rows (the
 * design mockup's own examples: a no-show is marked on the row itself, not folded into the date line).
 * [clickable] gates whether a tap opens the reschedule flow — see [ClientDetailLoadedBody]'s own doc
 * comment on why only Предстоящие rows are reschedule targets.
 */
@Composable
private fun ClientBookingRow(
    booking: PersonBooking,
    clickable: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = businessLocalTimeOrNull(booking.startsAt) ?: "—",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.width(52.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = booking.serviceName ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (booking.status == PersonBookingStatus.NoShow) {
                    Spacer(modifier = Modifier.width(8.dp))
                    NoShowBadge()
                }
            }
            Text(
                text = clientBookingSubtitle(booking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (clickable) {
            Icon(
                imageVector = AgoIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "5 октября 2026 · Ирина Соколова · 60 мин" - the mockup's own row sub-line, three plain pieces joined
 * by " · " the identical way [WorkerGroupHeader]/`VisitorEmojiPairName` already join their own two, rather
 * than one localized format string: the middot is punctuation, not a phrase, so it needs no resource of
 * its own. A piece that fails to render (an unparsable date, no duration) is simply omitted along with its
 * own leading separator, never a stray " · " left dangling. */
@Composable
private fun clientBookingSubtitle(booking: PersonBooking): String {
    val pieces = mutableListOf<String>()
    businessLocalFullDateOrNull(booking.localDate)?.let(pieces::add)
    pieces.add(booking.workerDisplayName)
    durationMinutesOrNull(booking.startsAt, booking.endsAt)?.let { minutes ->
        pieces.add(stringResource(R.string.bookings_duration_minutes, minutes.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()))
    }
    return pieces.joinToString(" · ")
}

/** The mockup's own plain «Неявка» badge - the identical warning-toned [NoShowPill] treatment, restated
 * for a bare label rather than a count: this badge answers "was *this* visit a no-show", a different
 * question from [NoShowPill]'s own "how many times has this client no-shown in total". */
@Composable
private fun NoShowBadge() {
    Surface(
        color = agoStatusColors().warningTint,
        contentColor = agoStatusColors().warning,
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(
            text = stringResource(R.string.bookings_client_detail_status_no_show),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** "5 октября 2026" - a hard-coded Russian locale, the identical `RU_LOCALE`/`Locale.forLanguageTag("ru")`
 * precedent [ConfirmedBookingsScreen.kt][WorkerGroupHeader] and half a dozen other call sites across this
 * app already establish: this app is Russian-only, so there is no device-locale branch to honour here
 * either. `null` on a malformed [localDate] - the identical "never invented, rendered honestly" posture
 * [businessLocalTimeOrNull] already takes for its own input. */
private fun businessLocalFullDateOrNull(localDate: String): String? =
    runCatching { LocalDate.parse(localDate).format(CLIENT_BOOKING_DATE_FORMAT) }.getOrNull()

private val CLIENT_BOOKING_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))
