package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.core.domain.bookings.PersonBooking
import ago.chat.android.core.domain.bookings.PersonBookingStatus
import ago.chat.android.core.domain.bookings.PhoneCandidate
import ago.chat.android.core.domain.bookings.businessLocalTimeOrNull
import ago.chat.android.core.domain.bookings.confirmedBookingsCountLabel
import ago.chat.android.core.domain.visitorEmojiPair
import ago.chat.android.ui.components.VisitorIdentityText
import ago.chat.android.ui.components.formatRuPhoneForDisplay
import ago.chat.android.ui.components.russianPluralStringResource
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.AgoChatTheme
import ago.chat.android.ui.theme.agoStatusColors
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.tooling.preview.Preview
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
    // `26-310`: reports this hub's own, freshly-loaded `upcoming.size` back to whichever screen opened
    // it - [ContactsBody] threads this straight to [ContactsViewModel.applyUpcomingCount] so the Клиенты
    // row's own « · N запись» badge (and the «С предстоящей записью»/«Без записей» filter it feeds) never
    // drifts from what this sheet - the only place a booking gets cancelled from - just showed. Defaulted
    // to a no-op so every existing call site keeps compiling unchanged.
    onUpcomingCountChanged: (customerId: String, upcomingCount: Int) -> Unit = { _, _ -> },
) {
    val viewModel: ClientDetailViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Keyed on the id alone, not the whole `contact` - a reveal/confirm this same hub just performed
    // mutates `contact` field-for-field on every recomposition of the caller (`ContactsBody`'s own
    // re-derivation from `state.contacts`), and re-keying on that changed instance would restart the
    // whole read the moment its own write finished.
    LaunchedEffect(contact.customerId) { viewModel.open(contact) }

    // `26-310`: fires on every [ClientDetailUiState.Loaded] this hub ever reaches - the initial load, a
    // successful cancel ([ClientDetailViewModel.cancelBooking] already drops the row from `upcoming`
    // before this recomposes), and a manual booking added from inside this same sheet (`viewModel.retry()`
    // after `onCreated`) alike. Keyed on `state` itself, not `contact.customerId`: a `data class` equality
    // check on the whole state means this effect only re-runs when the *count itself* (or anything else in
    // [ClientDetailUiState.Loaded]) actually changed, never once per unrelated recomposition, and
    // [ContactsViewModel.applyUpcomingCount]'s own no-op-when-unchanged guard means an initial load that
    // merely confirms the list's existing count writes nothing back either.
    LaunchedEffect(state) {
        (state as? ClientDetailUiState.Loaded)?.let { loaded ->
            onUpcomingCountChanged(loaded.contact.customerId, loaded.upcoming.size)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val closeSheet: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }

    // `26-283`/`26-284` (item 8): «+ Записать» stacks the manual-booking wizard over this hub - the
    // identical "hold the id/flag, derive the rest" pattern [ClientDetailLoadedBody]'s own
    // `reschedulingBookingId`/`selectedPastBookingId` already establish for a sheet stacked over this same
    // one, restated here for a plain boolean since there is only ever one such wizard open at a time.
    var showManualBookingSheet by rememberSaveable { mutableStateOf(false) }

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
            onOpenManualBooking = { showManualBookingSheet = true },
            onClose = closeSheet,
        )
    }

    val loaded = state as? ClientDetailUiState.Loaded
    if (showManualBookingSheet && loaded != null) {
        ManualBookingSheet(
            prefillClient = manualBookingPrefillCandidate(loaded),
            onDismiss = { showManualBookingSheet = false },
            // `26-311`: this hub has no day-strip of its own to jump - only [BookingsScreen]'s own
            // Утверждены segment does ([ManualBookingSheet]'s own doc comment) - so the new booking's own
            // `localDate` is simply unused here, the identical "the caller uses only what it needs" shape
            // every other multi-field write result on this port already follows.
            onCreated = { _ ->
                showManualBookingSheet = false
                // `26-283`: the new booking just landed on the server - re-read so the hub's own
                // Предстоящие segment picks it up, the identical "the caller re-reads, the write result
                // carries no fresh reading back" discipline every other write on this port already
                // follows ([ManualBookingUiState.Created]'s own doc comment states it for this exact
                // write). `retry()` re-opens for the same contact [viewModel.open] is already keyed on.
                viewModel.retry()
            },
        )
    }
}

