package ago.chat.android.ui.components

import ago.chat.android.ui.theme.AgoInkFaintDark
import ago.chat.android.ui.theme.AgoInkFaintLight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * `26-44`: the mockup's `.slabel` — the one heading style every screen that groups rows under a
 * heading uses (`docs/backlog/26-44-*.md`'s own quote of `tokens.css`):
 *
 * ```css
 * .slabel{
 *   font-size:11px; font-weight:800; letter-spacing:.1em; text-transform:uppercase; color:var(--ink-faint);
 *   padding:16px 16px 7px;
 * }
 * ```
 *
 * Small, very bold, letterspaced, uppercase, in the faintest ink the palette has — quiet on purpose,
 * so it organises without competing with the rows underneath it. `SettingsScreen` and `MoreScreen` each
 * drew a private, `labelLarge`/`primary`-coloured copy of this before this item — the loudest colour on
 * the screen for the text that is supposed to recede. This composable is the one place that mistake can
 * be made again, replacing both.
 *
 * **`11.sp` is not used.** This app's type scale bottoms out at `12.sp` (`ui/theme/Type.kt`'s own
 * `labelMedium`/`labelSmall`) and every size in this app is meant to be traceable to that scale — the
 * same rule `ConversationRowIdentityLine` states for the mockup's own `14.5px`. `labelMedium` supplies
 * the base size here; weight, tracking and case are layered on top of it rather than an untraceable
 * `11.sp` invented for this one call site.
 *
 * **Uppercase is applied here, not in `strings.xml`.** The mockup's `.slabel` reaches uppercase through
 * CSS `text-transform`, which leaves the *source* string in whatever case reads best as a sentence — so
 * the string resources this composable is given stay sentence case, and [text] is transformed the same
 * way at render time. Shouting in the resource file would make the source unreadable for the one thing
 * that ever reads it in sentence case: a screen reader, via TalkBack, which speaks the underlying string
 * rather than its rendered transform.
 *
 * **The colour is read directly from `ui/theme/`, not a `ColorScheme` role.** `docs/architecture.md`'s
 * own design-system section already names the pattern this follows: a handful of `tokens.css` tokens
 * have no honest Material 3 `ColorScheme` slot and are kept as named constants a call site reads
 * directly (`AgoLive`, read the same way by [ago.chat.android.ui.components.HubConnectionDot]) rather
 * than forced into a role that doesn't fit. `--ink-faint` is one more — every `ColorScheme` slot in
 * `Theme.kt` is already assigned to a different token (`onSurfaceVariant` is `--ink-soft`, a distinct,
 * louder value), so binding this one would mean overloading an existing role's meaning rather than
 * adding a missing one.
 *
 * **Dark/light is resolved from the active `ColorScheme`, not `isSystemInDarkTheme()`.** Unlike
 * `AgoLive` (one value in both themes), `--ink-faint` inverts between them, so *which* constant to read
 * still has to be decided somewhere. [ago.chat.android.MainActivity] already resolves
 * [ago.chat.android.ui.theme.ThemeMode] into an explicit `darkTheme` boolean before calling
 * [ago.chat.android.ui.theme.AgoChatTheme] — an operator who has picked "Светлая" against a dark system
 * setting is already looking at the light scheme. Calling `isSystemInDarkTheme()` again here would
 * silently re-ask the system instead of reading the theme actually in effect, so this composable checks
 * the *resolved* scheme's own background luminance instead — correct by construction for whichever
 * `ColorScheme` `MaterialTheme` is already carrying, with no second source of truth to drift out of sync
 * with the first.
 */
@Composable
public fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val inkFaint = if (isDark) AgoInkFaintDark else AgoInkFaintLight
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.1.em),
        color = inkFaint,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 7.dp),
    )
}
