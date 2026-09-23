package ago.chat.android.team

import ago.chat.android.R
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.core.network.realtime.TeamMessageDto
import ago.chat.android.ui.components.AccountAvatarAction
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 * `26-54`/`26-55`: Команда's two segments — «Общение» (`26-54`'s own room) and «Люди»
 * (`26-55`'s roster), matching `ago-console/src/shell/consoleNav.ts:345`'s own `site:manage_operators`
 * gate for the identical destination, folded here into one segmented control rather than the console's
 * two separate nav rows because Команда is one bottom-navigation destination on this app, not a rail
 * section — [ago.chat.android.core.domain.navigation.BottomDestination.Team]'s own doc comment.
 */
internal enum class TeamTab {
    Communication,
    People,
}

/**
 * `26-55`: Команда's real entry point, replacing the old parameterless `TeamChatRoute` this file used to
 * expose directly to [ago.chat.android.shell.AppShellScreen]. [canManageOperators] is
 * `OperatorPermissions.holds(Permission.SITE_MANAGE_OPERATORS)`
 * (`ago.chat.android.core.domain.permissions`), read once by the caller and handed down as a plain
 * `Boolean` — this composable has no reason to know about `OperatorPermissions` as a type, only the one
 * fact it already decided.
 *
 * **No segmented control at all without the permission** — `navigation.md`'s own "a destination with
 * nothing extra inside it draws nothing extra" rule, restated one level down from
 * [ago.chat.android.core.domain.navigation.visibleBottomDestinations] itself: Команда is always drawn,
 * but an operator lacking `site:manage_operators` sees exactly what `26-54` shipped — the room, and
 * nothing else — because [TeamScreen] below never draws [TeamSegmentedRow] for that operator at all,
 * not a segmented row with «Люди» disabled or hidden-but-present in the tab enum.
 *
 * [TeamChatViewModel] is always obtained here, never only when «Общение» is selected — it always was
 * (this is the identical `hiltViewModel()` call `26-54`'s own `TeamChatRoute` already made
 * unconditionally), so an operator who opens Люди first still has a warm hub connection the moment they
 * switch back.
 */
@Composable
public fun TeamRoute(
    canManageOperators: Boolean,
    hubConnectionState: OperatorHubConnectionState,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
    operatorDisplayName: String? = null,
    operatorEmail: String? = null,
    chatViewModel: TeamChatViewModel = hiltViewModel(),
    peopleContent: @Composable () -> Unit = { PeopleRoute() },
) {
    val chatState by chatViewModel.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(TeamTab.Communication) }

    TeamScreen(
        canManageOperators = canManageOperators,
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it },
        chatState = chatState,
        onRetryLoad = chatViewModel::retryLoad,
        onLoadOlder = chatViewModel::loadOlder,
        onDraftChanged = chatViewModel::onDraftChanged,
        onSend = chatViewModel::sendClicked,
        onRetrySend = chatViewModel::retrySend,
        onDismissSendRefusal = chatViewModel::dismissSendRefusal,
        peopleContent = peopleContent,
        hubConnectionState = hubConnectionState,
        operatorDisplayName = operatorDisplayName,
        operatorEmail = operatorEmail,
        onOpenSettings = onOpenSettings,
        onSignOut = onSignOut,
    )
}

