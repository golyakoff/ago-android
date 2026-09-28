package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.ui.components.VisitorIdentityText
import ago.chat.android.ui.components.russianPluralStringResource
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
 */
@Composable
internal fun ContactsBody(
    state: ContactsUiState,
    onRetry: () -> Unit,
    onReveal: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
) {
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
                                ContactsList(contacts = visible, revealingCustomerIds = state.revealingCustomerIds, onReveal = onReveal)
                            }
                        }
                    }
            }
        }
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
    onReveal: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(contacts, key = { it.customerId }) { contact ->
            ContactCard(
                contact = contact,
                revealing = contact.customerId in revealingCustomerIds,
                onReveal = { onReveal(contact.customerId) },
            )
            HorizontalDivider()
        }
    }
}

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
 */
@Composable
private fun ContactCard(
    contact: Contact,
    revealing: Boolean,
    onReveal: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
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
 * third caller needs the identical thing). */
@Composable
private fun NoShowPill(count: Int) {
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
