package ago.chat.android.channels

import ago.chat.android.R
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.core.domain.channels.VkReveal
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-188`: how a channel parameterises the shared [ChannelConnectScreen] — a plain bag of string
 * resource ids plus [showVkReveal], so the scaffold stays a pure renderer and each channel is a thin
 * binding ([TelegramChannelViewModel]'s own doc comment covers the view-model half of the same split).
 * [titleRes] doubles as the `MoreScreen` row label and this screen's own app-bar title, the identical
 * "one resource, two call sites" shape [ago.chat.android.channels.InstallWidgetScreen]'s own
 * `channels_install_title` already uses.
 *
 * `26-190`/`C3`: [showVkReveal] now gates the reveal panel [ConnectedBody] draws
 * (`docs/design/tenant-channels-android.md` §5.1 had named this file as the one `C3` would come back to
 * edit, exactly as it did). Telegram and MAX both set it `false`; only [VkChannelViewModel]'s own config
 * sets it `true`.
 */
internal data class ChannelConnectConfig(
    @StringRes val titleRes: Int,
    @StringRes val notConnectedBodyRes: Int,
    @StringRes val tokenLabelRes: Int,
    @StringRes val tokenHintRes: Int,
    @StringRes val disconnectDialogBodyRes: Int,
    val showVkReveal: Boolean,
)

/**
 * `26-188`: the reusable Каналы connect/status/disconnect screen — one composable Telegram binds now,
 * MAX and VK bind identically in `C2`/`C3`
 * ([ago.chat.android.core.domain.channels.ChannelConnectionApi]'s own doc comment: three channels, one
 * contract, read three times). Route/Screen split exactly like [InstallWidgetScreen]: back arrow in the
 * top bar (a drill-in, not a top-level destination), no
 * [ago.chat.android.ui.components.AccountAvatarAction].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelConnectScreen(
    state: ChannelConnectUiState,
    config: ChannelConnectConfig,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onDismissDisconnectError: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(config.titleRes)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    ChannelConnectUiState.Loading -> LoadingBody()
                    is ChannelConnectUiState.Failed -> ChannelStatusFailedBody(reason = state.reason, onRetry = onRetry)
                    is ChannelConnectUiState.Disconnected ->
                        DisconnectedBody(state = state, config = config, onConnect = onConnect)

                    is ChannelConnectUiState.Connected ->
                        ConnectedBody(
                            state = state,
                            config = config,
                            onDisconnect = onDisconnect,
                            onDismissDisconnectError = onDismissDisconnectError,
                        )
                }
            }
        }
    }
}

/** The status read itself failed — the identical "title, [networkFailureText], retry" shape
 * `ConversationListScreen`'s own `QueueLoadFailedBody` already establishes for a full-screen
 * [NetworkFailure], restated here privately since neither file imports composables from the other. */
@Composable
private fun ChannelStatusFailedBody(
    reason: NetworkFailure,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = networkFailureText(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

/** `connected == false` — a token field and a primary connect action, the identical shape
 * `docs/design/tenant-channels-android.md` §1.4 states for every one of the three token channels. The
 * token is transient `rememberSaveable` state, never persisted past this composition — acceptable per
 * that same section's own Edge cases: a short one-shot input, unlike a composer draft. */
@Composable
private fun DisconnectedBody(
    state: ChannelConnectUiState.Disconnected,
    config: ChannelConnectConfig,
    onConnect: (String) -> Unit,
) {
    var token by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(config.notConnectedBodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(text = stringResource(config.tokenLabelRes)) },
            placeholder = { Text(text = stringResource(config.tokenHintRes)) },
            singleLine = true,
            // The token never round-trips (`adr/0069`) - masked on entry the same way any credential
            // field would be, and never echoed back anywhere this screen reads from.
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !state.connecting,
            modifier = Modifier.fillMaxWidth(),
        )
        state.connectError?.let { error ->
            InlineAlert(text = channelActionErrorText(error))
        }
        Button(
            onClick = { onConnect(token) },
            enabled = token.isNotBlank() && !state.connecting,
        ) {
            Text(
                text =
                    stringResource(
                        if (state.connecting) R.string.channel_connect_connecting else R.string.channel_connect_action,
                    ),
            )
        }
    }
}

/** `connected == true` — the badge/since/checked block every one of the three connected sub-states
 * (verified/unreachable/refused) shares, then — for VK alone — the shown-once reveal region, then a ghost
 * disconnect action behind a confirm dialog. */
@Composable
private fun ConnectedBody(
    state: ChannelConnectUiState.Connected,
    config: ChannelConnectConfig,
    onDisconnect: () -> Unit,
    onDismissDisconnectError: () -> Unit,
) {
    var confirmingDisconnect by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectedStatusPill(state)

        state.createdAt?.let { createdAt ->
            Text(
                text = stringResource(R.string.channel_connected_since, CREATED_AT_FORMAT.format(createdAt)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Exactly one of the three connected sub-states is ever the true reading
        // ([ago.chat.android.core.domain.channels.ChannelStatus]'s own doc comment): unreachable and
        // refused get two different alerts because a tenant acts on them oppositely - wait-and-retry
        // against get-a-new-token (`adr/0143`) - never the same banner reworded.
        when {
            state.unreachable -> InlineAlert(text = stringResource(R.string.channel_status_unreachable_body))
            state.verified == false && state.refusalReason != null -> InlineAlert(text = state.refusalReason)
        }

        Text(
            text = stringResource(R.string.channel_checked_at, CHECKED_AT_FORMAT.format(state.checkedAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // `26-190`/`C3`: VK's own shown-once reveal - drawn only for VK ([ChannelConnectConfig.showVkReveal])
        // and only below the badge/since/checked block every connected sub-state already shares
        // (`docs/design/tenant-channels-android.md` §2.3). A reload or a second device genuinely has
        // nothing to show ([ChannelConnectUiState.Connected.reveal]'s own doc comment), so that case gets
        // its own honest hint rather than a silently-omitted panel.
        if (config.showVkReveal) {
            if (state.reveal != null) {
                VkRevealPanel(reveal = state.reveal)
            } else {
                InlineAlert(text = stringResource(R.string.channels_vk_secrets_shown_once_hint))
            }
        }

        OutlinedButton(onClick = { confirmingDisconnect = true }, enabled = !state.disconnecting) {
            Text(
                text =
                    stringResource(
                        if (state.disconnecting) R.string.channel_disconnecting_action else R.string.channel_disconnect_action,
                    ),
            )
        }
    }

    if (confirmingDisconnect) {
        AlertDialog(
            onDismissRequest = {
                confirmingDisconnect = false
                onDismissDisconnectError()
            },
            title = { Text(text = stringResource(R.string.channel_disconnect_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = stringResource(config.disconnectDialogBodyRes))
                    state.disconnectError?.let { error ->
                        Text(
                            text = channelActionErrorText(error),
                            style = MaterialTheme.typography.bodySmall,
                            color = agoStatusColors().dangerText,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDisconnect, enabled = !state.disconnecting) {
                    Text(text = stringResource(R.string.channel_disconnect_action), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmingDisconnect = false
                    onDismissDisconnectError()
                }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * `26-190`/`C3`: VK's own shown-once `callbackUrl`/`webhookSecret` - a secret **AGO generated for the
 * shop**, needed by a human pasting it into VK's own community Callback API settings
 * ([VkReveal]'s own doc comment). A neutral surface (not the danger-tinted [InlineAlert]) since this
 * panel is informational, never a refusal or a wait-and-retry notice.
 */
@Composable
private fun VkRevealPanel(reveal: VkReveal) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(R.string.channels_vk_setup_title), style = MaterialTheme.typography.titleSmall)
            Text(text = stringResource(R.string.channels_vk_setup_body), style = MaterialTheme.typography.bodySmall)
            VkRevealField(
                value = reveal.callbackUrl,
                copyLabelRes = R.string.channels_vk_copy_callback_url,
                copiedLabelRes = R.string.channels_vk_callback_url_copied,
            )
            VkRevealField(
                value = reveal.webhookSecret,
                copyLabelRes = R.string.channels_vk_copy_webhook_secret,
                copiedLabelRes = R.string.channels_vk_webhook_secret_copied,
            )
        }
    }
}

/**
 * One reveal value: a read-only monospace field, a primary **Копировать** action and a secondary
 * **Поделиться** one — the identical [LocalClipboard]/[ClipEntry] and [Intent.ACTION_SEND]/
 * `createChooser` shapes [ago.chat.android.team.InviteColleagueSheet]'s own `InviteResultBody` already
 * establishes for its invite link, restated here rather than shared since that composable is private to
 * its own file. The clip label is an OS-level implementation detail (visible, if at all, only in system
 * clipboard history), never user-facing copy, so it needs no string resource - the same reasoning that
 * composable's own doc comment gives for `"invite-link"`.
 */
@Composable
private fun VkRevealField(
    value: String,
    @StringRes copyLabelRes: Int,
    @StringRes copiedLabelRes: Int,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by rememberSaveable(value) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ago-channel-secret", value)))
                    copied = true
                }
            }) {
                Text(text = stringResource(copyLabelRes))
            }
            OutlinedButton(onClick = {
                val shareIntent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, value)
                    }
                try {
                    context.startActivity(Intent.createChooser(shareIntent, null))
                } catch (missing: ActivityNotFoundException) {
                    // A device with no share target at all, the only way this throws - the value is still
                    // on screen, copyable, either way (`InviteColleagueSheet`'s own identical posture).
                }
            }) {
                Text(text = stringResource(R.string.channels_vk_share))
            }
        }
        if (copied) {
            Text(
                text = stringResource(copiedLabelRes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The mockup's own three-way pill, restated locally as a tiny private composable rather than reused
 * from `ConversationListScreen`'s own `private fun StatusPill` — that one is `private` to its own file
 * on purpose, the same "each screen owns its own small drawing helpers" shape this app already has more
 * than one instance of. */
@Composable
private fun ConnectedStatusPill(state: ChannelConnectUiState.Connected) {
    val textRes: Int
    val containerColor: Color
    val contentColor: Color
    when {
        state.unreachable -> {
            textRes = R.string.channel_status_unreachable
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        }

        state.verified == true -> {
            textRes = R.string.channel_status_verified
            containerColor = MaterialTheme.colorScheme.primary
            contentColor = MaterialTheme.colorScheme.onPrimary
        }

        else -> {
            textRes = R.string.channel_status_refused
            containerColor = MaterialTheme.colorScheme.error
            contentColor = MaterialTheme.colorScheme.onError
        }
    }
    StatusPill(text = stringResource(textRes), containerColor = containerColor, contentColor = contentColor)
}

@Composable
private fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(color = containerColor, contentColor = contentColor, shape = RoundedCornerShape(percent = 50)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** An inline banner for a refusal or a wait-and-retry notice — a tonal danger surface for everything
 * this screen shows here (a bad/expired token, an unreachable provider, a failed disconnect): the app
 * has no separate "info" role ([ago.chat.android.ui.theme.AgoStatusColors]'s own doc comment names only
 * `warning` and `dangerText`/`dangerIcon` as the two gaps Material 3 leaves), so the softer
 * `errorContainer` tone stands in for both without inventing a role this codebase does not otherwise
 * need. */
@Composable
private fun InlineAlert(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

/** The one place [ChannelActionError] becomes a sentence — a [ChannelActionError.ServerRefusal] is
 * already the server's own words, shown verbatim; a [ChannelActionError.Unavailable] is rendered through
 * [networkFailureText], the identical single call site every other screen's own [NetworkFailure] goes
 * through. */
@Composable
private fun channelActionErrorText(error: ChannelActionError): String =
    when (error) {
        is ChannelActionError.ServerRefusal -> error.detail
        is ChannelActionError.Unavailable -> networkFailureText(error.reason)
    }

/** `createdAt` as a date stamp, `checkedAt` as date + time — the project's date-and-time rule (UTC
 * instant in, device-zone label out) applied with the app's own existing numeric, locale-independent
 * pattern rather than a spelled-out month name, the identical style
 * [ago.chat.android.bookings.PendingBookingsScreen]'s own `ROW_DATE_FORMAT` already uses. */
private val CREATED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault())
private val CHECKED_AT_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm").withZone(ZoneId.systemDefault())