/**
 * The stateless half — [TeamRoute] wires [TeamChatViewModel] above it, the same "route wires, screen
 * renders" split every other screen in this app already follows. One `Scaffold`, one `TopAppBar` titled
 * Команда regardless of which segment is selected (`BookingsScreen`'s own precedent: the app-bar title
 * names the destination, not the segment) — [TeamSegmentedRow] and the body beneath it are the only
 * parts that change.
 *
 * The composer (`TeamComposer`) is Общение's own `bottomBar`, drawn only while that segment is
 * selected — Люди is read-only end to end (`docs/backlog/26-55-*.md`'s own Out of scope), so it has
 * nothing to compose into.
 *
 * `26-77`: [AccountAvatarAction] is drawn unconditionally, unlike the bare `HubConnectionDot` it
 * replaces here (which was Общение-only, per this doc comment's own previous wording: "the hub
 * connection state describes the chat room, not the plain-REST roster read beside it"). Showing the
 * presence dot on Люди too is a deliberate widening of what it means, settled by this item: it now
 * reads as *this operator's own* connectivity rather than as a fact scoped to one segment, and
 * [chatState]'s hub connection genuinely stays live regardless of [selectedTab] (`TeamRoute`'s own doc
 * comment on why [TeamChatViewModel] is never obtained conditionally), so this is a true fact on both
 * segments, not a stale one borrowed from the other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamScreen(
    canManageOperators: Boolean,
    selectedTab: TeamTab,
    onTabSelected: (TeamTab) -> Unit,
    chatState: TeamChatUiState,
    onRetryLoad: () -> Unit,
    onLoadOlder: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onRetrySend: () -> Unit,
    onDismissSendRefusal: () -> Unit,
    peopleContent: @Composable () -> Unit,
    hubConnectionState: OperatorHubConnectionState = chatState.hubConnectionState,
    operatorDisplayName: String? = null,
    operatorEmail: String? = null,
    onOpenSettings: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    val showChat = !canManageOperators || selectedTab == TeamTab.Communication

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.nav_team)) },
                    actions = {
                        AccountAvatarAction(
                            displayName = operatorDisplayName,
                            email = operatorEmail,
                            hubConnectionState = hubConnectionState,
                            onOpenSettings = onOpenSettings,
                            onSignOut = onSignOut,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    },
                )
            },
            bottomBar = {
                if (showChat) {
                    TeamComposer(
                        draft = chatState.draft,
                        sending = chatState.sending,
                        onDraftChanged = onDraftChanged,
                        onSend = onSend,
                    )
                }
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (canManageOperators) {
                    TeamSegmentedRow(selected = selectedTab, onSelected = onTabSelected)
                }

                if (showChat) {
                    TeamChatBody(
                        state = chatState,
                        onRetryLoad = onRetryLoad,
                        onLoadOlder = onLoadOlder,
                        onRetrySend = onRetrySend,
                        onDismissSendRefusal = onDismissSendRefusal,
                    )
                } else {
                    peopleContent()
                }
            }
        }
    }
}

/**
 * `26-51`'s own dynamic, permission-driven segment list read for Команда's two segments instead of
 * Записи's booking-status ones — [ConversationListScreen][ago.chat.android.conversations.ConversationListScreen]'s
 * own `SingleChoiceSegmentedButtonRow`/`SegmentedButton` loop over an enum's `entries`, restated (this
 * screen carries no per-segment count the way that one's `Мои`/`Ожидают` badges do, so
 * [segmentedTabLabel] has nothing to build beyond the bare label).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamSegmentedRow(
    selected: TeamTab,
    onSelected: (TeamTab) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        TeamTab.entries.forEachIndexed { index, tab ->
            SegmentedButton(
                selected = selected == tab,
                onClick = { onSelected(tab) },
                shape = SegmentedButtonDefaults.itemShape(index, TeamTab.entries.size),
                label = { Text(text = stringResource(tab.labelRes())) },
                icon = {},
            )
        }
    }
}

private fun TeamTab.labelRes(): Int =
    when (this) {
        TeamTab.Communication -> R.string.team_tab_communication
        TeamTab.People -> R.string.team_tab_people
    }

/**
 * `26-54`'s own room content — banners, then whichever of loading/error/message-list applies. Lifted
 * unchanged out of what used to be `TeamChatScreen`'s own `Scaffold` content lambda: `26-55`'s own brief
 * is explicit that this body's logic is not this item's to touch, only the chrome that now surrounds it
 * ([TeamScreen] above).
 */
@Composable
private fun ColumnScope.TeamChatBody(
    state: TeamChatUiState,
    onRetryLoad: () -> Unit,
    onLoadOlder: () -> Unit,
    onRetrySend: () -> Unit,
    onDismissSendRefusal: () -> Unit,
) {
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
        // The identical "a join failure never leaves any message on screen" signal `ThreadScreen`'s own
        // `when` uses to tell an initial-load failure apart from a later "load older" failure, restated
        // for this room's own `historyError`.
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
