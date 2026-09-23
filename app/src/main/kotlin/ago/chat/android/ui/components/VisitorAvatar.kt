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
import androidx.compose.ui.text.style.TextAlign
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
 */
@Composable
public fun VisitorAvatar(
    emojiCreature: String?,
    emojiFood: String?,
    modifier: Modifier = Modifier,
) {
    val pair = visitorEmojiPair(emojiCreature, emojiFood) ?: return

    Box(modifier = modifier.size(AvatarDiameter).clearAndSetSemantics {}) {
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
                style = TextStyle(fontSize = CreatureFontSize, lineHeight = CreatureFontSize, textAlign = TextAlign.Center),
            )
        }
        // `right:-4px; bottom:-4px` — anchored to the corner and then pushed *past* it, so the badge
        // overhangs the circle's edge rather than sitting inscribed inside it. The parent `Box` does
        // not clip, which is what lets the overhang actually draw.
        Text(
            text = pair.food,
            style = TextStyle(fontSize = FoodFontSize, lineHeight = FoodFontSize),
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = FoodBadgeOverhang, y = FoodBadgeOverhang),
        )
    }
}

// `.av{width:40px; height:40px; border-radius:50%; background:var(--brand-tint)}`
private val AvatarDiameter = 40.dp

// `.av.pair{font-size:28px; line-height:1}` — the mockup's own comment records 28px as the author's
// "explicit final size, settled live after two earlier percentage-based passes", so it is not rounded
// to the nearest `MaterialTheme.typography` role the way ordinary prose sizes in this app are.
private val CreatureFontSize = 28.sp

// `.av-food{font-size:16px; line-height:1}`
private val FoodFontSize = 16.sp

// `.av-food{right:-4px; bottom:-4px}`
private val FoodBadgeOverhang = 4.dp