/**
 * `26-283`: builds the manual-booking wizard's own [PhoneCandidate] shape for the reuse path, from data
 * this hub already has in memory — no second read. [PhoneCandidate.firstSeenAt]/`.lastSeenAt` take an
 * empty-string placeholder rather than [Contact]'s own (non-existent) first/last-*seen* facts: verified
 * against every render site of a [ManualBookingClient.Existing] candidate
 * ([ManualBookingScreen.kt]'s own `PhoneCandidateFoundCard`/Client-step body), neither field is ever drawn
 * there — only [PhoneCandidate.displayName]/`.phone`/`.bookingCount` are — and this prefill skips straight
 * to [ManualBookingStep.Service] besides, so even the Client-step render those two dead fields feed is
 * never reached in the first place. [PhoneCandidate.bookingCount] is the one field that *is* rendered
 * (`phoneCandidateHistoryLabel`) and gets [ClientDetailUiState.Loaded.totalBookingsCount] — this hub's own
 * accurate count, not a placeholder — for exactly that reason.
 */
private fun manualBookingPrefillCandidate(state: ClientDetailUiState.Loaded): PhoneCandidate {
    val contact = state.contact
    return PhoneCandidate(
        personId = contact.customerId,
        phone = contact.phone,
        masked = contact.masked,
        noShowCount = contact.noShowCount,
        bookingCount = state.totalBookingsCount,
        phoneVerifiedAt = contact.phoneVerifiedAt,
        phoneConfirmedByOperatorAt = contact.phoneConfirmedByOperatorAt,
        firstSeenAt = "",
        lastSeenAt = "",
        displayName = contact.displayName,
    )
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
    onOpenManualBooking: () -> Unit,
    onClose: () -> Unit,
) {
    // `26-268` follow-up: one explicit close control above every state arm - unlike `ManualBookingSheet`'s
    // own title row, this sheet has no single title rendered across every arm (`Loading`/`NotConfigured`
    // draw no heading at all), so the X gets a bare header row of its own rather than riding a title that
    // does not exist in every state.
    //
    // `26-284` (item 2): the close row's own bottom padding is gone - it used to be implicit, coming
    // entirely from [ClientDetailLoadedBody]'s own `padding(24.dp)` on every side of its content `Column`,
    // which put a full 24dp gap *below* this row *in addition to* whatever height the icon button itself
    // takes, before the avatar header even starts. [ClientDetailLoadedBody] now opens with a much smaller
    // top inset instead (see that composable's own doc comment) so the header sits near this row rather
    // than a full close-button's-height-plus-24dp below it.
    //
    // `26-298` (live-testing screenshot): this row's own top padding is gone too - the drag handle
    // [ModalBottomSheet] already draws above this row supplies its own clearance, so a further explicit
    // gap here just widened the space between the drag handle and the header below for no visual gain.
    //
    // `26-306` (author screenshot, «поднять выше»): trimming this row's own top padding to zero (above)
    // still left the X sitting a full drag-handle's height below the sheet's top edge, because
    // [ModalBottomSheet]'s default `dragHandle` reserves that space *above* this content, not inside it -
    // `BottomSheetDefaults.DragHandle`'s own 22dp top/bottom padding around its 4dp pill
    // (`ClientDetailCloseButtonRaise`'s own doc comment). Pulling this row up by exactly that reserved
    // height puts the close button flush with the drag-handle line instead of a further row below it,
    // without touching the drag handle itself (still drawn, still centered) or this row's own layout.
    //
    // `26-308` (author screenshot): a `Column` still left a large *dead* gap between the (now-raised)
    // close row and the header below it, because [Modifier.offset] only shifts where a child is *drawn* -
    // it never changes the space a `Column` reserves for that child among its siblings
    // (https://developer.android.com/reference/kotlin/androidx/compose/foundation/layout/package-summary#(androidx.compose.ui.Modifier).offset(androidx.compose.ui.unit.Dp,androidx.compose.ui.unit.Dp)).
    // So the close row's own un-offset height (a full `IconButton` touch target) stayed reserved at the
    // top of the `Column` exactly where it used to sit, and the header started right after *that*, below
    // both the reserved slot and the row's now-higher drawn position - the visible gap this item reports.
    // A `Box` has no such per-child reserved flow: the close row is drawn wherever [Modifier.offset] and
    // [Alignment.TopEnd] put it (spilling upward over the drag handle exactly as before), while the header
    // - the other, unshifted child - starts flush at the sheet's own content top with no slot left behind.
    Box(modifier = Modifier.fillMaxWidth()) {
        ClientDetailStateBody(
            state = state,
            onRetry = onRetry,
            onReveal = onReveal,
            onConfirmPhone = onConfirmPhone,
            onSegmentSelected = onSegmentSelected,
            onOpenDialog = onOpenDialog,
            canCancelBooking = canCancelBooking,
            onCancelBooking = onCancelBooking,
            onOpenManualBooking = onOpenManualBooking,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp)
                    .offset(y = -ClientDetailCloseButtonRaise),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onClose) {
                Icon(imageVector = AgoIcons.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
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
    onOpenManualBooking: () -> Unit,
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
                onOpenManualBooking = onOpenManualBooking,
            )
    }
}

