package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.ui.components.VisitorIdentityText
import ago.chat.android.ui.components.russianPluralStringResource
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
                            val visible = state.visibleContacts
                            if (visible.isEmpty()) {
                                EmptyBody(stringResource(R.string.bookings_contacts_search_empty))
                            } else {
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
 * has one for [Contact.customerId], or the bare id, but never a raw GUID and never an invented label
 * (`ui/components/IdentifierText.kt`'s own doc comment, `docs/backlog/26-52-*.md`'s own Done-when).
 *
 * `26-53`: the phone row now draws Показать exactly when [Contact.masked] says so — never inferred from
 * the string's own shape (`ago-console`'s own `renderPhone` doc comment states the identical rule this
 * mirrors). [revealing] disables the control while this customer's own reveal is in flight, and swaps
 * its label to say so, the identical `RevealControl`/`revealing` shape `renderPhone` already draws.
 *
 * `26-269`: the two full-sentence phone-status lines (`bookings_contacts_phone_verified_no`/
 * `..._confirmed_no`) this card used to draw unconditionally are gone, replaced by
 * [PhoneStatusAndNoShowRow] — a single row drawn only when it has something to say
 * (`docs/backlog/26-269-*.md` §3.3/§3.4: the warning glyph only when the phone is neither verified nor
 * operator-confirmed, the no-show pill only when the count is positive; "zero/confirmed is the quiet
 * default" for both).
 *
 * `26-269`: [onOpenClient] makes the whole card a tap target — the client-detail hub's own entry point,
 * the affordance this card's own doc comment above states `26-52` deliberately left out. The reveal
 * control keeps its own, narrower [TextButton] tap target *inside* this same clickable card (a
 * `TextButton` consumes its own click before it reaches the card's `clickable` behind it, the ordinary
 * Compose nested-click-target behaviour), so «Показать» still reveals in place rather than opening the
 * hub.
 */
@Composable
private fun ContactCard(
    contact: Contact,
    revealing: Boolean,
    onReveal: () -> Unit,
    onOpenClient: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenClient)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
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
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
        // The phone, exactly as the server sent it, plus Показать when `masked` says a real number is
        // still hidden behind it - `26-52`'s own "Masked is masked, no control at all" rule is now
        // `26-53`'s "a control exactly when the server says there is something to reveal".
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = contact.phone,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        PhoneStatusAndNoShowRow(contact = contact)
    }
}

/**
 * `26-269`: the row's own compact status line — a [AgoIcons.Exclamation] glyph, tinted
 * `agoStatusColors().warning` (the mockup's own `<svg class="i" style="color:var(--warning)">`, never
 * [agoStatusColors().dangerIcon] — that role is reserved for [ago.chat.android.ui.icons.AgoIcons.ErrorCircle]'s
 * "needs attention now" call sites, and an unconfirmed phone is a caution, not an error), drawn **only**
 * when [Contact.phoneVerifiedAt] **and** [Contact.phoneConfirmedByOperatorAt] are both `null`
 * (`docs/backlog/26-269-*.md` §3.3: "the single actionable state... When it is verified *either* way, show
 * no icon"). The two facts stay exactly two facts on the wire and on [Contact] itself — only this row's
 * *presentation* collapses them into one glyph, never the underlying data.
 *
 * [contentDescription] carries the actual words ([R.string.bookings_contacts_phone_unverified_description])
 * rather than `null`: unlike [ago.chat.android.bookings.ReadinessBody]'s identical-looking icon (which sits
 * beside its own text label and can stay decorative), this glyph is now the *only* thing on the row saying
 * "unconfirmed" — a screen reader needs the words the two deleted sentences used to carry, even though a
 * sighted operator reads the icon alone.
 *
 * The no-show pill is [contact.noShowCount] worded through [russianPluralStringResource] — the project's
 * own hand-rolled Russian/English plural split ([ago.chat.android.ui.components.russianPluralStringResource]'s
 * own doc comment) — drawn only when the count is positive (`docs/backlog/26-269-*.md` §3.4: "otherwise
 * nothing... zero is the quiet default"), in the same `warningTint`/`warning` pair as the glyph: a client who
 * no-showed is exactly the same "worth a second look before committing a slot" caution the phone glyph is,
 * not a harsher one.
 */
@Composable
private fun PhoneStatusAndNoShowRow(contact: Contact) {
    val phoneNeedsAttention = contact.phoneNeedsAttention
    if (!phoneNeedsAttention && contact.noShowCount <= 0) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (phoneNeedsAttention) {
            Icon(
                imageVector = AgoIcons.Exclamation,
                contentDescription = stringResource(R.string.bookings_contacts_phone_unverified_description),
                tint = agoStatusColors().warning,
                modifier = Modifier.size(18.dp),
            )
        }
        if (contact.noShowCount > 0) {
            NoShowPill(count = contact.noShowCount)
        }
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
