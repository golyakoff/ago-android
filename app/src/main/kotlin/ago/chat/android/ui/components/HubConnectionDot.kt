package ago.chat.android.ui.components

import ago.chat.android.R
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.ui.theme.AgoLive
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * `26-32`: the connection state, costing no line of its own.
 *
 * This replaces `HubConnectionDebugRow`, which was never meant to survive — `26-13` called it "a
 * minimal connection-state surface... good enough to prove this item" in its own doc comment, and said
 * a designed one was a later item's job. It drew a dot *and* the words «Соединение: Подключено» on a
 * full-width row of their own, above the conversation list and above the thread. The author's
 * complaint is exactly the cost/benefit: a whole row of vertical space, on the two screens where
 * space is scarcest, to carry what is very nearly one bit.
 *
 * So the dot stays and the row goes. The dot is not new — it is the same 8dp circle that row already
 * drew, lifted out of it and placed in the app bar, where it costs nothing.
 *
 * ## Why the colours are these colours
 *
 * `AgoLive` (`ui/theme/Color.kt`, from the brand book's own `tokens.css` §Status) is this product's
 * status green, and its own comment there says what it is for: "Decorative dot only, in both themes —
 * never wired into a `ColorScheme` role". That is this call site precisely, which is why `Connected`
 * reads green here rather than `primary`. The old row used `primary`, which in this app's scheme is
 * the brand violet — a violet dot does not read as "online" to anyone, and the author asked for
 * «зелёная|красная точка» in so many words.
 *
 * The other two mappings are unchanged from the row this replaces: `error` for `Disconnected`, and
 * `tertiary` for the two in-between states. Four states, three colours — deliberately, because
 * `Connecting` and `Reconnecting` are the same thing to a person looking at a dot ("it is trying"),
 * and they are told apart in the spoken description below rather than by a fourth colour nobody could
 * name.
 *
 * ## Why it still says something out loud
 *
 * A coloured circle with no text is invisible to a screen reader and meaningless to anyone who cannot
 * separate this green from this red. The `hub_connection_*` strings the old row printed on screen are
 * kept for exactly that and are now spoken rather than drawn — which is why this file did not delete
 * them.
 *
 * ## Why the state is still a plain parameter
 *
 * Unchanged from the row this replaces: [state] is threaded down from the one `@Singleton`
 * `OperatorHubConnection.state`, and this composable owns no connection and no `ViewModel` of its own.
 */
@Composable
public fun HubConnectionDot(
    state: OperatorHubConnectionState,
    modifier: Modifier = Modifier,
) {
    val description = "${stringResource(R.string.hub_connection_label)}: ${labelFor(state)}"
    Box(
        modifier =
            modifier
                .size(DotSize)
                .clip(CircleShape)
                .background(colorFor(state))
                .semantics { contentDescription = description },
    )
}

/** The size the retired `HubConnectionDebugRow` already drew, carried over rather than re-chosen. */
private val DotSize = 8.dp

// `26-77`: `internal` rather than `private` - [ago.chat.android.ui.components.AccountAvatarAction]'s
// own presence dot reads both of these directly, so the account menu's dot and this file's own dot
// can never silently drift onto two different colour/wording rules for the identical
// [OperatorHubConnectionState].
@Composable
internal fun colorFor(state: OperatorHubConnectionState): Color =
    when (state) {
        OperatorHubConnectionState.Connected -> AgoLive
        OperatorHubConnectionState.Connecting, OperatorHubConnectionState.Reconnecting -> MaterialTheme.colorScheme.tertiary
        OperatorHubConnectionState.Disconnected -> MaterialTheme.colorScheme.error
    }

@Composable
internal fun labelFor(state: OperatorHubConnectionState): String =
    when (state) {
        OperatorHubConnectionState.Disconnected -> stringResource(R.string.hub_connection_disconnected)
        OperatorHubConnectionState.Connecting -> stringResource(R.string.hub_connection_connecting)
        OperatorHubConnectionState.Connected -> stringResource(R.string.hub_connection_connected)
        OperatorHubConnectionState.Reconnecting -> stringResource(R.string.hub_connection_reconnecting)
    }

@Preview(showBackground = true)
@Composable
private fun HubConnectionDotPreview() {
    HubConnectionDot(state = OperatorHubConnectionState.Connected)
}
