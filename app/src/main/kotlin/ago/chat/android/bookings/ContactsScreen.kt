package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.core.domain.visitorEmojiPair
import ago.chat.android.ui.components.SectionLabel
import ago.chat.android.ui.components.VisitorAvatar
import ago.chat.android.ui.components.VisitorIdentityText
import ago.chat.android.ui.components.formatRuPhoneForDisplay
import ago.chat.android.ui.components.initialsFor
import ago.chat.android.ui.components.russianPluralStringResource
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * `26-52`: Клиенты's own body — a flat card list, no row that opens anything
 * (`docs/backlog/26-52-*.md`'s own Out of scope: "a row that opens nothing is fine, the list itself is
 * the answer"). The four-arm `when` below is the identical shape
 * [ConfirmedBookingsBody]/[BookingsScreen] own `Pending` branch already draw for their own state.
 *
 * `26-53`: no longer strictly read-only — [onReveal] is the one write this screen now offers. The
 * `Column`/`Box(weight)` wrapper and the [ActionErrorBanner] placement are the identical shape
 * [BookingsScreen]'s own `Pending` branch already establishes for its own veto-write errors, restated
 * here for the same reason: a refusal is shown above the list, never in place of it.
 *
 * `26-269`: [onSearchQueryChange] is the list's own search box — see [ContactsSearchField]. Drawn only
 * once [ContactsUiState.Loaded.contacts] is non-empty: a tenant with no customers at all has nothing to
 * search, so it keeps the pre-existing "empty customer base" message with no search box sitting uselessly
 * above it (`docs/backlog/26-269-*.md` §3.1's own search-field scope, read together with `26-52`'s own
 * "empty is a state" rule below). A non-empty [Contact] list whose [ContactsUiState.Loaded.visibleContacts]
 * comes back empty (the query matched nobody) renders its own, differently-worded empty state
 * ([R.string.bookings_contacts_search_empty]) rather than [R.string.bookings_contacts_empty] — the two are
 * different facts ("no customers exist" vs. "no customer matches this search") and must not share a
 * sentence.
 *
 * `26-269`: a tap on a row now opens the client-detail hub ([ClientDetailSheet]) — the affordance
 * `26-52`'s own doc comment explicitly deferred ("a row that opens nothing is fine, the list itself is
 * the answer"). [selectedClientId] is local navigation state, not view-model state — the identical
 * "which sheet is open is UI, not network" split [ConfirmedBookingsBody]'s own `selectedBookingId` already
 * draws — and holds only the tapped [Contact.customerId], re-resolved against [state]'s own current list on
 * every recomposition (the identical "hold the id, derive the object" shape that same sheet uses), so a
 * reveal that lands while the hub is open is picked up rather than frozen at the moment it was tapped.
 * [onOpenDialog] is threaded straight through to the hub with no handling here — this body owns no
 * conversation-navigation decision of its own.
 */
@Composable
internal fun ContactsBody(
    state: ContactsUiState,
    onRetry: () -> Unit,
    onReveal: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    // `26-282` (A8): defaulted to a no-op / `ContactsFilter.All` so every existing call site (this
    // screen's own previews and androidTest hosts included) keeps compiling unchanged, the identical
    // "new parameter, old call sites untouched" discipline `canEraseClient`/`onDeleteClient` already
    // establish below for the erase gesture.
    onFilterChange: (ContactsFilter) -> Unit = {},
    onOpenDialog: (String) -> Unit,
    // `26-275`: `customer:erase` alone - gates the row's own swipe-to-delete
    // (`docs/backlog/26-275-*.md` §3/§6.1), mirrored from `ConversationListScreen`'s own
    // `AllRow`/`canErase`. Defaulted `false` so every existing call site keeps compiling unchanged.
    canEraseClient: Boolean = false,
    onDeleteClient: (String) -> Unit = {},
    onDismissBlockedErasure: () -> Unit = {},
    // `26-275`: threaded straight through to [ClientDetailSheet] - see that composable's own doc comment
    // on why the Предстоящие row's new «Отменить» needs its own, separate permission.
    canCancelBooking: Boolean = false,
) {
    var selectedClientId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state is ContactsUiState.Loaded) {
            state.actionError?.let { error -> ActionErrorBanner(error = error, modifier = Modifier.fillMaxWidth()) }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                ContactsUiState.Loading -> LoadingBody()
                ContactsUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
                is ContactsUiState.Failed ->
                    RefusalBody(
                        reason = state.reason,
                        onRetry = onRetry,
                        unexpectedMessageRes = R.string.bookings_contacts_load_failed_unexpected,
                    )

                is ContactsUiState.Loaded ->
                    // `docs/backlog/26-52-*.md`'s own Done-when: "an empty customer base renders a stated
                    // empty state" - the identical "empty is a state, not a blank area" rule
                    // [ago.chat.android.bookings.BookingsScreen]'s own `Pending` branch already applies.
                    if (state.contacts.isEmpty()) {
                        EmptyBody(stringResource(R.string.bookings_contacts_empty))
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            ContactsSearchField(query = state.searchQuery, onQueryChange = onSearchQueryChange)
                            ContactsFilterRow(selected = state.filter, onSelected = onFilterChange)
                            val visible = state.visibleContacts
                            if (visible.isEmpty()) {
                                // `26-269` polish (A6): «Очистить поиск» - the zero-match recovery action,
                                // clearing `searchQuery` straight back to blank through the identical
                                // `onSearchQueryChange` the text field itself already calls.
                                EmptyBody(
                                    text = stringResource(R.string.bookings_contacts_search_empty),
                                    action = {
                                        TextButton(onClick = { onSearchQueryChange("") }) {
                                            Text(text = stringResource(R.string.bookings_contacts_search_clear_action))
                                        }
                                    },
                                )
                            } else {
                                // `26-269` polish (A5): «Найдено N» - drawn only while a query is active
                                // and it actually matched somebody; a blank query shows the full list with
                                // no count line at all, and a zero-match query draws the empty state above
                                // instead, never this label over nothing.
                                if (state.searchQuery.isNotBlank()) {
                                    SectionLabel(text = stringResource(R.string.bookings_contacts_search_found_count, visible.size))
                                }
                                ContactsList(
                                    contacts = visible,
                                    revealingCustomerIds = state.revealingCustomerIds,
                                    deletingClientIds = state.deletingClientIds,
                                    canEraseClient = canEraseClient,
                                    onReveal = onReveal,
                                    onOpenClient = { customerId -> selectedClientId = customerId },
                                    onDeleteClient = onDeleteClient,
                                )
                            }
                        }
                    }
            }
        }
    }

    // `26-275`: the blocked-delete explanation (§6.1) - a client-side branch off the server's own typed
    // `person_erase.future_bookings` refusal (`ContactsViewModel.deleteClient`'s own doc comment), never a
    // pre-check this screen performs itself (rule 8: the server is the one and only gate). «Перейти к
    // записям» opens the same [ClientDetailSheet] an ordinary row tap already would, for the same id -
    // that sheet already defaults to Предстоящие ([ClientDetailUiState.Loaded.selectedSegment]'s own
    // default), so no extra navigation state is needed to land the operator on the right segment.
    val blockedClientId = (state as? ContactsUiState.Loaded)?.blockedErasureClientId
    if (blockedClientId != null) {
        ClientEraseBlockedDialog(
            onDismiss = onDismissBlockedErasure,
            onNavigateToUpcoming = {
                selectedClientId = blockedClientId
                onDismissBlockedErasure()
            },
        )
    }

    val selectedContact =
        (state as? ContactsUiState.Loaded)?.contacts?.firstOrNull { it.customerId == selectedClientId }
    if (selectedContact != null) {
        ClientDetailSheet(
            contact = selectedContact,
            onDismiss = { selectedClientId = null },
            onOpenDialog = onOpenDialog,
            canCancelBooking = canCancelBooking,
        )
    }
}

