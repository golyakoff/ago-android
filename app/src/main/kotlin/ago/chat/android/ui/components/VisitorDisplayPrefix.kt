package ago.chat.android.ui.components

import ago.chat.android.core.domain.visitorDisplayPrefixParts
import ago.chat.android.ui.theme.AgoFontSans
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The composite `{emojiCreature}{emojiFood} {visitorName?} {visitorId.slice(0, 8)}`
 * (`ago-android/docs/architecture.md`, "How an identifier is rendered") — the same three-part string
 * `ago-console/src/workspace/visitorEmoji.ts`'s `visitorDisplayPrefix` builds, with the short id this
 * composable appends itself via `IdentifierText`, matching the console's own two real call sites
 * (`ConversationList.tsx`, `ConversationPage.tsx`), each of which appends `visitorId.slice(0, 8)` after
 * that function's own return value.
 *
 * Each part is genuinely absent, not blank, when unknown — the exact console behaviour
 * (`ago-console/src/workspace/visitorEmoji.ts`'s own doc comment: a visitor predating the emoji-pair
 * column "render[s] as the short code alone", and "no case joins the emoji pair's own absence with a
 * present name into a leading-space artifact"). `visitorDisplayPrefixParts` (`:core:domain`) is the
 * logic that decides what is present and already covers that rule (proved by
 * `VisitorDisplayPrefixTest`, `:core:domain`); this composable only decides how to lay the present
 * parts out — the emoji pair at its own, deliberately larger size, the same treatment `25-207`/`25-162`
 * already give it on the web (`VisitorAvatar.tsx`, `visitorNameSuffix`).
 *
 * `26-30`: renders [ago.chat.android.core.domain.VisitorDisplayPrefixParts.displayName], not
 * `.visitorName` — a nameless visitor with a known emoji pair now reads as "Лиса · Апельсин" here too,
 * the same fallback the conversation-list row's identity line gained, for free, because both read the
 * identical `:core:domain` field rather than each deriving it separately.
 */
@Composable
public fun VisitorDisplayPrefix(
    emojiCreature: String?,
    emojiFood: String?,
    visitorName: String?,
    visitorId: String,
    modifier: Modifier = Modifier,
) {
    val parts = visitorDisplayPrefixParts(emojiCreature, emojiFood, visitorName, visitorId)
    val baseStyle = LocalTextStyle.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        parts.emoji?.let { pair ->
            Text(
                text = "${pair.creature}${pair.food}",
                style = baseStyle.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize),
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        parts.displayName?.let { name ->
            Text(
                text = name,
                style = baseStyle.copy(fontFamily = AgoFontSans),
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        IdentifierText(id = parts.visitorId, style = baseStyle)
    }
}