/**
 * `26-269`/`26-284`: `docs/backlog/26-269-clients-redesign.md` §4's own hub layout, in the order the
 * `26-284` polish pass settled on — header (avatar, name, phone + reveal), pills (returning/count,
 * no-show) directly under the phone, then «Подтвердить телефон» when it applies, then the
 * «+ Добавить запись» button, the Прошедшие/Предстоящие segmented control, that segment's own booking
 * list, and the SMS-confirmed/visit metadata rows at the bottom. `26-284` (item 7/10) moved the pills
 * above the banner and the button row (they used to sit *below* the banner). `26-308` moved
 * `Позвонить`/`Диалог` off that row and onto the phone line itself as trailing icon buttons, dropped the
 * inline warning glyph beside the name, and left «+ Добавить запись» (renamed from «+ Записать») as the
 * row's one remaining, now accent-styled, action — see that item's own doc comments on the phone row, the
 * name row and the button below for the reasoning behind each.
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
    onOpenManualBooking: () -> Unit,
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
    val callAction: () -> Unit = {
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + contact.phone))
        try {
            context.startActivity(dialIntent)
        } catch (missing: ActivityNotFoundException) {
            // No dialer app on this device - the number stays on screen either way, the identical posture
            // `InviteResultBody`'s own share-intent catch already takes for a missing target app.
        }
    }

    // `26-284` (item 2/3): a much smaller top inset than the header used to open with, and a smaller
    // bottom one too - `ClientDetailBody`'s own doc comment on its close row explains the top half; the
    // bottom half is the identical "the last visible content should not float in its own extra 24dp"
    // observation, restated for the sheet's own end rather than its start.
    //
    // `26-298` (live-testing screenshot): the top inset is trimmed again, from 4dp to 0dp - the close
    // row above already supplies the only clearance this header needs (see that row's own doc comment).
    Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
        // Three top-level columns: [1] avatar, [2] name with the phone directly beneath it, [3] the two
        // action icons. The icons live in their own column now, not on the phone line - an `IconButton`'s
        // own 48dp min height used to stretch the phone row and push the number well below the name; with
        // the icons out of that row, name and phone sit tight together, and the parent Row's own
        // `CenterVertically` keeps both icons opposite the avatar.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // `26-269` polish (B2): the header's own 48dp avatar - the identical three-way fallback
            // [ContactsScreen.kt]'s own 42dp list-row copy already draws, through the one shared
            // [ClientAvatar] composable.
            ClientAvatar(contact = contact, size = ClientDetailAvatarSize)
            Spacer(modifier = Modifier.width(ClientDetailAvatarGap))
            // Column 2: name + phone, nothing else - so no control's height can open a gap between them.
            Column(modifier = Modifier.weight(1f)) {
                val displayName = contact.displayName
                // `26-308` (item 4, author screenshot): the inline warning glyph this header used to draw
                // beside the name - [ConfirmPhoneBanner]'s own doc comment used to justify it as a "second,
                // harder-to-miss signal *in addition to* the banner below" - is gone. The banner is now the
                // header's only unconfirmed-phone signal, so a phone that needs attention is never flagged
                // twice in the same glance.
                val titleStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                when {
                    displayName != null -> Text(text = displayName, style = titleStyle)
                    // A nameless but chat-known visitor keeps their «{creature} · {food}» identity as the
                    // title - the phone still shows once in the row below, so nothing is duplicated for them.
                    visitorEmojiPair(contact.emojiCreature, contact.emojiFood) != null ->
                        VisitorIdentityText(
                            id = contact.customerId,
                            emojiCreature = contact.emojiCreature,
                            emojiFood = contact.emojiFood,
                            style = titleStyle,
                        )
                    // A manual client with neither a name nor a chat identity: just «Без имени» as the
                    // title. The phone used to be repeated here (as the title) *and* in the row below - now
                    // it shows once, below, and this line names the client instead of echoing the number.
                    else -> Text(text = stringResource(R.string.bookings_confirmed_identity_no_name), style = titleStyle)
                }

                Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // `26-307`: a masked preview (`contact.masked`) never carries the full 10 digits, so
                        // [formatRuPhoneForDisplay] passes it through unchanged - only a real, complete number
                        // is reformatted.
                        text = formatRuPhoneForDisplay(contact.phone),
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
            }
            // Column 3: `Диалог`/`Позвонить` as trailing icon buttons - the identical "always render both,
            // chat then call" treatment [ConfirmedBookingRow] already draws ([AgoIcons.Chat]/[AgoIcons.Call],
            // plain [IconButton]s with no fill or outline). The dialog icon stays `enabled` only when
            // [ClientDetailUiState.Loaded.hasDialog] is true - the identical no-op posture that row's own doc
            // comment states for a booking with no chat origin - while the call icon is always live, since
            // [callAction] never depended on a dialog existing.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { state.dialogConversationId?.let(onOpenDialog) }, enabled = state.hasDialog) {
                    Icon(
                        imageVector = AgoIcons.Chat,
                        contentDescription = stringResource(R.string.bookings_client_detail_open_dialog_action),
                    )
                }
                IconButton(onClick = callAction) {
                    Icon(
                        imageVector = AgoIcons.Call,
                        contentDescription = stringResource(R.string.bookings_client_detail_call_action),
                    )
                }
            }
        }

        // `26-284` (item 7/10): the pills now sit directly under the phone line - above the
        // «Подтвердить телефон» banner and above the action-button row below, moved up from their old
        // position (after the banner). «Постоянный клиент» exactly when
        // [ClientDetailUiState.Loaded.isReturningClient], then the plain booking-count pill every client
        // gets regardless, then [NoShowPill] when there is one to show - a no-show count is a caution,
        // worded and toned differently from the two plain status pills, but the design now groups all
        // three into one badge zone rather than a separate row further down.
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
            if (contact.noShowCount > 0) {
                NoShowPill(count = contact.noShowCount)
            }
        }

        if (contact.phoneNeedsAttention) {
            ConfirmPhoneBanner(confirming = state.confirmingPhone, onConfirmPhone = onConfirmPhone)
        }

        state.actionError?.let { error -> ActionErrorBanner(error = error, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) }

        // `26-308` (item 3, author screenshot): `Позвонить`/`Диалог` moved onto the phone row above as
        // icon buttons (that row's own doc comment), so this used-to-be-two-shapes action row collapses to
        // the one action left - «+ Добавить запись» - and it is no longer a secondary [OutlinedButton]
        // riding alongside a primary `Позвонить`: with `Позвонить` gone from here, manual booking is this
        // sheet's own main call to action, so it becomes a filled/accent [Button] (Material3's default
        // `primary`-container fill - the identical brand-blue accent [callAction]'s own removed `Button`
        // used to carry), full width, relabelled from «Записать» to «Добавить запись»
        // ([R.string.bookings_client_detail_add_booking_action], both languages - the old
        // `bookings_client_detail_record_action` key is gone since this was its only call site). Unlike the
        // old two-shapes-by-[ClientDetailUiState.Loaded.hasDialog] row, this single button now renders
        // identically whether or not the client has a dialog - the manual-booking action itself
        // ([onOpenManualBooking]) never depended on that fact either.
        Button(onClick = onOpenManualBooking, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Icon(imageVector = AgoIcons.Plus, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(text = stringResource(R.string.bookings_client_detail_add_booking_action))
        }

        BookingSegmentedControl(
            selected = state.selectedSegment,
            upcomingCount = state.upcoming.size,
            pastCount = state.past.size,
            onSelected = onSegmentSelected,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
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
            // and «Отменить» are reached from that card rather than the reschedule sheet directly or a
            // row-level button (`26-284` item 11: the row's own inline «Отменить», added in `26-275`, is
            // gone - the card path below already offers it). Both draw the chevron unconditionally,
            // signalling either destination alike.
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = ClientDetailBookingListMaxHeight),
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
 *
 * `26-284` (item 9): when [ClientDetailUiState.Loaded.isSingleVisit] is `true`, the separate «Первый
 * визит»/«Последний визит» pair collapses into one «Единственный визит» row instead — showing the same
 * date twice under two different labels reads as a glitch, not two facts, once first and last are the
 * same visit.
 */
