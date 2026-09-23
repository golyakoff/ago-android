package ago.chat.android.ui.components

import ago.chat.android.R
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * `26-77`: the account menu — mockup section "08 · Аккаунт и шапка" — replacing the ad-hoc
 * `HubConnectionDot` + kebab pair every top-level screen drew its own version of
 * (`docs/backlog/26-77-*.md`'s own Found table). Lives beside [HubConnectionDot], which this does not
 * replace: [ThreadScreen][ago.chat.android.thread.ThreadScreen]'s drill-in header is explicitly out of
 * this item's scope and keeps drawing the bare dot exactly as it always has.
 *
 * **Self-contained, the same shape [ConversationListOverflowMenu][ago.chat.android.conversations.ConversationListScreen]'s
 * now-retired private composable already was**: `expanded` is owned here, not hoisted, because no
 * caller has ever needed to know whether this menu is open — the same reasoning that let that private
 * composable own its own `remember { mutableStateOf(false) }` instead of taking `expanded`/`onExpandedChange`
 * parameters nobody would have read.
 *
 * **Two independent accessibility nodes, not one merged sentence.** This composable deliberately does
 * *not* set `Modifier.semantics(mergeDescendants = true)` anywhere: the avatar's own clickable region
 * carries [R.string.account_menu_open_action] and the presence dot inside it carries its own
 * `"<label>: <state>"` description exactly as [HubConnectionDot] always has — the identical pair of
 * independently-announced facts a screen reader could already reach on every screen this replaces (the
 * dot, and the kebab/nothing beside it), now drawn as one visual unit instead of two.
 *
 * @param displayName the signed-in operator's own name, from [ago.chat.android.session.OperatorIdentity] —
 * `null`/blank renders the honest fallbacks this file's own [initialsFor] and
 * [R.string.account_menu_unknown_name] state, never a blank circle or a blank header line
 * (`docs/backlog/26-77-*.md`'s own Scope: "empty/unset name falls back to something honest").
 * @param email the same identity's email/username — `null`/blank simply omits that header line, the
 * "never invented, rendered honestly" rule every optional line in this app already follows
 * ([ConversationRowSnippetLine][ago.chat.android.conversations.ConversationListScreen]'s own doc
 * comment states it first).
 */
@Composable
public fun AccountAvatarAction(
    displayName: String?,
    email: String?,
    hubConnectionState: OperatorHubConnectionState,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val fallbackInitial = stringResource(R.string.account_menu_fallback_initial)
    val initials = remember(displayName, fallbackInitial) { initialsFor(displayName, fallbackInitial) }
    val resolvedName = displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.account_menu_unknown_name)
    val dotColor = colorFor(hubConnectionState)
    val dotDescription = "${stringResource(R.string.hub_connection_label)}: ${labelFor(hubConnectionState)}"
    val openMenuLabel = stringResource(R.string.account_menu_open_action)

    Box(modifier = modifier) {
        PresenceAvatar(
            initials = initials,
            dotColor = dotColor,
            dotDescription = dotDescription,
            avatarSize = AvatarSize,
            avatarModifier =
                Modifier
                    .clickable(onClickLabel = openMenuLabel) { expanded = true }
                    .semantics { contentDescription = openMenuLabel },
        )

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // `26-77` follow-up, 2026-09-23, both from the author's own live-device review: the header
            // row's own avatar shrank to [AvatarSize] - the same circle size as the trigger just
            // tapped, not a second, larger drawing of it - and it carries no presence dot of its own.
            // The trigger directly above already showed the dot once; this row's own job is the name
            // and the email, not a second announcement of the same online/offline fact.
            Row(
                modifier = Modifier.widthIn(min = HeaderMinWidth).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarCircle(initials = initials, size = AvatarSize)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = resolvedName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!email.isNullOrBlank()) {
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            HorizontalDivider()
            // `docs/backlog/26-77-*.md`'s own Scope item 3: a chevron, opening the existing Settings
            // screen - never a second sign-out pathway, never a colour of its own.
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.more_settings_row)) },
                trailingIcon = { Icon(imageVector = AgoIcons.ChevronRight, contentDescription = null) },
                onClick = {
                    // Closed before the callback, not after - `ConversationListOverflowMenu`'s own
                    // doc comment on why (a `setExpanded` landing on a torn-down composition).
                    expanded = false
                    onOpenSettings()
                },
            )
            HorizontalDivider()
            // Decided 2026-09-23 (`docs/backlog/26-77-*.md`'s own Scope item 3): plain styling, the
            // identical color `DropdownMenuItem` already gives Настройки above - no `error`/danger
            // tint. Sign-out is an ordinary, fully reversible action here, not one this menu marks as
            // destructive.
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.action_sign_out)) },
                onClick = {
                    expanded = false
                    onSignOut()
                },
            )
        }
    }
}

