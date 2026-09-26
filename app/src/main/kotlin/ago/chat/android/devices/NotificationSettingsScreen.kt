package ago.chat.android.devices

import ago.chat.android.R
import ago.chat.android.ui.components.SectionLabel
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/**
 * `26-19`: "the switches on this screen are true" — the real, dedicated notification-settings screen the
 * old disabled-banner design (`scope-inventory.md` §11) was always meant to become once the channels it
 * names actually existed (`26-18`). See this module's own `docs/backlog/26-19-*.md` for the corrected
 * premise this item was built against: no such disabled-banner screen exists in this codebase to un-gate
 * — this is a from-scratch build.
 *
 * **Reached from [ago.chat.android.shell.SettingsScreen] as a local drill-in, not a new top-level
 * `NavHost` route.** [ago.chat.android.shell.AppShellContent]'s own `SETTINGS_ROUTE` is reachable from
 * every tab's own account menu specifically because Настройки itself is a genuinely global destination;
 * this screen is reachable from exactly one place (Настройки's own new row), which is the identical
 * shape [ago.chat.android.shell.MoreScreen]'s own `openRowId` already is for Ещё's one-level-deep rows —
 * a `rememberSaveable` boolean plus a `BackHandler` in [ago.chat.android.shell.SettingsRoute], not a
 * second entry in [ago.chat.android.shell.AppShellScreen]'s own settingsScreen slot. Adding a new global
 * route here would have meant touching that slot's default, both back-contract test files that already
 * substitute markers for it, and `SETTINGS_ROUTE`'s own sibling constant — real surface for a screen that
 * is, and only ever will be, one level under Настройки.
 */
@Composable
internal fun NotificationSettingsRoute(
    onBack: () -> Unit,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val channelStates by viewModel.channelStates.collectAsStateWithLifecycle()
    val quietHours by viewModel.quietHours.collectAsStateWithLifecycle()
    val pushAvailability by viewModel.pushAvailability.collectAsStateWithLifecycle()

    // `SettingsRoute`'s own identical `ON_RESUME` observer, for the identical reason -
    // [NotificationSettingsViewModel.refreshChannelStates]'s own doc comment states it in full: an
    // operator who left for the system per-channel settings screen and came back must see the real,
    // current importance.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshChannelStates()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val context = LocalContext.current
    NotificationSettingsScreen(
        channelStates = channelStates,
        pushAvailability = pushAvailability,
        onOpenChannelSettings = { channel ->
            // `26-19`'s own Scope: "the row deep-links into the system channel settings rather than
            // keeping a second, disagreeing copy of it" - `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS`
            // with both extras, the documented way to land on one specific channel's own settings page
            // rather than the app's whole notification settings ([SettingsRoute]'s own
            // `ACTION_APP_NOTIFICATION_SETTINGS` neighbour, which has no channel of its own to name).
            context.startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channel.id),
            )
        },
        quietHours = quietHours,
        onQuietHoursEnabledChanged = viewModel::setQuietHoursEnabled,
        onQuietHoursRangeChanged = viewModel::setQuietHoursRange,
        onBack = onBack,
    )
}