@Composable
private fun ClientDetailMetadataRows(state: ClientDetailUiState.Loaded) {
    val smsConfirmedAt = state.contact.phoneVerifiedAt?.let { smsConfirmedFullDateOrNull(it) ?: "—" }
    val firstVisit = state.firstVisitLocalDate?.let(::businessLocalFullDateOrNull)
    val lastVisit = state.lastVisitLocalDate?.let(::businessLocalFullDateOrNull)
    val singleVisit = state.isSingleVisit
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
                Text(text = smsConfirmedAt, style = valueStyle)
            }
        }
        if (singleVisit && firstVisit != null) {
            HorizontalDivider()
            BookingDetailRow(
                label = stringResource(R.string.bookings_client_detail_single_visit_label),
                labelStyle = labelStyle,
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                Text(text = firstVisit, style = valueStyle)
            }
        } else {
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
}

// `26-306`: `BottomSheetDefaults.DragHandle`'s own reserved height - 22dp padding, a 4dp pill, 22dp
// padding - the space [ModalBottomSheet]'s default `dragHandle` draws above this sheet's own content,
// restated here rather than imported from Material3 (that composable exposes no public dimension
// constants of its own to read this back from). `ClientDetailBody`'s own close-row doc comment above is
// where this gets used, and why.
private val ClientDetailCloseButtonRaise = 48.dp

