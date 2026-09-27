package ago.chat.android.ui.components

import ago.chat.android.R
import ago.chat.android.core.domain.VisitorEmojiPair
import ago.chat.android.core.domain.shortId
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle

/**
 * `26-203`: the booking vertical's own standard visitor label — «{creature} · {food} (shortid)», e.g.
 * «Сова · Клубника (a0f3c952)», the identical format
 * [ago.chat.android.restrictions.RestrictedVisitorsScreen]'s own `restrictedVisitorLabel` already renders
 * for the tenant oversight screen, read onto every booking-vertical call site that used to fall straight
 * back to a bare [IdentifierText] the moment it had no display name for a visitor: `ContactsScreen`,
 * `WorkerSlotsScreen`, `WorkerRecutScreen`, `PhoneRevealsReportScreen`.
 *
 * **[emojiCreature]/[emojiFood] must be read off the row, never hash-derived from [id].** The pair is
 * assigned once, at first contact, and stored on the visitor
 * (`reference_visitor_emoji_pair_is_stored`) — every call site here gets both fields from a client-side
 * merge against chat's own person registry ([ago.chat.android.core.domain.persons.PersonsApi], the
 * identical `26-162`/`adr/0184` display-merge [ago.chat.android.bookings.ContactsViewModel] already
 * performs for a real name), never computed fresh from the id.
 *
 * A `@Composable` rather than a plain `String`-returning function, unlike [visitorEmojiPairNameText]:
 * the two arms render in two genuinely different styles — prose for a resolved pair, [IdentifierText]'s
 * own monospace face for a bare id — so this is also the one place [IdentifierText]'s own doc comment
 * still holds ("every screen renders an identifier through this composable, never by calling `shortId` at
 * its own call site") once a second arm exists beside it.
 */
@Composable
public fun VisitorIdentityText(
    id: String,
    emojiCreature: String?,
    emojiFood: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val pair = resolvedVisitorEmojiPair(emojiCreature, emojiFood)
    if (pair != null) {
        Text(
            text = stringResource(R.string.visitor_label_emoji_and_short_id, visitorEmojiPairName(pair), shortId(id)),
            modifier = modifier,
            style = style,
        )
    } else {
        IdentifierText(id = id, modifier = modifier, style = style)
    }
}

/**
 * The plain decision behind [VisitorIdentityText], pulled out so a plain JVM `test` can assert it without
 * a composition host — the identical split [visitorEmojiPairNameText] already establishes for
 * [visitorEmojiPairName]. Both fields must be present to render the pair: `26-202`'s own fields are
 * additive together on every row this app reads them from, never one without the other, so a lone
 * survivor (a partially-backfilled row, or a caller's own bug) is treated the same as neither — the bare
 * id, not a half-formed label.
 */
internal fun resolvedVisitorEmojiPair(
    emojiCreature: String?,
    emojiFood: String?,
): VisitorEmojiPair? = if (emojiCreature != null && emojiFood != null) VisitorEmojiPair(emojiCreature, emojiFood) else null
