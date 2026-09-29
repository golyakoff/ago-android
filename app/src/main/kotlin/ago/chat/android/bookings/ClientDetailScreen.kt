package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.core.domain.bookings.PersonBooking
import ago.chat.android.core.domain.bookings.PersonBookingStatus
import ago.chat.android.core.domain.bookings.businessLocalTimeOrNull
import ago.chat.android.core.domain.bookings.confirmedBookingsCountLabel
import ago.chat.android.ui.components.VisitorIdentityText
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
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
 *
 * `26-268` follow-up (author bug report 2026-09-29): the Предстоящие/Прошедшие segment renders its bookings
 * as a scrollable [LazyColumn] ([ClientDetailLoadedBody]), so this sheet carries the identical
 * gestures-disabled fix [ManualBookingSheet]'s own doc comment states in full — see that comment for why
 * `sheetGesturesEnabled = false` replaced an earlier, broken `confirmValueChange` attempt, and for why
 * [closeSheet] (the X button, back, a scrim tap) is what still closes this sheet, via
 * [androidx.compose.material3.SheetState.hide].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClientDetailSheet(
    contact: Contact,
    onDismiss: () -> Unit,
    onOpenDialog: (String) -> Unit,
    // `26-275`: `booking:cancel` alone - gates the Предстоящие row's own new «Отменить» action
    // (`docs/backlog/26-275-*.md` §5), the identical permission the pending queue's own cancel veto
    // already checks server-side. Defaulted to `false` so every existing call site keeps compiling
    // unchanged.
    canCancelBooking: Boolean = false,
) {
    val viewModel: ClientDetailViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Keyed on the id alone, not the whole `contact` - a reveal/confirm this same hub just performed
    // mutates `contact` field-for-field on every recomposition of the caller (`ContactsBody`'s own
    // re-derivation from `state.contacts`), and re-keying on that changed instance would restart the
    // whole read the moment its own write finished.
    LaunchedEffect(contact.customerId) { viewModel.open(contact) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val closeSheet: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = closeSheet,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
    ) {
        ClientDetailBody(
            state = state,
            onRetry = viewModel::retry,
            onReveal = viewModel::reveal,
            onConfirmPhone = viewModel::confirmPhone,
            onSegmentSelected = viewModel::onSegmentSelected,
            onOpenDialog = onOpenDialog,
            canCancelBooking = canCancelBooking,
            onCancelBooking = viewModel::cancelBooking,
            onClose = closeSheet,
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
    canCancelBooking: Boolean,
    onCancelBooking: (String) -> Unit,
    onClose: () -> Unit,
) {
    // `26-268` follow-up: one explicit close control above every state arm - unlike `ManualBookingSheet`'s
    // own title row, this sheet has no single title rendered across every arm (`Loading`/`NotConfigured`
    // draw no heading at all), so the X gets a bare header row of its own rather than riding a title that
    // does not exist in every state.
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onClose) {
                Icon(imageVector = AgoIcons.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
        ClientDetailStateBody(
            state = state,
            onRetry = onRetry,
            onReveal = onReveal,
            onConfirmPhone = onConfirmPhone,
            onSegmentSelected = onSegmentSelected,
            onOpenDialog = onOpenDialog,
            canCancelBooking = canCancelBooking,
            onCancelBooking = onCancelBooking,
        )
    }
}

@Composable
private fun ClientDetailStateBody(
    state: ClientDetailUiState,
    onRetry: () -> Unit,
    onReveal: () -> Unit,
    onConfirmPhone: () -> Unit,
    onSegmentSelected: (ClientDetailSegment) -> Unit,
    onOpenDialog: (String) -> Unit,
    canCancelBooking: Boolean,
    onCancelBooking: (String) -> Unit,
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
                canCancelBooking = canCancelBooking,
                onCancelBooking = onCancelBooking,
            )
    }
}

/**
 * `26-269`: `docs/backlog/26-269-clients-redesign.md` §4's own hub layout, in order — header (avatar,
 * name + inline warning glyph, phone + reveal, «Подтвердить телефон» banner, pills, no-show pill),
 * «Диалог» when the client has one, Предстоящие/Прошедшие segmented control, that segment's own booking
 * list, and (`26-269` polish) the SMS-confirmed/first-visit/last-visit metadata rows at the bottom.
 * «Записать» (`26-268`) is a later slice and is not drawn here, per this item's own scope.
 */
