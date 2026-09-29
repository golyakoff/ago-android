package ago.chat.android.ui.components

import ago.chat.android.core.domain.visitorEmojiPair
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * `26-23`: the visitor's emoji identity as the approved mockup composes it — the creature centred in a
 * circular brand tint, the food emoji floating over the circle's bottom-right edge with **no background
 * of its own**. The mockup's own CSS carries the reason this is a separate composable rather than a
 * tweak to the old inline emoji-pair glyph: "`25-207`: badge composition, not a side-by-side pair - the
 * creature alone, centered, larger than the old squeezed-pair glyph". `26-40` retired that old inline
 * pair's one remaining call site (the thread screen's app-bar title, which draws no emoji at all any
 * more — that item's own Scope: "the mockup's own title is plain text") — this composable is unaffected,
 * since it never drew that pair itself.
 *
 * **Absence is absence, never a blank circle.** The presence rule is not restated here: it is
 * [visitorEmojiPair] (`:core:domain`), the same function every other caller of the emoji pair reads
 * through, which treats a half-present pair exactly like a missing one ("never half a badge", that
 * function's own doc comment). When it answers `null` — a visitor predating the emoji-pair column —
 * this composable draws
 * *nothing at all*, rather than an empty tinted circle. An empty circle would be the "leading-space
 * artifact"/"blank placeholder" that `visitorDisplayPrefixText`'s own doc comment rejects for the text
 * form, drawn in pixels instead of characters; the row simply starts at its text, the way the console's
 * own pair-less row renders "the short code alone".
 *
 * Every dimension below is the mockup's own `.av`/`.av-food` rule, transcribed rather than chosen —
 * which is why they are named constants with the CSS beside them, not literals inline.
 *
 * `26-269`: [diameter] is an optional override, defaulted to [AvatarDiameter] so every existing call
 * site above (40dp, unparametrised) keeps compiling and rendering pixel-identically. Клиенты's own
 * client-detail hub (`ClientDetailScreen.kt`) is the first caller to pass a different value — 42dp on
 * the list row, 48dp on the header, the mockup's own two distinct `.av` sizes for that screen — so the
 * creature/food font sizes and the food badge's own overhang scale proportionally with it rather than
 * staying pinned to the 40dp figures they were tuned for.
 *
 * `26-64`: `clearAndSetSemantics {}` below is [ui.components.HubConnectionDot]'s own rule, applied in the
 * opposite direction. That composable's own doc comment states the rule this app otherwise follows: "a
 * coloured circle with no text is invisible to a screen reader... it is decorative here by construction:
 * the same pair is already spoken as a name" — right after this avatar, on
 * `ConversationListScreen.kt`'s own identity line, which reads
 * [ago.chat.android.core.domain.VisitorDisplayPrefixParts.displayName]. Without this, TalkBack read the
 * two raw emoji characters below as *glyph names* ("Fox face. Tangerine.") — in whichever language the
 * TTS engine happens to run, not necessarily Russian — once for every row, right before repeating the
 * same identity as a name. `clearAndSetSemantics {}` (empty) drops this composable's own semantics and
 * every descendant's, which is the ordinary Compose shape for "this subtree is decorative" — distinct
 * from the row-level `mergeDescendants = true` in `ConversationRow`, which *folds* descendants into one
 * description rather than silencing them; this avatar wants silence, not folding, because folding it in
 * would still speak the glyph names as part of the merged sentence.
 *
 * `26-285`: reads [LocalVisitorAvatarStyle] and, while it is [VisitorAvatarStyle.Initials] *and* the
 * pair's localized names both resolve ([visitorEmojiInitials]), draws an initials circle instead of the
 * badge above — the exact circle [ago.chat.android.bookings.ClientAvatar] and
 * [AccountAvatarAction]'s own `AvatarCircle` already draw for a named person
 * (`primaryContainer`/`onPrimaryContainer`, `titleSmall` bold, a fixed size rather than one scaled by
 * [diameter] the way the emoji glyphs above are), so a visitor rendered in Initials mode and a named
 * client render as one visual family. An unresolved name (`null`) falls back to the emoji badge for
 * that one avatar rather than risk [visitorEmojiInitials]'s own documented broken-surrogate hazard — see
 * that function's own doc comment. This toggle governs the anonymous emoji-pair avatar only:
 * [ago.chat.android.bookings.ClientAvatar]'s named-person arm calls [initialsFor] directly and never
 * reaches this composable at all, so a real name's own initials are unaffected by either style.
 */
@Composable
public fun VisitorAvatar(
    emojiCreature: String?,
    emojiFood: String?,
    modifier: Modifier = Modifier,
    diameter: Dp = AvatarDiameter,
) {
    val pair = visitorEmojiPair(emojiCreature, emojiFood) ?: return
    val style = LocalVisitorAvatarStyle.current

    if (style == VisitorAvatarStyle.Initials) {
        val initials = visitorEmojiInitials(pair)
        if (initials != null) {
            Box(
                modifier =
                    modifier
                        .size(diameter)
                        .clearAndSetSemantics {}
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            return
        }
    }

    val creatureFontSize = (diameter.value * CREATURE_FONT_SIZE_RATIO).sp
    val foodFontSize = (diameter.value * FOOD_FONT_SIZE_RATIO).sp
    val foodBadgeOverhang = diameter * FOOD_BADGE_OVERHANG_RATIO

    Box(modifier = modifier.size(diameter).clearAndSetSemantics {}) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = pair.creature,
                style = TextStyle(fontSize = creatureFontSize, lineHeight = creatureFontSize, textAlign = TextAlign.Center),
            )
        }
        // `right:-4px; bottom:-4px` — anchored to the corner and then pushed *past* it, so the badge
        // overhangs the circle's edge rather than sitting inscribed inside it. The parent `Box` does
        // not clip, which is what lets the overhang actually draw.
        Text(
            text = pair.food,
            style = TextStyle(fontSize = foodFontSize, lineHeight = foodFontSize),
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = foodBadgeOverhang, y = foodBadgeOverhang),
        )
    }
}

// `.av{width:40px; height:40px; border-radius:50%; background:var(--brand-tint)}`
private val AvatarDiameter = 40.dp

// `.av.pair{font-size:28px; line-height:1}` — the mockup's own comment records 28px as the author's
// "explicit final size, settled live after two earlier percentage-based passes", so it is not rounded
// to the nearest `MaterialTheme.typography` role the way ordinary prose sizes in this app are. Kept as a
// ratio against [AvatarDiameter] (28/40) rather than a bare constant now that `26-269` lets a caller pass
// a different [Dp] — the default caller's own math (`40 * 0.7 = 28`) reproduces the original literal
// exactly, so nothing changes for it.
private const val CREATURE_FONT_SIZE_RATIO = 28f / 40f

// `.av-food{font-size:16px; line-height:1}` — see [CREATURE_FONT_SIZE_RATIO]'s own doc comment.
private const val FOOD_FONT_SIZE_RATIO = 16f / 40f

// `.av-food{right:-4px; bottom:-4px}` — see [CREATURE_FONT_SIZE_RATIO]'s own doc comment.
private const val FOOD_BADGE_OVERHANG_RATIO = 4f / 40f
