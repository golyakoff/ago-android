package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.ui.components.IdentifierText
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
 * [ConfirmedBookingsBody]/[BookingsScreen]'s own `Pending` branch already draw for their own state.
 *
 * `26-53`: no longer strictly read-only — [onReveal] is the one write this screen now offers. The
 * `Column`/`Box(weight)` wrapper and the [ActionErrorBanner] placement are the identical shape
 * [BookingsScreen]'s own `Pending` branch already establishes for its own veto-write errors, restated
 * here for the same reason: a refusal is shown above the list, never in place of it.
 */
@Composable
internal fun ContactsBody(
    state: ContactsUiState,
    onRetry: () -> Unit,
    onReveal: (String) -> Unit,
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
                        ContactsList(contacts = state.contacts, revealingCustomerIds = state.revealingCustomerIds, onReveal = onReveal)
                    }
            }
        }
    }
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
 * A customer with no [Contact.displayName] renders through [IdentifierText] — never a raw GUID, never
 * an invented label (`ui/components/IdentifierText.kt`'s own doc comment,
 * `docs/backlog/26-52-*.md`'s own Done-when).
 *
 * [Contact.phoneVerifiedAt] and [Contact.phoneConfirmedByOperatorAt] each get their own line, in that
 * fixed order — **never** collapsed into one "verified" line, no matter how tempting that is on a
 * phone-width card (`Contact`'s own doc comment; `docs/backlog/26-52-*.md`'s own Scope item 3, and the
 * identical rule `scope-inventory.md` §9 states for `/account/ai`'s three controls).
 *
 * `26-53`: the phone row now draws Показать exactly when [Contact.masked] says so — never inferred from
 * the string's own shape (`ago-console`'s own `renderPhone` doc comment states the identical rule this
 * mirrors). [revealing] disables the control while this customer's own reveal is in flight, and swaps
 * its label to say so, the identical `RevealControl`/`revealing` shape `renderPhone` already draws.
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
            IdentifierText(
                id = contact.customerId,
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
        Text(
            text = phoneVerifiedLine(contact.phoneVerifiedAt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = phoneConfirmedLine(contact.phoneConfirmedByOperatorAt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.bookings_contacts_noshow_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = contact.noShowCount.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

/** "Proven reachable by an SMS code" - [Contact.phoneVerifiedAt]'s own doc comment. A `null` is a
 * stated "not yet", never a blank line. */
@Composable
private fun phoneVerifiedLine(phoneVerifiedAt: String?): String =
    stringResource(
        if (phoneVerifiedAt != null) R.string.bookings_contacts_phone_verified_yes else R.string.bookings_contacts_phone_verified_no,
    )

/** "An operator called and it is them" - [Contact.phoneConfirmedByOperatorAt]'s own doc comment, a
 * weaker, human-asserted fact rendered on its own line, never folded into [phoneVerifiedLine]'s. */
@Composable
private fun phoneConfirmedLine(phoneConfirmedByOperatorAt: String?): String =
    stringResource(
        if (phoneConfirmedByOperatorAt != null) {
            R.string.bookings_contacts_phone_confirmed_yes
        } else {
            R.string.bookings_contacts_phone_confirmed_no
        },
    )