@Composable
private fun ClientDetailLoadedBody(
    state: ClientDetailUiState.Loaded,
    onReveal: () -> Unit,
    onConfirmPhone: () -> Unit,
    onSegmentSelected: (ClientDetailSegment) -> Unit,
    onOpenDialog: (String) -> Unit,
    canCancelBooking: Boolean,
    onCancelBooking: (String) -> Unit,
) {
    // `26-209`/`adr/0187`: a booking row's own reschedule - the identical `RescheduleBookingSheet` this
    // item reuses verbatim, stacked over this sheet the same way `ConfirmedBookingsBody`'s own detail sheet
    // stacks its reschedule sheet over itself.
    var reschedulingBookingId by rememberSaveable { mutableStateOf<String?>(null) }
    var reschedulingWorkerId by rememberSaveable { mutableStateOf<String?>(null) }

    // `26-269` polish (B9): which past booking's own read-only card is open - `null` whenever it is
    // closed, the identical "hold the id, derive the object" shape the reschedule pair above already
    // uses, so a reveal landing while this card is open is picked up rather than frozen at tap time.
    var selectedPastBookingId by rememberSaveable { mutableStateOf<String?>(null) }

    // `26-279` (B8): the identical "hold the id, derive the object" shape [selectedPastBookingId] already
    // uses, one segment over - which *upcoming* booking's own detail card is open, now that a tap on such
    // a row opens that card first rather than jumping straight into [RescheduleBookingSheet].
    var selectedUpcomingBookingId by rememberSaveable { mutableStateOf<String?>(null) }

    val contact = state.contact
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // `26-269` polish (B2): the header's own 48dp avatar - the identical three-way fallback
            // [ContactsScreen.kt]'s own 42dp list-row copy already draws, through the one shared
            // [ClientAvatar] composable.
            ClientAvatar(contact = contact, size = ClientDetailAvatarSize)
            Spacer(modifier = Modifier.width(ClientDetailAvatarGap))
            Column(modifier = Modifier.weight(1f)) {
                val displayName = contact.displayName
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (displayName != null) {
                        Text(text = displayName, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    } else {
                        VisitorIdentityText(
                            id = contact.customerId,
                            emojiCreature = contact.emojiCreature,
                            emojiFood = contact.emojiFood,
                            // `26-279` (A9): the identical further fallback `ContactsScreen.kt`'s own row
                            // now passes - `Contact.phone` as the title plus «Без имени», never the raw
                            // `customerId` this header used to leak through.
                            phone = contact.phone,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                    // `26-269` polish (B7): the identical warning glyph the phone line and
                    // [ConfirmPhoneBanner] already draw, restated inline beside the name itself - the
                    // mockup's own header treats an unconfirmed phone as worth this second, harder-to-miss
                    // signal *in addition to* the banner below, not instead of it.
                    if (contact.phoneNeedsAttention) {
                        Icon(
                            imageVector = AgoIcons.ErrorCircle,
                            contentDescription = stringResource(R.string.bookings_contacts_phone_unverified_description),
                            tint = agoStatusColors().warning,
                            modifier = Modifier.padding(start = 8.dp).size(20.dp),
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
                    // `26-279` (B3): «Позвонить» - `ACTION_DIAL`, never `ACTION_CALL`, so this needs no
                    // `CALL_PHONE` permission at all: the dialer opens pre-filled with `contact.phone` and
                    // the operator's own tap places the call, the identical "open the target app, don't
                    // place the call ourselves" posture `MainActivity.openInBrowser`'s own `ACTION_VIEW`
                    // already takes for a link. Reveals nothing new - it dials exactly the digits already
                    // on screen, masked or real alike.
                    TextButton(onClick = {
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + contact.phone))
                        try {
                            context.startActivity(dialIntent)
                        } catch (missing: ActivityNotFoundException) {
                            // No dialer app on this device - the number stays on screen either way, the
                            // identical posture `InviteResultBody`'s own share-intent catch already takes
                            // for a missing target app.
                        }
                    }) {
                        Text(text = stringResource(R.string.bookings_client_detail_call_action))
                    }
                }
            }
        }

        if (contact.phoneNeedsAttention) {
            ConfirmPhoneBanner(confirming = state.confirmingPhone, onConfirmPhone = onConfirmPhone)
        }

        // `26-269` polish (B5): the header's own pill row - «Постоянный клиент» exactly when
        // [ClientDetailUiState.Loaded.isReturningClient], then the plain booking-count pill every client
        // gets regardless. [NoShowPill] stays a separate row below: a no-show count is a caution, worded
        // and toned differently from these two plain status pills.
        Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isReturningClient) {
                ClientDetailPill(
                    text = stringResource(R.string.bookings_manual_returning_client_label),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            ClientDetailPill(
                text = confirmedBookingsCountLabel(state.totalBookingsCount),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            // `26-269` polish (B9), `26-279` (B8): every row is now a tap target, upcoming and past alike,
            // and both now open a booking-detail card rather than either jumping straight into
            // [RescheduleBookingSheet] (the pre-`26-279` upcoming behaviour) or opening nothing at all (the
            // pre-`26-269` past behaviour). [clientDetailCardTarget] is the one place that routing and its
            // `readOnly` value are decided: past opens [ConfirmedBookingDetailBody] `readOnly = true` (B9,
            // unchanged); upcoming opens the identical body `readOnly = false` (B8), the same body
            // `ConfirmedBookingsBody`'s own Утверждены row already opens for a live booking - «Перенести»
            // and, now, «Отменить» are reached from that card rather than the reschedule sheet directly.
            // Both draw the chevron unconditionally, signalling either destination alike.
            val isUpcomingSegment = state.selectedSegment == ClientDetailSegment.Upcoming
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                contentPadding = PaddingValues(top = 8.dp),
            ) {
                items(visible, key = { it.bookingId }) { booking ->
                    ClientBookingRow(
                        booking = booking,
                        onClick = {
                            when (clientDetailCardTarget(state.selectedSegment)) {
                                ClientDetailCardTarget.Upcoming -> selectedUpcomingBookingId = booking.bookingId
                                ClientDetailCardTarget.Past -> selectedPastBookingId = booking.bookingId
                            }
                        },
                        // `26-275`: «Отменить» only makes sense for an upcoming, still-live booking - the
                        // identical [isUpcomingSegment] flag [clientDetailCardTarget]'s own click routing
                        // above is built from, restated here for the row's own inline cancel button rather
                        // than a second, independent flag.
                        canCancel = canCancelBooking && isUpcomingSegment,
                        cancelling = booking.bookingId in state.cancellingBookingIds,
                        onCancel = { onCancelBooking(booking.bookingId) },
                    )
                    HorizontalDivider()
                }
            }
        }

        // `26-269` polish (B6): the hub's own metadata rows, drawn last and only for the facts that exist.
        ClientDetailMetadataRows(state = state)
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
                // The booking moved - close the reschedule sheet AND the upcoming detail card it was
                // opened from (`26-279` B8: the identical `ConfirmedBookingsBody`'s own
                // reschedule-from-detail flow this hub's own doc comment above used to contrast itself
                // with - a moved booking's own card would otherwise sit open showing a time that is no
                // longer the one the operator is looking at). The hub itself (this whole sheet) stays open
                // either way - here the operator's own next likely action is still on this same client.
                reschedulingBookingId = null
                reschedulingWorkerId = null
                selectedUpcomingBookingId = null
            },
        )
    }

    // `26-269` polish (B9): the past-row read-only card - re-derived from `state.past` by id on every
    // recomposition, the identical "hold the id, derive the object" shape `ConfirmedBookingsBody`'s own
    // `selectedBooking` already uses, so a reveal that lands while this card is open is reflected in place.
    // «Показать» reuses this same hub's single [onReveal]/[ClientDetailUiState.Loaded.revealing] - see
    // [asConfirmedBooking]'s own doc comment for why the *contact's* phone, not the booking row's own
    // snapshot, is what this card renders.
    val selectedPastBooking = state.past.firstOrNull { it.bookingId == selectedPastBookingId }
    if (selectedPastBooking != null) {
        ConfirmedBookingDetailSheet(
            booking = selectedPastBooking.asConfirmedBooking(contact),
            revealing = state.revealing,
            onReveal = onReveal,
            onOpenDialog = { selectedPastBooking.originConversationId?.let(onOpenDialog) },
            onReschedule = {},
            onDismiss = { selectedPastBookingId = null },
            readOnly = ClientDetailCardTarget.Past.readOnly,
        )
    }

    // `26-279` (B8): the identical read-derived card [selectedPastBooking] above already draws, one
    // segment over - an *upcoming* booking's own detail card, `readOnly = false` so [ConfirmedBookingDetailBody]
    // draws both «Перенести» (stacks [RescheduleBookingSheet] over this card, unchanged from the flow this
    // row used to open directly) and, now, «Отменить» ([onCancel] below - gated on [canCancelBooking], the
    // identical permission [ClientBookingRow]'s own inline cancel button already checks). A successful
    // cancel removes this booking from `state.upcoming` ([ClientDetailViewModel.cancelBooking]), which
    // makes [selectedUpcomingBooking] resolve to `null` on the very next recomposition and closes this
    // card on its own - no separate "cancel succeeded, now dismiss" wiring needed, the identical
    // self-closing behaviour a swipe-erased row already gets elsewhere in this app.
    val selectedUpcomingBooking = state.upcoming.firstOrNull { it.bookingId == selectedUpcomingBookingId }
    if (selectedUpcomingBooking != null) {
        ConfirmedBookingDetailSheet(
            booking = selectedUpcomingBooking.asConfirmedBooking(contact),
            revealing = state.revealing,
            onReveal = onReveal,
            onOpenDialog = { selectedUpcomingBooking.originConversationId?.let(onOpenDialog) },
            onReschedule = {
                reschedulingBookingId = selectedUpcomingBooking.bookingId
                reschedulingWorkerId = selectedUpcomingBooking.workerId
            },
            onCancel =
                if (canCancelBooking) {
                    { onCancelBooking(selectedUpcomingBooking.bookingId) }
                } else {
                    null
                },
            cancelling = selectedUpcomingBooking.bookingId in state.cancellingBookingIds,
            onDismiss = { selectedUpcomingBookingId = null },
            readOnly = ClientDetailCardTarget.Upcoming.readOnly,
        )
    }
}