/**
 * `26-269`: the list's own live filter — every keystroke calls [onQueryChange] straight through to
 * [ContactsViewModel.onSearchQueryChange], no submit button and no IME "search" action: unlike
 * [ago.chat.android.conversations.ConversationSearchScreen]'s own server-backed search (a real request per
 * search, worth gating behind a deliberate submit), this filters the list already sitting in memory
 * (`docs/backlog/26-269-*.md` §1.5.3's "client-side... works the instant the list is on screen"), so there
 * is nothing to save a keystroke's worth of network cost by withholding.
 */
@Composable
private fun ContactsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(text = stringResource(R.string.bookings_contacts_search_placeholder)) },
        leadingIcon = { Icon(imageVector = AgoIcons.Search, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * `26-282` (A8): the filter chip row under the search box — «Все» / «С предстоящей записью» / «Без
 * записей», the identical `Row(horizontalScroll) { FilterChip(...) }` idiom [MastersBody]'s own calendar
 * picker already establishes for a small, single-select chip set, restated here for [ContactsFilter]
 * instead of a calendar id. Drawn once, above both the zero-match empty state and the list itself
 * ([ContactsBody]'s own call site), so a filter that currently matches nobody still leaves the chips
 * reachable to switch back — the identical reasoning `onSearchQueryChange("")`'s own «Очистить поиск»
 * action states for the search box.
 */
@Composable
private fun ContactsFilterRow(
    selected: ContactsFilter,
    onSelected: (ContactsFilter) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ContactsFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                label = { Text(text = stringResource(filter.labelRes())) },
            )
        }
    }
}

