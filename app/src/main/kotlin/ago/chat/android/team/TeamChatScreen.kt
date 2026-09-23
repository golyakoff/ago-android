package ago.chat.android.team

import ago.chat.android.R
import ago.chat.android.core.network.realtime.TeamMessageDto
import ago.chat.android.ui.components.HubConnectionDot
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-54`: Команда, for real — the tenant's one team room, ridden over the operator hub connection this
 * app already holds open (this file's own package doc: [TeamChatViewModel]). No segmented control (Люди
 * is `26-55`, `MoreScreen`'s own one-row precedent restated) and no left/right message sides — see
 * [TeamMessageRow]'s own doc comment for why.
 *
 * Modelled on [ago.chat.android.thread.ThreadScreen] wherever the shape is genuinely shared (the
 * composer, the loading/error bodies), but its own, smaller file rather than a reuse of that screen's
 * private composables — a team message has no attachment control, no visitor title block and no
 * keyboard-driven `BackHandler` of its own (this is a bottom-tab destination, not a pushed screen), so
 * threading this room through `ThreadScreen`'s own parameter list would cost more than the handful of
 * composables this file repeats.
 */
@Composable
public fun TeamChatRoute(viewModel: TeamChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    TeamChatScreen(
        state = state,
        onRetryLoad = viewModel::retryLoad,
        onLoadOlder = viewModel::loadOlder,
        onDraftChanged = viewModel::onDraftChanged,
        onSend = viewModel::sendClicked,
        onRetrySend = viewModel::retrySend,
        onDismissSendRefusal = viewModel::dismissSendRefusal,
    )
}

/** The stateless half - [TeamChatRoute] wires the [TeamChatViewModel] above it, the same "route wires,
 * screen renders" split every other screen in this app already follows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamChatScreen(
    state: TeamChatUiState,
    onRetryLoad: () -> Unit,
    onLoadOlder: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onRetrySend: () -> Unit,
    onDismissSendRefusal: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.nav_team)) },
                    actions = {
                        HubConnectionDot(state = state.hubConnectionState, modifier = Modifier.padding(end = 16.dp))
                    },
                )
            },
            bottomBar = {
                TeamComposer(
                    draft = state.draft,
                    sending = state.sending,
                    onDraftChanged = onDraftChanged,
                    onSend = onSend,
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (state.pendingRetry) {
                    DismissibleTeamBanner(
                        message = stringResource(R.string.thread_send_pending_retry),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = onRetrySend,
                    )
                }
                state.sendRefusedMessage?.let { refusal ->
                    DismissibleTeamBanner(
                        message = refusal,
                        actionLabel = stringResource(R.string.action_dismiss),
                        onAction = onDismissSendRefusal,
                    )
                }

                when {
                    state.loading -> TeamLoadingBody()
                    // The identical "a join failure never leaves any message on screen" signal
                    // `ThreadScreen`'s own `when` uses to tell an initial-load failure apart from a
                    // later "load older" failure, restated for this room's own `historyError`.
                    state.messages.isEmpty() && state.historyError != null ->
                        TeamLoadErrorBody(error = state.historyError, onRetry = onRetryLoad)

                    else ->
                        TeamMessageList(
                            modifier = Modifier.weight(1f),
                            messages = state.messages,
                            canLoadOlder = state.canLoadOlder,
                            loadingOlder = state.loadingOlder,
                            historyError = state.historyError,
                            onLoadOlder = onLoadOlder,
                        )
                }
            }
        }
    }
}

@Composable
private fun TeamLoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.team_chat_loading_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun TeamLoadErrorBody(
    error: String,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = stringResource(R.string.team_chat_load_failed_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text(text = stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun DismissibleTeamBanner(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAction) { Text(text = actionLabel) }
    }
}

/**
 * `26-54`: the room itself - keyset paging upward, exactly [ago.chat.android.thread.ThreadScreen]'s own
 * `MessageList` shape (`reverseLayout = true` so the newest message anchors the bottom, an explicit
 * "load older" tap rather than infinite scroll). [messages] arrives already sorted ascending by
 * [TeamChatViewModel]'s own `mergeAndRender`.
 */
@Composable
private fun TeamMessageList(
    messages: List<TeamMessageDto>,
    canLoadOlder: Boolean,
    loadingOlder: Boolean,
    historyError: String?,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty() && historyError == null) {
        Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.team_chat_empty_state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val newestFirst = messages.asReversed()
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(newestFirst, key = { it.id }) { message -> TeamMessageRow(message) }

        if (canLoadOlder) {
            item(key = "load-older") {
                Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    if (loadingOlder) {
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    } else {
                        TextButton(onClick = onLoadOlder) {
                            Text(text = stringResource(R.string.thread_load_older_action))
                        }
                    }
                }
            }
        }

        // A "load older" failure, not an initial-load failure - the caller above only reaches this
        // composable once the initial load itself succeeded.
        historyError?.let { error ->
            item(key = "history-error") {
                DismissibleTeamBanner(message = error, actionLabel = stringResource(R.string.action_retry), onAction = onLoadOlder)
            }
        }
    }
}

/**
 * `26-54`: `TeamChatPage.tsx`'s own `.ago-team-message` row, restated - a flat list item, deliberately
 * not a chat bubble. **No left/right side**: every message renders identically regardless of author,
 * because there is no route to the caller's own operator id to compare against (this screen's own
 * top-of-file doc comment, `ago-console`'s identical reasoning). [TeamMessageDto.authorIsAdmin] is the
 * one distinction this row does draw, and it comes from the server - stamped at send time, never
 * recomputed here.
 */
@Composable
private fun TeamMessageRow(message: TeamMessageDto) {
    val removed = message.removedAt != null
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = message.authorDisplayName ?: stringResource(R.string.team_chat_unnamed_author),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (message.authorIsAdmin) {
                TeamAdminBadge()
            }
            clockTimeOrNull(message.createdAt)?.let { time ->
                Text(text = time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            // `TeamMessageDto`'s own doc comment: `body` is `null` exactly when `removedAt` is not - a
            // fixed placeholder is drawn whenever [removed], never the raw (already-`null`) body.
            text = if (removed) stringResource(R.string.team_chat_removed_placeholder) else message.body.orEmpty(),
            style =
                if (removed) {
                    MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
            color = if (removed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** The mockup's own admin pill - `ConversationListScreen`'s private `StatusPill` restated locally
 * rather than shared, since that composable is `private` to its own file and this is this file's only
 * caller of the shape. */
@Composable
private fun TeamAdminBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(
            text = stringResource(R.string.team_chat_admin_badge),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** `null` for anything that fails to parse - `ThreadScreen`'s own `clockTimeOrNull`, restated. Rendered
 * in the device's own zone. */
private fun clockTimeOrNull(createdAt: String): String? =
    runCatching {
        OffsetDateTime.parse(createdAt).atZoneSameInstant(ZoneId.systemDefault()).format(CLOCK_FORMAT)
    }.getOrNull()

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** No attach control - a team message never carries an attachment (`26-54`'s own Scope). */
@Composable
private fun TeamComposer(
    draft: String,
    sending: Boolean,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ComposerHorizontalPadding, vertical = ComposerVerticalPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ComposerGap),
            ) {
                TeamComposerField(draft = draft, onDraftChanged = onDraftChanged, modifier = Modifier.weight(1f))
                FilledIconButton(onClick = onSend, enabled = draft.isNotBlank() && !sending) {
                    Icon(
                        imageVector = AgoIcons.Send,
                        contentDescription = stringResource(R.string.team_chat_composer_send),
                    )
                }
            }
        }
    }
}

/** `ThreadScreen`'s own `ComposerField` — the mockup's `.field` pill, restated. */
@Composable
private fun TeamComposerField(
    draft: String,
    onDraftChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyle = TextStyle(fontSize = ComposerFieldFontSize, color = MaterialTheme.colorScheme.onSurface)
    Surface(
        modifier = modifier.heightIn(min = ComposerFieldHeight),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(modifier = Modifier.padding(horizontal = ComposerFieldHorizontalPadding), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChanged,
                modifier = Modifier.fillMaxWidth(),
                textStyle = textStyle,
                maxLines = COMPOSER_MAX_LINES,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                decorationBox = { innerTextField ->
                    if (draft.isEmpty()) {
                        Text(
                            text = stringResource(R.string.team_chat_composer_placeholder),
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                },
            )
        }
    }
}

private val ComposerHorizontalPadding = 12.dp
private val ComposerVerticalPadding = 9.dp
private val ComposerGap = 9.dp
private val ComposerFieldHeight = 40.dp
private val ComposerFieldHorizontalPadding = 14.dp
private val ComposerFieldFontSize = 13.5.sp
private const val COMPOSER_MAX_LINES = 5