/** `26-269` polish (B5): the header's own plain status pill - the identical `Surface`/`RoundedCornerShape`/
 * `labelSmall` recipe [NoShowPill] already uses, restated with a caller-supplied colour pair since these
 * two pills (a positive "returning" fact, a neutral count) are not the warning tone that pill is pinned
 * to. */
@Composable
private fun ClientDetailPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(color = containerColor, contentColor = contentColor, shape = RoundedCornerShape(5.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/**
 * `26-269` polish (B6): the hub's own metadata rows, drawn last and only for the facts that exist -
 * «Подтверждён по SMS» exactly when [Contact.phoneVerifiedAt] is set (this app's own SMS-code
 * verification of the *number*; reuses [R.string.bookings_confirmed_detail_sms_label] verbatim, the
 * identical label Утверждены's own detail sheet always renders "—" beside, since the calendar records no
 * SMS *booking*-confirmation fact at all — a different, genuinely-answerable fact here), and «Первый
 * визит»/«Последний визит» exactly when [ClientDetailUiState.Loaded.firstVisitLocalDate]/
 * [ClientDetailUiState.Loaded.lastVisitLocalDate] resolve to a real date - a client with no completed
 * visit yet, or one whose date fails to parse, gets neither row rather than a blank or fabricated one.
 * Drawn through [BookingDetailRow] — the identical label-then-value shape
 * [ConfirmedBookingDetailBody]'s own Услуга/Мастер/Телефон rows already use — so a third such row here
 * never invents a fourth visual language for the same fact shape.
 */
@Composable
private fun ClientDetailMetadataRows(state: ClientDetailUiState.Loaded) {
    val smsConfirmedAt = state.contact.phoneVerifiedAt?.let { businessLocalShortDateOrNull(it) ?: "—" }
    val firstVisit = state.firstVisitLocalDate?.let(::businessLocalFullDateOrNull)
    val lastVisit = state.lastVisitLocalDate?.let(::businessLocalFullDateOrNull)
    if (smsConfirmedAt == null && firstVisit == null && lastVisit == null) return

    val labelStyle = MaterialTheme.typography.bodyMedium
    val valueStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)

    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        if (smsConfirmedAt != null) {
            HorizontalDivider()
            BookingDetailRow(
                label = stringResource(R.string.bookings_confirmed_detail_sms_label),
                labelStyle = labelStyle,
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                Text(text = stringResource(R.string.bookings_client_detail_sms_confirmed_value, smsConfirmedAt), style = valueStyle)
            }
        }
        if (firstVisit != null) {
            HorizontalDivider()
            BookingDetailRow(
                label = stringResource(R.string.bookings_client_detail_first_visit_label),
                labelStyle = labelStyle,
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                Text(text = firstVisit, style = valueStyle)
            }
        }
        if (lastVisit != null) {
            HorizontalDivider()
            BookingDetailRow(
                label = stringResource(R.string.bookings_client_detail_last_visit_label),
                labelStyle = labelStyle,
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                Text(text = lastVisit, style = valueStyle)
            }
        }
    }
}