/**
 * The avatar circle and its overlapping presence dot, with no click handling of its own — the
 * app-bar trigger wraps [avatarModifier] with its own `clickable`/`semantics` above; the menu header
 * reaches for the bare [AvatarCircle] beneath this instead, since it draws no dot of its own at all
 * (`26-77` follow-up, 2026-09-23).
 *
 * **The outer [Box] carries no [clip] of its own, on purpose.** It used to — clipped to
 * [CircleShape] at [avatarSize] — which silently cut off the presence dot below, since the dot is a
 * *sibling* deliberately positioned to overflow past that same [avatarSize] box (`.align(BottomEnd)`
 * plus a positive [DotRingInset] offset), and a circular clip removes exactly a shape's own corners —
 * precisely where the dot sits. Found live, on a real device, by the author. [clip] now lives only on
 * [AvatarCircle] itself, sized to match, so the *avatar's* own corners still round correctly (and its
 * ripple, via [avatarModifier], still bounds to that same circle) while the dot's overflow is never
 * touched by a clip meant for a different child.
 */
@Composable
private fun PresenceAvatar(
    initials: String,
    dotColor: Color,
    dotDescription: String,
    avatarSize: Dp,
    avatarModifier: Modifier = Modifier,
) {
    Box(modifier = Modifier.size(avatarSize), contentAlignment = Alignment.Center) {
        AvatarCircle(initials = initials, size = avatarSize, modifier = avatarModifier)

        // The presence dot overlapping the avatar's own bottom-right corner
        // (`docs/backlog/26-77-*.md`'s own Scope item 2) - a small ring in the surrounding surface
        // colour first, so the dot itself never touches the avatar's fill directly, then the coloured
        // dot with its own soft-coloured glow ([androidx.compose.ui.draw.shadow]'s `ambientColor`/
        // `spotColor`, the same hue as the dot rather than Compose's default black shadow).
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = DotRingInset, y = DotRingInset)
                    .size(DotRingSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(DotSize)
                        .shadow(elevation = DotGlowElevation, shape = CircleShape, ambientColor = dotColor, spotColor = dotColor)
                        .clip(CircleShape)
                        .background(dotColor)
                        .semantics { contentDescription = dotDescription },
            )
        }
    }
}

/**
 * Just the circle and its initials, no presence dot — [PresenceAvatar] draws one of these and then
 * overlays the dot on top; the menu's own header row (`26-77` follow-up) draws this directly, since a
 * dot repeating the trigger's own already-announced state a few pixels below it is noise, not news.
 */
@Composable
private fun AvatarCircle(
    initials: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
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

/**
 * `docs/backlog/26-77-*.md`'s own derivation rule, settled here rather than guessed in the backlog
 * item itself: the first letter of the first two space-separated words ("Андрей Голяков" → "АГ"); a
 * single word uses [String.take] of 2 (naturally clamping to 1 for a one-character word, so "Ы" stays
 * "Ы" rather than throwing); a blank/absent name uses [fallback] — a single visible glyph, never a
 * blank circle. `String.uppercase()` with no `Locale` argument is Kotlin's own locale-invariant
 * uppercasing, the right choice for a name that could be Cyrillic or Latin and must never depend on the
 * device's own configured locale to render correctly (`docs/conventions/date-and-time.md`'s own
 * "never depend on ambient state" reasoning, restated for casing rather than time).
 */
internal fun initialsFor(
    displayName: String?,
    fallback: String,
): String {
    val words =
        displayName
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            .orEmpty()
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
        words.size == 1 -> words[0].take(2).uppercase()
        else -> fallback
    }
}

// `.avatar{width:36px;height:36px}` - the mockup's own app-bar avatar size, and (`26-77` follow-up,
// 2026-09-23) the menu header's own avatar too: the author's own live-device correction to this
// file's first pass, which drew the header's copy visibly larger.
private val AvatarSize = 36.dp
private val HeaderMinWidth = 220.dp

// The presence dot: a small ring in the surface colour, then the coloured dot itself, inset toward the
// avatar's own bottom-right corner rather than sitting flush with it - `HubConnectionDot`'s own 8dp
// carried over for the dot itself, sized down slightly against `AvatarSize` so it still reads as "on
// the corner" rather than "half the avatar".
private val DotSize = 10.dp
private val DotRingSize = 14.dp
private val DotRingInset = 2.dp
private val DotGlowElevation = 6.dp