private fun ContactsFilter.labelRes(): Int =
    when (this) {
        ContactsFilter.All -> R.string.bookings_contacts_filter_all
        ContactsFilter.HasUpcoming -> R.string.bookings_contacts_filter_has_upcoming
        ContactsFilter.NoUpcoming -> R.string.bookings_contacts_filter_no_upcoming
    }

@Composable
private fun ContactsList(
    contacts: List<Contact>,
    revealingCustomerIds: Set<String>,
    deletingClientIds: Set<String>,
    canEraseClient: Boolean,
    onReveal: (String) -> Unit,
    onOpenClient: (String) -> Unit,
    onDeleteClient: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(contacts, key = { it.customerId }) { contact ->
            SwipeableContactRow(
                contact = contact,
                revealing = contact.customerId in revealingCustomerIds,
                deleting = contact.customerId in deletingClientIds,
                canErase = canEraseClient,
                onReveal = { onReveal(contact.customerId) },
                onOpenClient = { onOpenClient(contact.customerId) },
                onConfirmDelete = { onDeleteClient(contact.customerId) },
            )
            HorizontalDivider()
        }
    }
}

/**
 * `26-275`/`adr/0189`: the Клиенты row's own swipe-to-delete — ported verbatim from
 * `ago.chat.android.conversations.ConversationListScreen`'s own `AllRow`/`EraseAction`/
 * `EraseConfirmDialog` (`docs/backlog/26-275-*.md` §1.2/§6.1: "this whole shape ports one-for-one to the
 * Клиенты row"). The hand-rolled 80dp partial reveal via `Modifier.draggable`, the halfway settle, the
 * danger `Surface` panel with the `TrashForever` glyph and a two-line caption, and the confirm dialog are
 * all taken from that file unchanged — only the caption text (`ContactEraseAction`'s own strings), the
 * capability ([canErase] is `customer:erase` rather than `conversation:erase`) and what a confirm does
 * (branches on the server's own typed refusal rather than an optimistic remove-with-retry, since this
 * write answers synchronously with one of three outcomes rather than an async erasure job the caller
 * would have to poll for) differ.
 *
 * **The gesture is not attached at all without `customer:erase`** — the identical "hide, don't disable"
 * rule [canErase]'s own doc comment on [Permission.CUSTOMER_ERASE][ago.chat.android.core.domain.permissions.Permission.CUSTOMER_ERASE]
 * states, restated here for this row: `.then(if (swipeable) … else Modifier)`, and
 * `LaunchedEffect(swipeable)` closes any open reveal the moment the capability is revoked.
 *
 * [deleting] disables the row's own reveal control (no unmasking mid-delete) and keeps the swipe closed
 * while this customer's own delete is on the network — there is no optimistic removal to undo here (the
 * write answers synchronously, so the row simply waits for [onConfirmDelete]'s own caller to fold the
 * result into state, the same "the caller already knows what it asked for" posture
 * [ago.chat.android.core.domain.bookings.BookingsApi.deleteClient]'s own doc comment states).
 */
