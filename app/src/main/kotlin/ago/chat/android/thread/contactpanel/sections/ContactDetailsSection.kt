package ago.chat.android.thread.contactpanel.sections

import ago.chat.android.R
import ago.chat.android.core.domain.contactdetails.ContactDetail
import ago.chat.android.thread.contactpanel.ContactDetailsSectionState
import ago.chat.android.thread.contactpanel.RowRevealError
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * `26-148`: the first section of the contact-detail panel — «КОНТАКТНЫЕ ДАННЫЕ», the visitor's recorded
 * contact fields (Имя / Телефон / Почта). One stateless composable, its own file under
 * `thread/contactpanel/sections/`, called from [ago.chat.android.thread.contactpanel.ContactDetailPanel]'s
 * marked insertion point — the additive convention that container's own doc comment prescribes for
 * `26-148`…`26-153`, so nothing in the shell or the header is restructured to add it.
 *
 * ## Stateless by construction
 *
 * It reads a plain [ContactDetailsSectionState] and takes its two callbacks ([onReveal], [onRetry]) as
 * parameters — no view model, no Hilt — which is exactly what lets `ContactDetailsSectionTest` drive it
 * with a hand-built state and no component in play, the identical split
 * [ago.chat.android.thread.contactpanel.ContactDetailPanel] already draws for the shell.
 *
 * ## Reveal, reusing `26-115`'s idiom
 *
 * The «Показать» control is drawn **exactly when [ContactDetail.masked] is `true`** — never inferred from
 * the value's own shape, and never for a `Name` row (the client guarantees a name is never masked,
 * `ContactDetailsApi`'s own contract; design decision #1: the name is plain display text, no «не
 * подтверждено» caption, no validity pill). A masked row whose id is in
 * [ContactDetailsSectionState.Loaded.revealingIds] shows «Показ…» disabled while its one reveal is in
 * flight; the value is replaced in place by the server's unmasked response, and a refusal or transport
 * failure shows under that one row with the masked value left exactly as it was — the panel never unmasks
 * a value itself.
 *
 * ## Absent honestly
 *
 * A row whose value is blank renders «[VALUE_ABSENT]» — the same em-dash placeholder
 * [ago.chat.android.bookings.ConfirmedBookingsScreen] uses for an absent cell — rather than an empty gap.
 *
 * ## Why not the shared [ago.chat.android.ui.components.SectionLabel]
 *
 * That composable bakes in a 16dp horizontal gutter for the full-width list screens it was written for
 * (`26-44`); this section renders inside the panel's own [Column], which already applies a 20dp horizontal
 * gutter, so `SectionLabel` would stack to 36dp and push the heading out of line with both the header
 * above it and its own rows below. The heading here therefore reuses `SectionLabel`'s *typographic*
 * treatment (uppercase, extra-bold, letter-spaced, faint) at the panel's own gutter rather than its
 * padding — named per teaching mode rather than silently duplicated.
 */
@Composable
internal fun ContactDetailsSection(
    state: ContactDetailsSectionState,
    onReveal: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag(CONTACT_DETAILS_SECTION_TEST_TAG)) {
        SectionHeading(text = stringResource(R.string.contact_details_section_title))

        when (state) {
            ContactDetailsSectionState.Loading ->
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = RowSpacing).size(SpinnerSize),
                    strokeWidth = SpinnerStroke,
                )

            is ContactDetailsSectionState.Failed ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.contact_details_load_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetry) {
                        Text(text = stringResource(R.string.action_retry))
                    }
                }

            is ContactDetailsSectionState.Loaded ->
                if (state.details.isEmpty()) {
                    Text(
                        text = stringResource(R.string.contact_details_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RowSpacing),
                    )
                } else {
                    state.details.forEach { detail ->
                        ContactDetailRow(
                            detail = detail,
                            revealing = detail.id in state.revealingIds,
                            revealError = state.revealErrors[detail.id],
                            onReveal = { onReveal(detail.id) },
                        )
                    }
                }
        }
    }
}

/**
 * One contact-detail row: the field label over its value, with «Показать» on the value line for a masked
 * [ContactDetail] and any reveal error beneath. The name row falls through the same layout with no button
 * (it is never masked) and no caption — plain display text (design decision #1).
 */
@Composable
private fun ContactDetailRow(
    detail: ContactDetail,
    revealing: Boolean,
    revealError: RowRevealError?,
    onReveal: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = RowSpacing)) {
        Text(
            text = fieldLabel(detail.kind),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RowSpacing),
        ) {
            Text(
                text = detail.value.ifBlank { VALUE_ABSENT },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Driven strictly by `masked`, exactly as the calendar reveal is (`26-53`); a name row
            // (masked=false) never gets the control.
            if (detail.masked) {
                TextButton(onClick = onReveal, enabled = !revealing) {
                    Text(
                        text =
                            stringResource(
                                if (revealing) R.string.contact_details_revealing else R.string.contact_details_reveal,
                            ),
                    )
                }
            }
        }
        revealError?.let { error ->
            Text(
                text =
                    when (error) {
                        is RowRevealError.Refused -> error.detail
                        is RowRevealError.Failed -> stringResource(R.string.contact_details_reveal_failed)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The section heading, reusing [ago.chat.android.ui.components.SectionLabel]'s typographic treatment at
 * the panel's own gutter (see this file's doc comment on why the shared composable itself is not used). */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text.uppercase(),
        style =
            MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.1.em,
            ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Maps `Ago.Chat.Domain.VisitorContactDetailKind`'s own wire spelling ([ContactDetail.kind], unparsed)
 * to its localized field label. An unrecognised kind renders its raw wire spelling rather than being
 * dropped — the same "the classification lives in `:core:domain`, an unknown value is shown as-is, never
 * guessed at" discipline the state chip's own `StateChipWord` follows. */
@Composable
private fun fieldLabel(kind: String): String =
    when (kind) {
        "Name" -> stringResource(R.string.contact_details_label_name)
        "Phone" -> stringResource(R.string.contact_details_label_phone)
        "Email" -> stringResource(R.string.contact_details_label_email)
        else -> kind
    }

/** `26-148`: the section's root test tag - a stable hook for `ContactDetailsSectionTest` independent of
 * the (Russian, wording-sensitive) heading text, the same reasoning
 * [ago.chat.android.thread.contactpanel.CONTACT_PANEL_TEST_TAG]'s own doc comment gives. */
internal const val CONTACT_DETAILS_SECTION_TEST_TAG: String = "contactDetailsSection"

// The absent-value placeholder, a bare em-dash literal the same way `ConfirmedBookingsScreen` renders an
// absent cell - not a translatable string (a dash reads identically in every locale).
private const val VALUE_ABSENT = "—"

private val RowSpacing = 8.dp
private val SpinnerSize = 16.dp
private val SpinnerStroke = 2.dp
