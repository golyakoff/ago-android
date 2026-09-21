package ago.chat.android.ui.components

import ago.chat.android.core.domain.shortId
import ago.chat.android.ui.theme.AgoFontMono
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/**
 * The one place `shortId` (`:core:domain`) is turned into UI — every screen renders an identifier
 * through this composable, never by calling `shortId`/`.take(8)` at its own call site
 * (`ago-android/docs/architecture.md`, "How an identifier is rendered": `visitorId`, `operatorId`,
 * `calendarId`, `conversationId`, `siteId`, `customerId` alike).
 *
 * The truncation rule itself lives in `:core:domain` (`shortId`) rather than here, because it needs no
 * Android/Compose dependency to express — a plain `String -> String` function — and putting it only
 * behind a `@Composable` would mean a non-UI rule could be reached solely through UI code, the same
 * "logic where it needs no framework, rendering where it does" split `ago-android/docs/architecture.md`
 * already draws for the module boundary itself. This composable does exactly one more thing than call
 * that function: render the result in the product's monospace face, because an id is a value someone
 * reads character by character or dictates aloud, never prose.
 */
@Composable
public fun IdentifierText(
    id: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    Text(
        text = shortId(id),
        modifier = modifier,
        style = style.copy(fontFamily = AgoFontMono),
    )
}