@Composable
private fun SwipeableContactRow(
    contact: Contact,
    revealing: Boolean,
    deleting: Boolean,
    canErase: Boolean,
    onReveal: () -> Unit,
    onOpenClient: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val swipeable = canErase && !deleting
    val revealWidthPx = with(LocalDensity.current) { ClientEraseActionWidth.toPx() }
    var offsetX by remember(contact.customerId) { mutableFloatStateOf(0f) }
    var confirming by rememberSaveable(contact.customerId) { mutableStateOf(false) }
    val animatedOffset by animateFloatAsState(targetValue = offsetX, label = "clientEraseReveal")

    // Closes the reveal again whenever the gesture stops being available - the identical
    // `AllRow`/`LaunchedEffect(swipeable)` reasoning, restated here for a capability revoke or a delete
    // going in flight alike.
    LaunchedEffect(swipeable) { if (!swipeable) offsetX = 0f }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (swipeable && offsetX < 0f) {
            Row(modifier = Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
                ClientEraseAction(onClick = { confirming = true })
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier =
                Modifier
                    .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                    .then(
                        if (swipeable) {
                            Modifier.draggable(
                                orientation = Orientation.Horizontal,
                                state =
                                    rememberDraggableState { delta ->
                                        offsetX = (offsetX + delta).coerceIn(-revealWidthPx, 0f)
                                    },
                                onDragStopped = {
                                    offsetX = if (offsetX < -revealWidthPx / 2f) -revealWidthPx else 0f
                                },
                            )
                        } else {
                            Modifier
                        },
                    ),
        ) {
            ContactCard(
                contact = contact,
                revealing = revealing || deleting,
                onReveal = onReveal,
                onOpenClient = onOpenClient,
            )
        }
    }

    if (confirming) {
        ClientEraseConfirmDialog(
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                offsetX = 0f
                onConfirmDelete()
            },
        )
    }
}

/** The mockup's `.swipe-del` panel, restated from `ConversationListScreen`'s own `EraseAction` for the
 * Клиенты caption («Удалить» / «клиента»). See that composable's own doc comment for the metrics this
 * one shares verbatim. */
