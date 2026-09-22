package ago.chat.android.thread

import ago.chat.android.R
import ago.chat.android.core.network.realtime.MessageDto
import ago.chat.android.ui.components.HubConnectionDebugRow
import ago.chat.android.ui.components.VisitorDisplayPrefix
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-15`: the thread screen - history, send, receive. Two decisions this file's own doc comment
 * states rather than leaving to be inferred from the code, per `docs/navigation.md`'s own framing that
 * both are worth stating in the open:
 *
 * ## The visitor chip: absent, not inert
 *
 * `navigation.md` draws the app bar's visitor identity as a tappable chip opening the (not-yet-built)
 * visitor context sheet, and asks this item to pick "present-but-inert" or "absent-until-then". This
 * screen picks **absent**: [TopAppBar]'s title below is [VisitorDisplayPrefix] rendered as plain,
 * non-interactive text — no `clickable`, no ripple, nothing that looks like it should respond to a
 * tap. The ticket's own reasoning is why: "a chip that does nothing when tapped is worse than no
 * chip" is a stronger, more specific claim than "a control that appears later is a layout change
 * nobody expects" — the latter is true of *any* control this app will ever add, and would argue
 * against ever shipping a screen incrementally at all. An inert chip actively teaches an operator that
 * tapping it does nothing, which is a worse thing to teach than simply not having drawn a tap target;
 * the identical hide-rather-than-disable posture `AttachmentUploadGrantToggle` states for itself. When
 * the visitor context sheet lands, the chip is a small, additive change to this same title slot - not
 * a redesign of it.
 *
 * ## The attach control
 *
 * Drawn — a real, visible paperclip — exactly when [ThreadRoute]'s own `hasAttachmentUploadGrant`
 * parameter is `true`, and omitted entirely otherwise (`navigation.md` §"The attach control": hidden,
 * never disabled, when the grant is absent — a disabled one would advertise a capability nobody could
 * use). What it does when shown is a deliberate stub: the picker, the presigned upload and the
 * `WorkManager` job are this item's own Out of scope ("Attachments themselves... a later item"), so
 * there is genuinely nothing yet to wire its tap to. This is a narrower promise than the visitor chip
 * makes — this item's whole job here is *which conversations show the control at all*, decided
 * correctly from the row's own [ago.chat.android.core.domain.conversations.ConversationSummary.hasAttachmentUploadGrant],
 * not what happens after a tap.
 */
@Composable
public fun ThreadRoute(
    conversationId: String,
    visitorId: String,
    emojiCreature: String?,
    emojiFood: String?,
    visitorName: String?,
    hasAttachmentUploadGrant: Boolean,
    onBack: () -> Unit,
    viewModel: ThreadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // The one "leave this thread" action, reached two ways - the app bar's own back arrow (passed to
    // `ThreadScreen` below) and the system back gesture/button (`BackHandler` below) - both routed
    // through the identical wrapper so neither one can skip releasing the hub subscription or
    // flushing the draft. Registering this `BackHandler` here, rather than one level up in
    // `SignedInHost` guarding on "is a thread open", is what lets that caller stay unaware of this
    // screen's own cleanup - it only ever has to know "the operator asked to leave", not how leaving
    // is implemented.
    val leaveThread: () -> Unit = {
        viewModel.close()
        onBack()
    }
    BackHandler(onBack = leaveThread)

    LaunchedEffect(conversationId) {
        viewModel.open(conversationId)
    }

    // `ThreadViewModel`'s own doc comment on why `ON_STOP` is what actually has to fire the draft
    // flush, rather than a `DisposableEffect`'s own `onDispose`: a process death can happen with no
    // "leaving the thread" involved at all, and `ON_STOP` is the lifecycle event Android guarantees
    // before that can happen. `ON_STOP` also fires on a plain device rotation - harmless here, since
    // flushing an unchanged draft is a no-op write - which is exactly why `close()` below is *not*
    // wired to this same observer: rotation must not release the hub subscription or reset this
    // screen's own state, only leaving the thread for real (`onBack`) should.
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) viewModel.flushDraft()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ThreadScreen(
        state = state,
        visitorId = visitorId,
        emojiCreature = emojiCreature,
        emojiFood = emojiFood,
        visitorName = visitorName,
        hasAttachmentUploadGrant = hasAttachmentUploadGrant,
        onBack = leaveThread,
        onLoadOlder = viewModel::loadOlder,
        onRetryJoin = viewModel::retryJoin,
        onDraftChanged = viewModel::onDraftChanged,
        onSend = viewModel::sendClicked,
        onRetrySend = viewModel::retrySend,
        onDismissSendRefusal = viewModel::dismissSendRefusal,
    )
}

/** The stateless half - [ThreadRoute] wires the [ThreadViewModel] above it, the same "route wires,
 * screen renders" split every other screen in this app already follows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadScreen(
    state: ThreadUiState,
    visitorId: String,
    emojiCreature: String?,
    emojiFood: String?,
    visitorName: String?,
    hasAttachmentUploadGrant: Boolean,
    onBack: () -> Unit,
    onLoadOlder: () -> Unit,
    onRetryJoin: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onRetrySend: () -> Unit,
    onDismissSendRefusal: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Text(text = "←", style = MaterialTheme.typography.headlineSmall)
                            }
                        },
                        title = {
                            // Plain text, not a chip - see this file's own top-of-file doc comment.
                            VisitorDisplayPrefix(
                                emojiCreature = emojiCreature,
                                emojiFood = emojiFood,
                                visitorName = visitorName,
                                visitorId = visitorId,
                            )
                        },
                    )
                    HubConnectionDebugRow(
                        state = state.hubConnectionState,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            },
            bottomBar = {
                Composer(
                    draft = state.draft,
                    sending = state.sending,
                    hasAttachmentUploadGrant = hasAttachmentUploadGrant,
                    onDraftChanged = onDraftChanged,
                    onSend = onSend,
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (state.pendingRetry) {
                    DismissibleBanner(
                        message = stringResource(R.string.thread_send_pending_retry),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = onRetrySend,
                    )
                }
                state.sendRefusedMessage?.let { refusal ->
                    DismissibleBanner(
                        message = refusal,
                        actionLabel = stringResource(R.string.action_dismiss),
                        onAction = onDismissSendRefusal,
                    )
                }

                when {
                    state.joining -> LoadingBody()
                    // A join failure never leaves any message on screen (nothing was ever loaded) -
                    // the one signal this screen uses to tell "the initial join failed" apart from "a
                    // later 'load older' page failed", since both share the same `historyError` field.
                    state.messages.isEmpty() && state.historyError != null ->
                        JoinErrorBody(error = state.historyError, onRetry = onRetryJoin)

                    else ->
                        MessageList(
                            // `.weight(1f)` is resolved here, inside the enclosing `Column`'s own
                            // `ColumnScope`, and carried into `MessageList` as an ordinary `Modifier` -
                            // the standard way a scope-specific modifier crosses a composable boundary
                            // without that composable needing the scope itself as a receiver.
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
private fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun JoinErrorBody(
    error: String,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = stringResource(R.string.thread_join_failed_title), style = MaterialTheme.typography.titleMedium)
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
private fun DismissibleBanner(
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
 * `docs/backlog/26-15-*.md`: "the message list ordered by `sequence`, keyset paging upward". `messages`
 * arrives already sorted ascending (oldest first) by [ThreadViewModel.mergeAndRender]; this list is
 * rendered with `reverseLayout = true` so the newest message anchors the bottom of the viewport (the
 * ordinary chat convention) and scrolling *up* moves toward older ones - the "Load older messages"
 * button, an explicit tap rather than a silent infinite-scroll trigger (matching `ago-console`'s own
 * `Thread.tsx`), sits at the far end of the reversed list, which renders at the visual top.
 */
@Composable
private fun MessageList(
    messages: List<MessageDto>,
    canLoadOlder: Boolean,
    loadingOlder: Boolean,
    historyError: String?,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val newestFirst = messages.asReversed()
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(newestFirst, key = { it.id }) { message -> MessageBubble(message) }

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

        // A "load older" failure, not a join failure - the caller above only reaches this composable
        // once the join itself succeeded, so any `historyError` here is this list's own to show.
        historyError?.let { error ->
            item(key = "history-error") {
                DismissibleBanner(message = error, actionLabel = stringResource(R.string.action_retry), onAction = onLoadOlder)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MessageDto) {
    val isOperator = message.authorKind == "Operator"
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isOperator) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isOperator) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(text = message.body, style = MaterialTheme.typography.bodyMedium)
                clockTimeOrNull(message.createdAt)?.let { time ->
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** `null` for anything that fails to parse - the same "never invented, rendered honestly" posture
 * `ago.chat.android.core.domain.conversations.elapsedSince` already takes for a malformed `createdAt`,
 * rather than throwing out of a composable or showing a made-up time. Rendered in the device's own
 * zone - the operator reading this screen, not the visitor's. */
private fun clockTimeOrNull(createdAt: String): String? =
    runCatching {
        OffsetDateTime.parse(createdAt).atZoneSameInstant(ZoneId.systemDefault()).format(CLOCK_FORMAT)
    }.getOrNull()

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun Composer(
    draft: String,
    sending: Boolean,
    hasAttachmentUploadGrant: Boolean,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hasAttachmentUploadGrant) {
                // A real, visible control - not disabled - whose tap does nothing yet. See this file's
                // own top-of-file doc comment on why that stub is the honest shape for this item.
                IconButton(onClick = { }) {
                    Text(text = "📎", style = MaterialTheme.typography.titleLarge)
                }
            }
            TextField(
                value = draft,
                onValueChange = onDraftChanged,
                modifier = Modifier.weight(1f),
                placeholder = { Text(text = stringResource(R.string.thread_composer_placeholder)) },
                maxLines = 5,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onSend, enabled = draft.isNotBlank() && !sending) {
                Text(text = stringResource(R.string.thread_composer_send))
            }
        }
    }
}