// `.av{width:48px; height:48px}` - the client-detail header's own avatar size, distinct from
// [ContactsScreen.kt]'s own 42dp list-row copy (the mockup draws the two at different sizes).
private val ClientDetailAvatarSize = 48.dp

// `.row{gap:13px}` - restated here for the header's own avatar/name gap, the identical value
// [ContactsScreen.kt]'s own `ContactRowAvatarGap` carries for its own row (two independent screens, each
// naming its own gap beside its own citation, the same restraint that constant's own doc comment states).
private val ClientDetailAvatarGap = 13.dp

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
            // `26-268` follow-up (author feedback 2026-09-29): the circled Material Symbols outlined
            // `error` glyph [AgoIcons.ErrorCircle] already draws for exactly this "needs attention" case
            // — see that icon's own doc comment — not the bare-stem-and-dot [AgoIcons.Exclamation], so
            // this banner's own icon reads the same shape as the identical warning on the Клиенты list row
            // ([ContactCard]'s own phone line in `ContactsScreen.kt`).
            Icon(
                imageVector = AgoIcons.ErrorCircle,
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
 *
 * `26-269` polish (B9): every row is now a tap target and draws the trailing chevron unconditionally —
 * [ClientDetailLoadedBody]'s own [onClick] routes an upcoming tap to the reschedule sheet and a past one
 * to the new read-only booking-detail card, so this row itself no longer needs to know which segment it
 * is in (the earlier `clickable` parameter this composable took, gating both the modifier and the chevron
 * on "is this Предстоящие", is gone along with that distinction — both destinations are equally "this row
 * opens something").
 *
 * `26-275`/`adr/0189`: [canCancel] draws a «Отменить» [TextButton] beside the chevron — the guard-plus-
 * navigate affordance `docs/backlog/26-275-*.md` §5 asks for: an operator blocked from deleting a client
 * by a future booking lands here to clear it. The button consumes its own tap before the row's outer
 * `.clickable(onClick = onClick)` ever sees it — the identical nested-click-target behaviour
 * `ContactCard`'s own reveal `TextButton` already relies on (that composable's own doc comment) — so
 * tapping «Отменить» never also opens the sheet underneath it. [cancelling] disables the button and
 * relabels it while the write is in flight, the identical `enabled = !revealing` shape [ContactCard]'s
 * own reveal control already uses for the identical reason.
 */
@Composable
private fun ClientBookingRow(
    booking: PersonBooking,
    onClick: () -> Unit,
    canCancel: Boolean = false,
    cancelling: Boolean = false,
    onCancel: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
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
        if (canCancel) {
            TextButton(onClick = onCancel, enabled = !cancelling) {
                Text(
                    text =
                        stringResource(
                            if (cancelling) {
                                R.string.bookings_client_detail_cancelling_booking
                            } else {
                                R.string.bookings_client_detail_cancel_booking_action
                            },
                        ),
                    color = if (cancelling) LocalContentColor.current else agoStatusColors().dangerText,
                )
            }
        }
        Icon(
            imageVector = AgoIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** `26-269` polish (B6): "2 окт" - [Contact.phoneVerifiedAt]'s own short form for the metadata row's
 * value - day plus the abbreviated Russian month Java's own locale data already supplies for the `"MMM"`
 * pattern, no year (the mockup's own value is a recency date, not a historical one worth a year). Reads
 * the offset already embedded in the ISO string, the identical "no zone conversion of any kind" idiom
 * [businessLocalTimeOrNull]'s own doc comment states, restated here for a date rather than a clock time.
 * `null` on a malformed value, never a fabricated date. */
private fun businessLocalShortDateOrNull(iso: String): String? =
    runCatching { OffsetDateTime.parse(iso).toLocalDate().format(SMS_CONFIRMED_SHORT_DATE_FORMAT) }.getOrNull()

private val SMS_CONFIRMED_SHORT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))