// The client-detail header's own avatar. 40dp to match every other client/visitor avatar in the app
// (`ContactsScreen.kt`'s own list row, `VisitorAvatar`'s own 40dp default): the mockup drew this header
// larger at 48px, but the author asked (2026-09-30) for one consistent avatar size everywhere except the
// app-bar operator chip (`AccountAvatarAction`'s own 36dp), which stays as it was.
private val ClientDetailAvatarSize = 40.dp

// `.row{gap:13px}` - restated here for the header's own avatar/name gap, the identical value
// [ContactsScreen.kt]'s own `ContactRowAvatarGap` carries for its own row (two independent screens, each
// naming its own gap beside its own citation, the same restraint that constant's own doc comment states).
private val ClientDetailAvatarGap = 13.dp

// `26-298` (live-testing screenshot): the bookings list used to carry a hard `.height(280.dp)` — a fixed
// box the sheet reserved regardless of how many rows `visible` actually held, which is exactly why a
// client with one booking still opened a sheet with a large empty tail below it. `heightIn(max = ...)`
// keeps the same 280dp ceiling (so a client with many bookings still gets a scrolling list rather than an
// ever-growing sheet) while letting a short list wrap to its own content height instead of padding out to
// the ceiling every time.
private val ClientDetailBookingListMaxHeight = 280.dp