/** The stateless half — every Compose preview and every future UI test targets this function directly,
 * the identical "route wires, screen renders" split [ago.chat.android.shell.SettingsScreen] already is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationSettingsScreen(
    channelStates: Map<PushNotificationChannel, Boolean>,
    onOpenChannelSettings: (PushNotificationChannel) -> Unit,
    quietHours: QuietHoursSettings,
    onQuietHoursEnabledChanged: (Boolean) -> Unit,
    onQuietHoursRangeChanged: (Int, Int) -> Unit,
    onBack: () -> Unit,
    pushAvailability: PushAvailability? = null,
) {
    var editingBoundary by remember { mutableStateOf<QuietHoursBoundary?>(null) }

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.notification_settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
                // `26-101`: a persistent-until-resolved warning, drawn first - the operator reached this
                // screen specifically to manage notifications, so whether they can arrive at all on this
                // device is the one fact worth stating before any switch. Hidden entirely while
                // [pushAvailability] is `Available` or still `null` (nothing has asked yet, or the last
                // answer was "this device is fine") - the identical "hidden, not shown-disabled"
                // convention this app's own `SettingsScreen` already applies to the same port.
                val pushUnavailable = pushAvailability as? PushAvailability.Unavailable
                if (pushUnavailable != null) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                text = stringResource(R.string.notification_settings_push_warning_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = agoStatusColors().dangerText,
                            )
                            Text(
                                text = pushUnavailableReasonText(pushUnavailable.reason),
                                style = MaterialTheme.typography.bodyMedium,
                                color = agoStatusColors().dangerText,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    item { HorizontalDivider() }
                }

                // `26-19`'s own Scope, in full: "A switch per notification channel that actually exists -
                // the ones the fan-out sends and no others." `26-86` grew that set from two to three;
                // this row-per-entry loop needed no change at all to pick up the third, because
                // [PushNotificationChannel.entries] is the one place that set is ever named.
                item { SectionLabel(stringResource(R.string.notification_settings_channels_section)) }
                items(PushNotificationChannel.entries, key = { it.id }) { channel ->
                    ChannelRow(
                        channel = channel,
                        enabled = channelStates[channel] ?: true,
                        onClick = { onOpenChannelSettings(channel) },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.notification_settings_channel_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                item { SectionLabel(stringResource(R.string.notification_settings_quiet_hours_section)) }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.notification_settings_quiet_hours_switch),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Switch(checked = quietHours.enabled, onCheckedChange = onQuietHoursEnabledChanged)
                    }
                }
                // Hidden entirely while disabled, the identical "hidden, not shown-disabled" convention
                // [ago.chat.android.shell.SettingsScreen]'s own doc comment already states for its site
                // switcher and its push-unavailable rows: a start/end time nobody can currently act on is
                // not a row an operator needs to read.
                if (quietHours.enabled) {
                    item {
                        QuietHoursTimeRow(
                            label = stringResource(R.string.notification_settings_quiet_hours_start_label),
                            minuteOfDay = quietHours.startMinuteOfDay,
                            onClick = { editingBoundary = QuietHoursBoundary.Start },
                        )
                    }
                    item {
                        QuietHoursTimeRow(
                            label = stringResource(R.string.notification_settings_quiet_hours_end_label),
                            minuteOfDay = quietHours.endMinuteOfDay,
                            onClick = { editingBoundary = QuietHoursBoundary.End },
                        )
                    }
                    item {
                        // `26-19`'s own Done-when: "the screen is honest about what happens to [a
                        // suppressed push]" - this app's own choice is "not at all", stated here in
                        // Russian rather than left to be inferred from the switch's own label.
                        Text(
                            text = stringResource(R.string.notification_settings_quiet_hours_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                // `26-19`'s own Away note: text only, never a control - `architecture.md`'s own Realtime
                // section states `SetAwayAsync` is per operator, not per connection, and this screen's
                // job is to say so rather than invent a per-device variant.
                item { SectionLabel(stringResource(R.string.notification_settings_away_section)) }
                item {
                    Text(
                        text = stringResource(R.string.notification_settings_away_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        val boundary = editingBoundary
        if (boundary != null) {
            QuietHoursTimeDialog(
                initialMinuteOfDay = if (boundary == QuietHoursBoundary.Start) quietHours.startMinuteOfDay else quietHours.endMinuteOfDay,
                onConfirm = { newMinute ->
                    val (newStart, newEnd) =
                        if (boundary == QuietHoursBoundary.Start) {
                            newMinute to quietHours.endMinuteOfDay
                        } else {
                            quietHours.startMinuteOfDay to newMinute
                        }
                    onQuietHoursRangeChanged(newStart, newEnd)
                    editingBoundary = null
                },
                onDismiss = { editingBoundary = null },
            )
        }
    }
}

private enum class QuietHoursBoundary { Start, End }

/** `26-101`: [PushUnavailableReason]'s own four values, each named - the identical mapping
 * [ago.chat.android.shell.SettingsScreen]'s own `pushUnavailableReasonText` already is for the same
 * port, restated here rather than shared across files: that function is `private` to a screen this item
 * does not touch, and the four strings it reads ([R.string.push_unavailable_host_app_not_installed] and
 * its three siblings) are already public resource ids meant to be read from wherever a reason needs
 * naming, not a copy owned by one screen. */
@Composable
private fun pushUnavailableReasonText(reason: PushUnavailableReason): String =
    when (reason) {
        PushUnavailableReason.HostAppNotInstalled -> stringResource(R.string.push_unavailable_host_app_not_installed)
        PushUnavailableReason.HostAppBackgroundWorkNotGranted -> stringResource(R.string.push_unavailable_background_work_not_granted)
        PushUnavailableReason.Unauthorized -> stringResource(R.string.push_unavailable_unauthorized)
        PushUnavailableReason.Unknown -> stringResource(R.string.push_unavailable_unknown)
    }

@Composable
private fun channelLabel(channel: PushNotificationChannel): String =
    when (channel) {
        PushNotificationChannel.Assignment -> stringResource(R.string.push_channel_assignment_name)
        PushNotificationChannel.VisitorMessage -> stringResource(R.string.push_channel_visitor_message_name)
        PushNotificationChannel.Waiting -> stringResource(R.string.push_channel_waiting_name)
    }

@Composable
private fun ChannelRow(
    channel: PushNotificationChannel,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = channelLabel(channel), style = MaterialTheme.typography.bodyLarge)
        Text(
            text =
                stringResource(
                    if (enabled) R.string.notification_settings_channel_state_on else R.string.notification_settings_channel_state_off,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else agoStatusColors().dangerText,
        )
    }
    HorizontalDivider()
}

@Composable
private fun QuietHoursTimeRow(
    label: String,
    minuteOfDay: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(text = formatMinuteOfDay(minuteOfDay), style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietHoursTimeDialog(
    initialMinuteOfDay: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state =
        rememberTimePickerState(
            initialHour = initialMinuteOfDay / 60,
            initialMinute = initialMinuteOfDay % 60,
            is24Hour = true,
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(text = stringResource(R.string.notification_settings_time_picker_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.notification_settings_time_picker_cancel))
            }
        },
        text = { TimePicker(state = state) },
    )
}

private fun formatMinuteOfDay(minuteOfDay: Int): String =
    String.format(Locale.getDefault(), "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)
