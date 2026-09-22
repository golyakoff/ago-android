package ago.chat.android.ui.components

import ago.chat.android.R
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * `26-13`'s own "a minimal connection-state surface" — a debug row, not a designed status indicator
 * (`docs/backlog/26-13-*.md`'s own Scope: "good enough to prove this item"). A real one is a later
 * item's job (`docs/architecture/realtime.md`'s own `ConnectionStateBadge` precedent, `ago-console`).
 *
 * A plain, stateless composable — [state] is threaded down from `SignInViewModel`'s own
 * `hubConnectionState` (itself only a relay onto the one `@Singleton`
 * `OperatorHubConnection.state`), the identical "screens observe a Flow, never own a connection" shape
 * every other consumer of that class follows. No `hiltViewModel()`/DI of its own — passing a plain
 * value down is simpler than a second `ViewModel` for one `StateFlow`, and keeps this composable
 * trivially previewable.
 */
@Composable
public fun HubConnectionDebugRow(
    state: OperatorHubConnectionState,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(colorFor(state)))
        Text(
            text = "${stringResource(R.string.hub_connection_label)}: ${labelFor(state)}",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun colorFor(state: OperatorHubConnectionState): Color =
    when (state) {
        OperatorHubConnectionState.Connected -> MaterialTheme.colorScheme.primary
        OperatorHubConnectionState.Connecting, OperatorHubConnectionState.Reconnecting -> MaterialTheme.colorScheme.tertiary
        OperatorHubConnectionState.Disconnected -> MaterialTheme.colorScheme.error
    }

@Composable
private fun labelFor(state: OperatorHubConnectionState): String =
    when (state) {
        OperatorHubConnectionState.Disconnected -> stringResource(R.string.hub_connection_disconnected)
        OperatorHubConnectionState.Connecting -> stringResource(R.string.hub_connection_connecting)
        OperatorHubConnectionState.Connected -> stringResource(R.string.hub_connection_connected)
        OperatorHubConnectionState.Reconnecting -> stringResource(R.string.hub_connection_reconnecting)
    }

@Preview(showBackground = true)
@Composable
private fun HubConnectionDebugRowPreview() {
    HubConnectionDebugRow(state = OperatorHubConnectionState.Connected)
}