/**
 * `26-298` (live-testing screenshot): «Подтвердить телефон» used to sit as a [TextButton] inline beside
 * the warning text, indented past the icon by [Column.weight] — a small, left-aligned pill that read as
 * crooked rather than as the banner's own call to action. It is now an [OutlinedButton] of its own, drawn
 * full width in a second row *under* the icon+text row, sized to the banner's own content width (the
 * identical width every other action in this sheet — the call/dialog/record row below — already spans).
 */
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
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
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
                Text(
                    text = stringResource(R.string.bookings_client_detail_confirm_phone_banner),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedButton(
                onClick = onConfirmPhone,
                enabled = !confirming,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
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

/** `docs/backlog/26-269-*.md` §3.5: Прошедшие/Предстоящие, each carrying its own count — the identical
 * `SegmentedButton`/`SingleChoiceSegmentedButtonRow` shape the top-level Записи tab bar already uses
 * ([BookingsScreen]'s own segment row), restated here for two entries rather than three.
 *
 * `26-284` (item 1): Прошедшие is now index 0 (left), Предстоящие index 1 (right) — the mockup's own
 * order, swapped from this control's original Предстоящие-left layout. [selected]'s own default
 * ([ClientDetailUiState.Loaded.selectedSegment]) is untouched by this reorder: which segment opens first
 * and which side of the control it is drawn on are independent questions, and only the second one changed
 * here. */
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
            selected = selected == ClientDetailSegment.Past,
            onClick = { onSelected(ClientDetailSegment.Past) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            // No leading checkmark on the selected segment - the identical `icon = {}` every other
            // `SegmentedButton` in this app already sets (BookingsScreen, SettingsScreen,
            // ConversationListScreen, TeamChatScreen, WidgetAppearanceEditor); this control was the one
            // that still let Material draw its default check.
            icon = {},
        ) {
            Text(text = "${stringResource(R.string.bookings_client_detail_past_segment)} $pastCount")
        }
        SegmentedButton(
            selected = selected == ClientDetailSegment.Upcoming,
            onClick = { onSelected(ClientDetailSegment.Upcoming) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = {},
        ) {
            Text(text = "${stringResource(R.string.bookings_client_detail_upcoming_segment)} $upcomingCount")
        }
    }
}

/**
 * One row of the visible segment — time leads (the identical leading-time-column reasoning
 * [ConfirmedBookingRow]'s own doc comment states for its own appointment row), then service name plus the
 * master's own name in bold on that same first line, and a "date · duration" sub-line below, with a
 * «Неявка» badge for [PersonBookingStatus.NoShow] rows (the design mockup's own examples: a no-show is
 * marked on the row itself, not folded into the date line).
 *
 * The row is two stacked columns plus a trailing chevron: the first pairs the date (bold) over the time
 * (muted), the second the master's name (bold) over "service · duration" (muted). The accent moved onto
 * the date because this list spans many days and the day is what tells two bookings apart - the time led
 * back when the list only ever showed a single day. [clientBookingServiceAndMaster] is the plain-Kotlin
 * half of the second column's mapping, pinned by [ClientBookingRowTest] without a Compose host.
 *
 * `26-269` polish (B9): every row is now a tap target and draws the trailing chevron unconditionally —
 * [ClientDetailLoadedBody]'s own [onClick] routes an upcoming tap to the reschedule sheet and a past one
 * to the new read-only booking-detail card, so this row itself no longer needs to know which segment it
 * is in (the earlier `clickable` parameter this composable took, gating both the modifier and the chevron
 * on "is this Предстоящие", is gone along with that distinction — both destinations are equally "this row
 * opens something").
 *
 * `26-275`/`adr/0189` added a row-level «Отменить» [TextButton] here, beside the chevron; `26-284`
 * (item 11) removes it again — cancelling an upcoming booking is reached by tapping the row into the
 * booking-detail card (`26-279` B8, [ClientDetailLoadedBody]'s own `selectedUpcomingBooking` block below),
 * which already offers «Отменить» there, so this row needs no cancel affordance of its own any more.
 */
@Composable
private fun ClientBookingRow(
    booking: PersonBooking,
    onClick: () -> Unit,
) {
    val (serviceName, masterName) = clientBookingServiceAndMaster(booking)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Column 1: date (bold) over time (muted). The date leads now - this list spans many days, so the
        // day is the fact that tells two bookings apart; the time is secondary beneath it. Fixed width
        // ([ClientBookingDateColumnWidth]) so every second column below lines up at the same x; the compact
        // "5 окт 26" format is what keeps that width small.
        Column(modifier = Modifier.width(ClientBookingDateColumnWidth)) {
            Text(
                text = clientBookingRowDateOrNull(booking.localDate) ?: "—",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = businessLocalTimeOrNull(booking.startsAt) ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Column 2: master (bold) over "service · duration" (muted).
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = masterName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // `26-306`: `weight(1f, fill = false)` - the identical `ConfirmedBookingRow` fix
                    // (`26-125` bug 3's own doc comment there) for the identical failure mode: without it,
                    // this `Text` claims the whole remaining row width even when its own content is short,
                    // which would push [NoShowBadge] hard against the trailing edge with a dead gap before
                    // it rather than sitting right after the text.
                    modifier = Modifier.weight(1f, fill = false).padding(bottom = 4.dp),
                )
                if (booking.status == PersonBookingStatus.NoShow) {
                    Spacer(modifier = Modifier.width(8.dp))
                    NoShowBadge()
                }
            }
            Text(
                text = clientBookingSubtitle(booking, service = serviceName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = AgoIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `26-306`: the row's own facts - service name (or "—" when absent, the identical fallback this
 * row already used) paired with the master's display name, Compose-free so [ClientBookingRowTest] can
 * assert the mapping directly rather than through a rendered `AnnotatedString`. */
internal fun clientBookingServiceAndMaster(booking: PersonBooking): Pair<String, String> =
    (booking.serviceName ?: "—") to booking.workerDisplayName

/** "Стрижка · 90 минут" - the row's second-column sub-line: the service name paired with the duration
 * spelled out in full through [russianPluralStringResource] (`bookings_duration_minutes_full_*`) rather
 * than the abbreviated «мин» badge [ConfirmedBookingRow] uses - this line has the width to say the whole
 * word without truncating it. Joined by " · " the identical way [WorkerGroupHeader] already joins its own
 * two: the middot is punctuation, not a phrase, so it needs no resource of its own. The date moved up to
 * the row's own first column (bold, above the time) now that the day - not the time - is what tells two
 * bookings in this multi-day list apart ([ClientBookingRow]'s own doc comment). [service] is the already-
 * resolved service name from [clientBookingServiceAndMaster] ("—" when the booking carries none, dropped
 * here rather than shown as a bare placeholder); a missing duration is dropped along with its own
 * separator too, never a stray " · " left dangling. */
@Composable
private fun clientBookingSubtitle(
    booking: PersonBooking,
    service: String,
): String {
    val pieces = mutableListOf<String>()
    service.takeIf { it != "—" }?.let(pieces::add)
    durationMinutesOrNull(booking.startsAt, booking.endsAt)?.let { minutes ->
        pieces.add(
            russianPluralStringResource(
                count = minutes,
                one = R.string.bookings_duration_minutes_full_one,
                few = R.string.bookings_duration_minutes_full_few,
                many = R.string.bookings_duration_minutes_full_many,
            ),
        )
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

/** "5 окт 26" - the booking row's own compact date (day, abbreviated Russian month, two-digit year), a
 * separate format from [CLIENT_BOOKING_DATE_FORMAT]'s full "5 октября 2026" (still used by the Первый/
 * Последний визит metadata rows). Short so the row's own fixed-width first column
 * ([ClientBookingDateColumnWidth]) can align every second column identically without a long month name
 * ("28 сент. 26") overrunning it. `null` on a malformed [localDate], the identical honest fallback
 * [businessLocalFullDateOrNull] takes for its own input. */
private fun clientBookingRowDateOrNull(localDate: String): String? =
    runCatching { LocalDate.parse(localDate).format(CLIENT_BOOKING_ROW_DATE_FORMAT) }.getOrNull()

private val CLIENT_BOOKING_ROW_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yy", Locale.forLanguageTag("ru"))

/** The booking row's own first-column width - fixed (not content-sized) so every row's second column
 * starts at the same x, and generous enough for the widest realistic compact date: a two-digit day plus
 * the longest abbreviated Russian month plus a two-digit year ("28 сент. 26"), with margin to spare. */
private val ClientBookingDateColumnWidth = 100.dp

/** "20 августа 2026" - the «Подтверждён по SMS» row's own value: the date [Contact.phoneVerifiedAt] was
 * proven, in the same full "d MMMM yyyy" form the Первый/Последний визит rows use (reusing
 * [CLIENT_BOOKING_DATE_FORMAT]). Reads the offset already embedded in the ISO string, the identical "no
 * zone conversion of any kind" idiom [businessLocalTimeOrNull]'s own doc comment states, restated here for
 * a date rather than a clock time. `null` on a malformed value, never a fabricated date. */
private fun smsConfirmedFullDateOrNull(iso: String): String? =
    runCatching { OffsetDateTime.parse(iso).toLocalDate().format(CLIENT_BOOKING_DATE_FORMAT) }.getOrNull()

// --------------------------------------------------------------------------------------------------
// Android Studio previews — не участвуют в сборке приложения, только визуальный рендер в IDE.
// Превьюим содержимое шита (ClientDetailBody), а НЕ сам ClientDetailSheet: тот дергает hiltViewModel()
// и оборачивается в ModalBottomSheet, что в превью не работает. Открой файл и нажми «Split».
// --------------------------------------------------------------------------------------------------

private val previewDetailContact =
    Contact(
        customerId = "1",
        phone = "+7 999 123-45-67",
        masked = false,
        displayName = "Анна Смирнова",
        noShowCount = 1,
        phoneVerifiedAt = "2026-09-20T10:00:00+03:00",
        phoneConfirmedByOperatorAt = null,
        upcomingBookingCount = 2,
    )

private fun previewBooking(
    id: String,
    service: String,
    worker: String,
    startsAt: String,
    endsAt: String,
    localDate: String,
    weekday: Int,
    status: PersonBookingStatus,
) = PersonBooking(
    bookingId = id,
    calendarId = "cal-1",
    workerId = "w-$id",
    workerDisplayName = worker,
    serviceId = "s-$id",
    serviceName = service,
    startsAt = startsAt,
    endsAt = endsAt,
    localDate = localDate,
    weekday = weekday,
    phone = "+7 999 123-45-67",
    masked = false,
    originConversationId = null,
    status = status,
)

private val previewUpcomingBookings =
    listOf(
        previewBooking(
            id = "u1",
            service = "Стрижка",
            worker = "Мария",
            startsAt = "2026-10-05T14:00:00+03:00",
            endsAt = "2026-10-05T15:00:00+03:00",
            localDate = "2026-10-05",
            weekday = 1,
            status = PersonBookingStatus.Booked,
        ),
        previewBooking(
            id = "u2",
            service = "Окрашивание",
            worker = "Ольга",
            startsAt = "2026-10-12T11:30:00+03:00",
            endsAt = "2026-10-12T13:00:00+03:00",
            localDate = "2026-10-12",
            weekday = 1,
            status = PersonBookingStatus.PendingConfirmation,
        ),
    )

private val previewPastBookings =
    listOf(
        previewBooking(
            id = "p1",
            service = "Стрижка",
            worker = "Мария",
            startsAt = "2026-09-01T12:00:00+03:00",
            endsAt = "2026-09-01T13:00:00+03:00",
            localDate = "2026-09-01",
            weekday = 2,
            status = PersonBookingStatus.Booked,
        ),
        previewBooking(
            id = "p2",
            service = "Маникюр",
            worker = "Ирина",
            startsAt = "2026-08-15T16:00:00+03:00",
            endsAt = "2026-08-15T17:00:00+03:00",
            localDate = "2026-08-15",
            weekday = 6,
            status = PersonBookingStatus.NoShow,
        ),
    )

@Preview(name = "Карточка клиента — записи", showBackground = true, locale = "ru", heightDp = 900)
@Composable
private fun ClientDetailLoadedPreview() {
    AgoChatTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ClientDetailBody(
                state =
                    ClientDetailUiState.Loaded(
                        contact = previewDetailContact,
                        upcoming = previewUpcomingBookings,
                        past = previewPastBookings,
                        dialogConversationId = "conv-1",
                    ),
                onRetry = {},
                onReveal = {},
                onConfirmPhone = {},
                onSegmentSelected = {},
                onOpenDialog = {},
                canCancelBooking = true,
                onCancelBooking = {},
                onOpenManualBooking = {},
                onClose = {},
            )
        }
    }
}

@Preview(name = "Карточка клиента — без записей", showBackground = true, locale = "ru", heightDp = 700)
@Composable
private fun ClientDetailEmptyPreview() {
    AgoChatTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ClientDetailBody(
                state =
                    ClientDetailUiState.Loaded(
                        contact = previewDetailContact.copy(displayName = null, upcomingBookingCount = 0),
                        upcoming = emptyList(),
                        past = emptyList(),
                        dialogConversationId = null,
                    ),
                onRetry = {},
                onReveal = {},
                onConfirmPhone = {},
                onSegmentSelected = {},
                onOpenDialog = {},
                canCancelBooking = false,
                onCancelBooking = {},
                onOpenManualBooking = {},
                onClose = {},
            )
        }
    }
}

@Preview(name = "Карточка клиента — загрузка", showBackground = true, locale = "ru")
@Composable
private fun ClientDetailLoadingPreview() {
    AgoChatTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ClientDetailBody(
                state = ClientDetailUiState.Loading,
                onRetry = {},
                onReveal = {},
                onConfirmPhone = {},
                onSegmentSelected = {},
                onOpenDialog = {},
                canCancelBooking = false,
                onCancelBooking = {},
                onOpenManualBooking = {},
                onClose = {},
            )
        }
    }
}
