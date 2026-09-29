package ago.chat.android.ui.components

import ago.chat.android.R
import ago.chat.android.core.domain.VisitorEmojiPair
import ago.chat.android.core.domain.shortId
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
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
 *
 * `26-279` (A9): [phone] is the client surfaces' own *further* fallback below the emoji pair — Клиенты's
 * list (`ContactsScreen.kt`) and detail header (`ClientDetailScreen.kt`) pass [Contact.phone][ago.chat.android.core.domain.bookings.Contact.phone]
 * here, since that field is never null for a customer, so a client with no chat identity at all shows a
 * real fact (the phone) plus a stated «Без имени» label ([NamelessClientIdentity]) rather than ever
 * falling through to [IdentifierText]'s raw id (the bug this item fixes: "a client with no chat name
 * currently renders the raw person-id as its title"). `null` (the default) preserves every other call
 * site's own existing bare-id fallback unchanged — `WorkerSlotsScreen`/`WorkerRecutScreen`/
 * `PhoneRevealsReportScreen`/`RestrictedVisitorsScreen` pass no phone and are out of this item's scope.
 */
@Composable
public fun VisitorIdentityText(
    id: String,
    emojiCreature: String?,
    emojiFood: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    phone: String? = null,
) {
    when (val fallback = resolveVisitorIdentityFallback(emojiCreature, emojiFood, phone)) {
        is VisitorIdentityFallback.EmojiPair ->
            Text(
                text =
                    stringResource(
                        R.string.visitor_label_emoji_and_short_id,
                        visitorEmojiPairName(fallback.pair),
                        shortId(id),
                    ),
                modifier = modifier,
                style = style,
            )

        is VisitorIdentityFallback.NoChatIdentity ->
            NamelessClientIdentity(phone = fallback.phone, modifier = modifier, titleStyle = style)

        VisitorIdentityFallback.BareId -> IdentifierText(id = id, modifier = modifier, style = style)
    }
}

/**
 * `26-279` (A9): the phone-as-title + «Без имени» arm — a client with a real phone on file but no chat
 * identity at all (never contacted through the widget, or a `26-268` manual client with no chat person
 * behind it). Two lines rather than one interpolated string, the identical "a name is prose, a fact is a
 * value" split [BookingIdentity][ago.chat.android.core.domain.bookings.BookingIdentity]'s own doc comment
 * draws for its own arms: the phone renders at the caller's own [titleStyle] so it reads exactly where a
 * name would have, the label beneath it in a smaller, muted style so it reads as a caption on that fact,
 * never a second title. Reuses [R.string.bookings_confirmed_identity_no_name] verbatim — the identical
 * «Без имени» text [ago.chat.android.bookings.bookingIdentityText]'s own `BookingIdentity.NoName` arm
 * already renders elsewhere in this app — rather than a near-duplicate resource for the same fact.
 */
@Composable
private fun NamelessClientIdentity(
    phone: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = LocalTextStyle.current,
) {
    Column(modifier = modifier) {
        Text(text = phone, style = titleStyle)
        Text(
            text = stringResource(R.string.bookings_confirmed_identity_no_name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/**
 * `26-279` (A9): [VisitorIdentityText]'s own three-arm fallback, encoded as data rather than the `if`/
 * `else if`/`else` this composable used to be — the identical "the row and the sheet cannot drift on this
 * rule" reasoning [BookingIdentity][ago.chat.android.core.domain.bookings.BookingIdentity]'s own doc
 * comment states for its own closed type. `sealed`, not a nullable pair of nullables, for the same reason
 * that one gives: two call sites branching on raw nullability independently is exactly how a third arm
 * (the bare id) would quietly stop being "always last resort" at one of them.
 */
internal sealed interface VisitorIdentityFallback {
    data class EmojiPair(
        val pair: VisitorEmojiPair,
    ) : VisitorIdentityFallback

    data class NoChatIdentity(
        val phone: String,
    ) : VisitorIdentityFallback

    data object BareId : VisitorIdentityFallback
}

/**
 * [VisitorIdentityFallback]'s own resolution — a plain function so a JVM `test` can drive the fallback
 * order directly, the identical split [resolvedVisitorEmojiPair] already establishes. The emoji pair
 * always wins when present (unchanged from before this item); [phone] is consulted only once the pair is
 * absent, and only when the caller actually supplied one — a `null` [phone] (every call site this item did
 * not touch) still falls all the way through to [VisitorIdentityFallback.BareId], preserving their
 * existing behaviour exactly.
 */
internal fun resolveVisitorIdentityFallback(
    emojiCreature: String?,
    emojiFood: String?,
    phone: String?,
): VisitorIdentityFallback {
    val pair = resolvedVisitorEmojiPair(emojiCreature, emojiFood)
    return when {
        pair != null -> VisitorIdentityFallback.EmojiPair(pair)
        phone != null -> VisitorIdentityFallback.NoChatIdentity(phone)
        else -> VisitorIdentityFallback.BareId
    }
}