@Composable
private fun ClientEraseAction(onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        modifier = Modifier.width(ClientEraseActionWidth).fillMaxHeight(),
    ) {
        Column(
            modifier =
                Modifier
                    .clickable(
                        onClickLabel = stringResource(R.string.bookings_contacts_erase_action_description),
                        onClick = onClick,
                    ).testTag(CLIENT_ERASE_ACTION_TEST_TAG)
                    .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = AgoIcons.TrashForever,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
            Text(
                text = stringResource(R.string.bookings_contacts_erase_action_line_one),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.bookings_contacts_erase_action_line_two),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/**
 * `26-275`/`adr/0189`: the confirmation between the swipe and the request — the identical
 * `ConversationListScreen.EraseConfirmDialog` shape, its body naming the blast radius (adr/0189's own
 * Option A: erasing a client erases the calendar record **and** the chat person, conversations and
 * messages alike) so an operator swiping a row is never surprised by what "delete" actually reaches.
 */
@Composable
private fun ClientEraseConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.bookings_contacts_erase_confirm_title)) },
        text = { Text(text = stringResource(R.string.bookings_contacts_erase_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.bookings_contacts_erase_confirm_action),
                    color = agoStatusColors().dangerText,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * `26-275`/`adr/0189`: the blocked-delete state (§6.1) — drawn instead of [ClientEraseConfirmDialog] once
 * the server has refused with `person_erase.future_bookings` (`ContactsViewModel.deleteClient`'s own
 * doc comment). [onNavigateToUpcoming] is the «Перейти к записям» primary action §5 asks for; the plain
 * dismiss is the identical `onDismiss`/cancel pairing [ClientEraseConfirmDialog] already uses.
 */
@Composable
private fun ClientEraseBlockedDialog(
    onDismiss: () -> Unit,
    onNavigateToUpcoming: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.bookings_contacts_erase_blocked_title)) },
        text = { Text(text = stringResource(R.string.bookings_contacts_erase_blocked_body)) },
        confirmButton = {
            TextButton(onClick = onNavigateToUpcoming) {
                Text(text = stringResource(R.string.bookings_contacts_erase_blocked_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

/** `.swipe-del{width:80px}` - the identical value `ConversationListScreen`'s own `EraseActionWidth`
 * carries, restated here rather than shared across files for the same reason that file's own row metrics
 * are not shared with this one (two independent screens, each naming its own constants beside its own
 * CSS citation). */
private val ClientEraseActionWidth = 80.dp

/** `ClientEraseAction`'s own test hook — the identical `ERASE_ACTION_TEST_TAG` idiom
 * `ConversationListScreen` already establishes, restated for this row's own tag. */
internal const val CLIENT_ERASE_ACTION_TEST_TAG = "contactsListEraseAction"

/**
 * The mockup's own `ClientCard`, minus the affordance that card's graph draws with nothing behind it
 * yet (`docs/backlog/26-52-*.md`'s own Out of scope: "a client detail card... is fine here" to omit) —
 * name, the masked phone verbatim, and the no-show count, each read plainly (Scope item 2).
 *
 * A customer with no [Contact.displayName] renders through
 * [ago.chat.android.ui.components.VisitorIdentityText] — the stored emoji pair
 * ([ContactsViewModel.mergePersonDetails]'s own client-side merge, `26-203`) when chat's person registry
 * has one for [Contact.customerId], else `26-279` (A9)'s own further fallback — [Contact.phone] as the
 * title plus a stated «Без имени» label, since that field is never null for a customer — but never a raw
 * GUID and never an invented label (`ui/components/IdentifierText.kt`'s own doc comment,
 * `docs/backlog/26-52-*.md`'s own Done-when, `docs/backlog/26-279-*.md`'s own A9).
 *
 * `26-53`: the phone row now draws Показать exactly when [Contact.masked] says so — never inferred from
 * the string's own shape (`ago-console`'s own `renderPhone` doc comment states the identical rule this
 * mirrors). [revealing] disables the control while this customer's own reveal is in flight, and swaps
 * its label to say so, the identical `RevealControl`/`revealing` shape `renderPhone` already draws.
 *
 * `26-269`: the two full-sentence phone-status lines (`bookings_contacts_phone_verified_no`/
 * `..._confirmed_no`) this card used to draw unconditionally are gone. The warning glyph for an
 * unconfirmed phone (`docs/backlog/26-269-*.md` §3.3: "the single actionable state... when it is verified
 * *either* way, show no icon") now sits inline on the phone line itself — see this card's own phone [Row]
 * below — and [NoShowRow] draws only the no-show pill, only when the count is positive (§3.4: "zero is the
 * quiet default").
 *
 * `26-269`: [onOpenClient] makes the whole card a tap target — the client-detail hub's own entry point,
 * the affordance this card's own doc comment above states `26-52` deliberately left out. The reveal
 * control keeps its own, narrower [TextButton] tap target *inside* this same clickable card (a
 * `TextButton` consumes its own click before it reaches the card's `clickable` behind it, the ordinary
 * Compose nested-click-target behaviour), so «Показать» still reveals in place rather than opening the
 * hub.
 *
 * `26-269` polish (A1/A2): a leading [ClientAvatar] and a trailing chevron now bracket the identical
 * name/phone/no-show content above — the mockup's own `.crow` row shape, signalling with both an icon
 * *and* the whole card's own [onOpenClient] tap that this row opens something, the affordance `26-52`'s
 * own doc comment above states was deliberately absent at first.
 */
@Composable
private fun ContactCard(
    contact: Contact,
    revealing: Boolean,
    onReveal: () -> Unit,
    onOpenClient: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenClient)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClientAvatar(contact = contact, size = ContactRowAvatarSize, modifier = Modifier.padding(end = ContactRowAvatarGap))
        Column(modifier = Modifier.weight(1f)) {
            val displayName = contact.displayName
            if (displayName != null) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            } else {
                VisitorIdentityText(
                    id = contact.customerId,
                    emojiCreature = contact.emojiCreature,
                    emojiFood = contact.emojiFood,
                    // `26-279` (A9): the row's own further fallback below the emoji pair - `Contact.phone`
                    // is never null, so a nameless, pair-less client shows that real fact plus «Без имени»
                    // rather than the raw `customerId` this row used to leak through as a title.
                    phone = contact.phone,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            }
            // The phone, exactly as the server sent it, plus Показать when `masked` says a real number is
            // still hidden behind it - `26-52`'s own "Masked is masked, no control at all" rule is now
            // `26-53`'s "a control exactly when the server says there is something to reveal".
            //
            // `26-268` follow-up (author feedback 2026-09-29): the unconfirmed-phone warning glyph used to sit
            // on its own row below this one (`NoShowRow`'s own doc comment traces that split); the author asked
            // for it inline instead, immediately after the phone number on this same line, since the icon *is*
            // a fact about this number, not a fact about the row as a whole the way the no-show pill is.
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // `26-307`: [formatRuPhoneForDisplay] formats only a complete Russian number - a masked
                    // preview (`contact.masked`) never has the full 10 digits, so it passes through unchanged.
                    text = formatRuPhoneForDisplay(contact.phone) + upcomingBookingCountSuffix(contact.upcomingBookingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (contact.phoneNeedsAttention) {
                    Icon(
                        imageVector = AgoIcons.ErrorCircle,
                        contentDescription = stringResource(R.string.bookings_contacts_phone_unverified_description),
                        tint = agoStatusColors().warning,
                        modifier = Modifier.padding(start = 6.dp).size(16.dp),
                    )
                }
                if (contact.masked) {
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
            NoShowRow(contact = contact)
        }
        Icon(
            imageVector = AgoIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * `26-282` (A7): the row's own « · N записи» suffix, appended straight onto the phone [Text] rather than
 * a separate composable — `docs/backlog/26-282-*.md`'s own "same text style as the phone" instruction is
 * automatically true this way, with no second `Text` to keep in sync with the first one's style/colour if
 * either ever changes. Empty string, never rendered, when [count] is zero — the identical "zero is the
 * quiet default" rule [NoShowRow]'s own doc comment states for the no-show pill, restated here for a plain
 * inline suffix rather than a pill: a client with nothing booked ahead gets a bare phone number, exactly
 * as before this item.
 */
@Composable
private fun upcomingBookingCountSuffix(count: Int): String {
    if (count <= 0) return ""
    return " · " +
        russianPluralStringResource(
            count = count.toLong(),
            one = R.string.bookings_contacts_upcoming_count_one,
            few = R.string.bookings_contacts_upcoming_count_few,
            many = R.string.bookings_contacts_upcoming_count_many,
        )
}

/**
 * `26-269` polish (A1): the client row's own leading avatar, 42dp — [ClientDetailLoadedBody]'s own 48dp
 * header copy is the same three-way fallback at a different size, both reusing this one composable rather
 * than three call sites re-deriving the identical order: [Contact.displayName] initials
 * ([ago.chat.android.ui.components.initialsFor], the identical algorithm [ago.chat.android.ui.components.AccountAvatarAction]'s
 * own operator avatar already uses) when a real name is known, else the stored
 * [ago.chat.android.ui.components.VisitorAvatar] emoji-pair badge when chat has one for this id, else
 * [AgoIcons.AddPerson] — this app's own redrawn `i-user-plus` (that icon's own doc comment) — for a
 * manual client (`26-268`) with neither a name nor a chat identity. The identical "encode the fallback
 * order as data, not a repeated `if`/`else if`/`else` chain" reasoning [BookingIdentity]'s own doc comment
 * states for its own three-arm identity, restated here for an avatar instead of a text line.
 */
@Composable
internal fun ClientAvatar(
    contact: Contact,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val displayName = contact.displayName?.takeIf { it.isNotBlank() }
    when {
        displayName != null -> {
            val initials = remember(displayName) { initialsFor(displayName, fallback = "") }
            Box(
                modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        visitorEmojiPair(contact.emojiCreature, contact.emojiFood) != null ->
            VisitorAvatar(
                emojiCreature = contact.emojiCreature,
                emojiFood = contact.emojiFood,
                diameter = size,
                modifier = modifier,
            )

        else ->
            Box(
                modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AgoIcons.AddPerson,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size / 2),
                )
            }
    }
}

// `.row{gap:13px}` - the mockup's own row gap, the identical value `ConversationListScreen`'s own
// private `RowGap` already carries for its own visitor row, restated here rather than shared across files
// (that constant's own restraint: two independent rows, each naming its own gap beside its own citation).
private val ContactRowAvatarGap = 13.dp

// `.av{width:42px; height:42px}` - Клиенты's own list-row avatar size, distinct from
// [ClientDetailLoadedBody]'s own 48dp header copy (the mockup draws the two at different sizes).
private val ContactRowAvatarSize = 42.dp

/**
 * `26-269`: the row's own no-show pill — [contact.noShowCount] worded through
 * [russianPluralStringResource] (the project's own hand-rolled Russian/English plural split,
 * [ago.chat.android.ui.components.russianPluralStringResource]'s own doc comment) — drawn only when the
 * count is positive (`docs/backlog/26-269-*.md` §3.4: "otherwise nothing... zero is the quiet default"), in
 * the same `warningTint`/`warning` pair the phone line's own warning glyph uses: a client who no-showed is
 * exactly the same "worth a second look before committing a slot" caution an unconfirmed phone is, not a
 * harsher one.
 *
 * `26-268` follow-up (author feedback 2026-09-29): this composable used to also draw the unconfirmed-phone
 * warning glyph, sharing this same row with the no-show pill under the name `PhoneStatusAndNoShowRow`. The
 * author asked for that glyph inline with the phone number instead ([ContactCard]'s own phone [Row] now
 * draws it, right after the number itself), which left this composable with only the no-show pill — renamed
 * to say exactly that, nothing more.
 */
@Composable
private fun NoShowRow(contact: Contact) {
    if (contact.noShowCount <= 0) return
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        NoShowPill(count = contact.noShowCount)
    }
}

/** The mockup's plain, warning-toned pill — [agoStatusColors]'s own `warningTint`/`warning` pair,
 * restated here rather than shared with [ago.chat.android.conversations.ConversationListScreen]'s own
 * private `StatusPill`, which lives in a different file for a different row shape (the same restraint
 * that file's own doc comment states: a second, near-identical composable is fine, extract only once a
 * third caller needs the identical thing).
 *
 * `26-269`: `internal`, not `private` — [ClientDetailScreen.kt][ClientDetailBody] draws the identical
 * no-show fact on the client-detail hub's own header, and a second, independently-drifting copy of the
 * same warning-toned pill for the same count is exactly the drift this promotion avoids, not a case of
 * the "extract only once a third caller needs it" restraint above (that restraint is about *not* sharing
 * with a *different* pill shape elsewhere, e.g. `StatusPill` — reusing this exact composable for its own
 * exact fact, a second time, is the opposite situation). */
@Composable
internal fun NoShowPill(count: Int) {
    Surface(
        color = agoStatusColors().warningTint,
        contentColor = agoStatusColors().warning,
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(
            text =
                russianPluralStringResource(
                    count = count.toLong(),
                    one = R.string.bookings_contacts_noshow_pill_one,
                    few = R.string.bookings_contacts_noshow_pill_few,
                    many = R.string.bookings_contacts_noshow_pill_many,
                ),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}
