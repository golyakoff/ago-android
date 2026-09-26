package ago.chat.android.shell

import ago.chat.android.R
import ago.chat.android.devices.openAutostartSettings
import ago.chat.android.devices.openBatteryOptimizationSettings
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * `26-128`: the first-launch prompt that replaces the raw system battery dialog — see
 * [BatteryAwarenessViewModel]'s own doc comment for why its state is a single "have I been dismissed
 * with the checkbox checked" question, never a live system reading. Composed once, at the top of
 * [ago.chat.android.MainActivity]'s own `setContent`, over [ago.chat.android.signin.SignInHost] — not
 * inside [AppShellScreen] or any one of its tabs, since the sheet's own "once, ever, until dismissed"
 * contract has nothing to do with which tab happens to be current, and wiring it through
 * [AppShellScreen]'s already-long parameter list (five Hilt-avoidance slots, each with its own
 * back-contract test substituting a trivial default) would risk exactly the kind of collateral breakage
 * this item has no reason to cause.
 */
@Composable
internal fun BatteryAwarenessRoute(viewModel: BatteryAwarenessViewModel = hiltViewModel()) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    if (!visible) return

    val context = LocalContext.current
    BatteryAwarenessSheet(
        onOpenBatterySettings = { openBatteryOptimizationSettings(context) },
        onOpenAutostartSettings = { openAutostartSettings(context, viewModel.autostartTarget) },
        onDismiss = viewModel::dismiss,
    )
}

/**
 * The stateless half — every Compose preview and future UI test targets this function directly, the
 * identical "route wires, screen renders" split every other screen in this app already follows.
 *
 * **Non-blocking by construction, not by convention.** [onDismiss] is the *only* way this composable ever
 * stops rendering — a swipe, a tap outside, the system back button and both buttons below all route
 * through [androidx.compose.material3.ModalBottomSheet]'s own `onDismissRequest`, so there is no code path
 * left in this file that could gate anything on an operator's choice here; the two buttons open a system
 * screen and leave the sheet exactly as visible as it was; only closing it acts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatteryAwarenessSheet(
    onOpenBatterySettings: () -> Unit,
    onOpenAutostartSettings: () -> Unit,
    onDismiss: (dontShowAgain: Boolean) -> Unit,
) {
    var dontShowAgain by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { onDismiss(dontShowAgain) },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                // `26-184`: the warning *triangle* `26-128` round 3 kept here (while every *circular*
                // status badge on the Settings rows below moved to `AgoIcons.Exclamation`) is retired in
                // favour of the same flat `AgoIcons.ErrorCircle` those rows now use (`StatusGlyph`'s own
                // doc comment in `SettingsScreen.kt`) — the sheet's own header is a danger/attention cue
                // exactly like those rows are, so it now reads as the same glyph family instead of a
                // third shape of its own. Meaning is unchanged; only the shape and tint (`dangerIcon`,
                // not `warning` — see `StatusGlyph`'s doc comment for why the two roles differ) are.
                Icon(
                    imageVector = AgoIcons.ErrorCircle,
                    contentDescription = null,
                    tint = agoStatusColors().dangerIcon,
                    modifier = Modifier.padding(top = 2.dp).size(24.dp),
                )
                Text(
                    text = stringResource(R.string.battery_awareness_battery_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Button(onClick = onOpenBatterySettings, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Text(text = stringResource(R.string.battery_awareness_battery_action))
            }

            Text(
                text = stringResource(R.string.battery_awareness_autostart_explanation),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            OutlinedButton(onClick = onOpenAutostartSettings, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(text = stringResource(R.string.battery_awareness_autostart_action))
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .clickable { dontShowAgain = !dontShowAgain },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                Text(text = stringResource(R.string.battery_awareness_sheet_dont_show_again))
            }
            Text(
                text = stringResource(R.string.battery_awareness_sheet_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
        }
    }
}
