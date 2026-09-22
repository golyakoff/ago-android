package ago.chat.android.conversations

import ago.chat.android.R
import ago.chat.android.core.domain.conversations.ElapsedLabel
import ago.chat.android.core.domain.conversations.elapsedSince
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.ui.components.HubConnectionDebugRow
import ago.chat.android.ui.components.IdentifierText
import ago.chat.android.ui.components.VisitorDisplayPrefix
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.OffsetDateTime

/**
 * `26-14`: the screen this whole item exists to build — see `docs/backlog/26-14-*.md`'s own Scope for
 * the five properties it has to prove. Obtains its own [ConversationListViewModel] via [hiltViewModel] —
 * independent of `ago.chat.android.signin.SignInViewModel`, which owns only the pre-session flow.
 *
 * `activeSiteId` is accepted and rendered, not read from anywhere in here: this screen shows *the*
 * active site's own queue by construction (`ConversationsApi.fetchQueue` carries no site parameter of
 * its own — `X-Ago-Active-Site` is a client plugin, `docs/architecture.md` §Tenancy), so there is no
 * second source of truth to reconcile against; it is shown purely so an operator holding several seats
 * can see which one this list is.
 *
 * The hub connection's own start/stop lifecycle is owned by `OperatorHubConnectionLifecycle`
 * (process-wide), not by this screen — this composable's own `ON_START`/`ON_STOP` observer below drives
 * only [ConversationListViewModel.onScreenStarted]/[ConversationListViewModel.onScreenStopped], the
 * «Ожидают» poll's own visibility gate (that class's own doc comment on why it is narrower than
 * `ago-console`'s always-on timer).
 */
@Composable
public fun ConversationListRoute(
    activeSiteId: String?,
    hubConnectionState: OperatorHubConnectionState,
    onOpenConversation: (String) -> Unit,
    onSignOut: () -> Unit,
    viewModel: ConversationListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // `26-17`: told on every mount/remount, however often that turns out to be - see
    // `ConversationListViewModel.onActiveSiteChanged`'s own doc comment for why the dedup that makes
    // this safe lives on the view model instance rather than here.
    LaunchedEffect(activeSiteId) { viewModel.onActiveSiteChanged(activeSiteId) }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> viewModel.onScreenStarted()
                    Lifecycle.Event.ON_STOP -> viewModel.onScreenStopped()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ConversationListScreen(
        state = state,
        activeSiteId = activeSiteId,
        hubConnectionState = hubConnectionState,
        onTabSelected = viewModel::onTabSelected,
        onRefresh = viewModel::refresh,
        onClaim = viewModel::claim,
        onDismissClaimError = viewModel::dismissClaimError,
        onOpenConversation = { conversationId ->
            viewModel.onRowOpened(conversationId)
            onOpenConversation(conversationId)
        },
        onSignOut = onSignOut,
    )
}

/**
 * The stateless half — [ConversationListRoute] wires the [ago.chat.android.conversations.ConversationListViewModel]
 * above it; every Compose preview and every future UI test targets this function directly, the same
 * "route wires, screen renders" split `SignInHost`/its own private screens already establish.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationListScreen(
    state: ConversationListUiState,
    activeSiteId: String?,
    hubConnectionState: OperatorHubConnectionState,
    onTabSelected: (ConversationListTab) -> Unit,
    onRefresh: () -> Unit,
    onClaim: (String) -> Unit,
    onDismissClaimError: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    // `ago-console`'s own `useNow` hook, restated: the one clock read this screen makes, so every
    // elapsed-time label re-renders together rather than each row reading `OffsetDateTime.now()` on
    // its own recomposition schedule. Coarser than a second and finer than a minute, matching that
    // hook's own `ELAPSED_TICK_MS` reasoning.
    var now by remember { mutableStateOf(OffsetDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ELAPSED_TICK_MILLIS)
            now = OffsetDateTime.now()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(text = stringResource(R.string.conversation_list_title)) },
                        actions = {
                            TextButton(onClick = onSignOut) {
                                Text(text = stringResource(R.string.action_sign_out))
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        activeSiteId?.let { IdentifierText(id = it, style = MaterialTheme.typography.labelSmall) }
                        Spacer(modifier = Modifier.width(8.dp))
                        HubConnectionDebugRow(state = hubConnectionState)
                    }
                }
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    ConversationListTab.entries.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = state.selectedTab == tab,
                            onClick = { onTabSelected(tab) },
                            shape = SegmentedButtonDefaults.itemShape(index, ConversationListTab.entries.size),
                            label = { Text(text = labelFor(tab)) },
                        )
                    }
                }

                if (state.isStale) {
                    StaleBanner(onRefresh = onRefresh)
                }
                state.loadError?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                when {
                    !state.hasData -> LoadingBody()
                    state.selectedTab == ConversationListTab.Mine ->
                        MineList(rows = state.mine, now = now, onOpenConversation = onOpenConversation)

                    else ->
                        WaitingList(
                            rows = state.waiting,
                            now = now,
                            onClaim = onClaim,
                            onDismissClaimError = onDismissClaimError,
                        )
                }
            }
        }
    }
}

@Composable
private fun labelFor(tab: ConversationListTab): String =
    when (tab) {
        ConversationListTab.Mine -> stringResource(R.string.conversation_list_tab_mine)
        ConversationListTab.Waiting -> stringResource(R.string.conversation_list_tab_waiting)
    }

@Composable
private fun StaleBanner(onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.conversation_list_stale_banner),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRefresh) {
            Text(text = stringResource(R.string.conversation_list_refresh_action))
        }
    }
}

@Composable
private fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * `docs/backlog/26-14-*.md`'s own Done-when: "the list survives rotation and returning from the
 * background with its scroll position intact". [rememberLazyListState] restores its own scroll offset
 * from `rememberSaveable`'s internal `Saver` automatically — nothing here has to save or restore it by
 * hand — which covers both a configuration change (the `ViewModel` and this state alike survive it) and
 * a process death recreation (`rememberSaveable`'s own `Bundle` round trip, which a plain `remember`
 * would not survive).
 */
@Composable
private fun MineList(
    rows: List<ConversationRowUi>,
    now: OffsetDateTime,
    onOpenConversation: (String) -> Unit,
) {
    if (rows.isEmpty()) {
        EmptyBody(text = stringResource(R.string.conversation_list_mine_empty))
        return
    }

    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(rows, key = { it.conversationId }) { row ->
            MineRow(row = row, now = now, onClick = { onOpenConversation(row.conversationId) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun MineRow(
    row: ConversationRowUi,
    now: OffsetDateTime,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            VisitorDisplayPrefix(
                emojiCreature = row.emojiCreature,
                emojiFood = row.emojiFood,
                visitorName = row.visitorName,
                visitorId = row.visitorId,
            )
            Text(
                text = elapsedText(row.createdAt, now, R.string.conversation_list_opened_prefix),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.isNewlyAssigned) {
            Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                Text(text = stringResource(R.string.conversation_list_new_badge))
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (row.unreadCount > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.error) {
                Text(text = row.unreadCount.toString())
            }
        }
    }
}

@Composable
private fun WaitingList(
    rows: List<ConversationRowUi>,
    now: OffsetDateTime,
    onClaim: (String) -> Unit,
    onDismissClaimError: (String) -> Unit,
) {
    if (rows.isEmpty()) {
        EmptyBody(text = stringResource(R.string.conversation_list_waiting_empty))
        return
    }

    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(rows, key = { it.conversationId }) { row ->
            WaitingRow(
                row = row,
                now = now,
                onClaim = { onClaim(row.conversationId) },
                onDismissError = { onDismissClaimError(row.conversationId) },
            )
            HorizontalDivider()
        }
    }
}

/**
 * **No confirmation dialog, and no navigation on claim** — `ago-console`'s `ClaimConversationButton`'s
 * own doc comment: taking a conversation is the ordinary, reversible act this whole item exists to make
 * reachable. The button disables itself while [ConversationRowUi.isClaiming] rather than being hidden
 * behind a permission check — this app has no permission model yet to check against
 * (`scope-inventory.md`), so every operator who can see this screen at all may claim from it, the same
 * posture the console takes before `23-04`'s own `CONVERSATION_CLAIM_PERMISSION` existed.
 */
@Composable
private fun WaitingRow(
    row: ConversationRowUi,
    now: OffsetDateTime,
    onClaim: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                VisitorDisplayPrefix(
                    emojiCreature = row.emojiCreature,
                    emojiFood = row.emojiFood,
                    visitorName = row.visitorName,
                    visitorId = row.visitorId,
                )
                Text(
                    text = elapsedText(row.createdAt, now, R.string.conversation_list_waiting_since_prefix),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onClaim, enabled = !row.isClaiming) {
                Text(
                    text =
                        if (row.isClaiming) {
                            stringResource(R.string.conversation_list_claiming_label)
                        } else {
                            stringResource(R.string.conversation_list_claim_action)
                        },
                )
            }
        }
        // `docs/backlog/26-14-*.md`'s own Scope: "rendering the server's refusal as a refusal... never
        // retried into a success" — this text and this dismiss button are the whole of that rendering.
        // There is deliberately no retry affordance here at all, only acknowledgement.
        row.claimError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissError) {
                    Text(text = stringResource(R.string.action_dismiss))
                }
            }
        }
    }
}

@Composable
private fun EmptyBody(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun elapsedText(
    createdAt: String,
    now: OffsetDateTime,
    @StringRes prefixRes: Int,
): String {
    val prefix = stringResource(prefixRes)
    val label =
        when (val elapsed = elapsedSince(createdAt, now)) {
            is ElapsedLabel.Minutes -> {
                val count = elapsed.value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                pluralStringResource(R.plurals.conversation_list_elapsed_minutes, count, count)
            }

            is ElapsedLabel.Hours -> {
                val count = elapsed.value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                pluralStringResource(R.plurals.conversation_list_elapsed_hours, count, count)
            }

            is ElapsedLabel.Days -> {
                val count = elapsed.value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                pluralStringResource(R.plurals.conversation_list_elapsed_days, count, count)
            }

            ElapsedLabel.Unknown -> stringResource(R.string.conversation_list_elapsed_unknown)
        }
    return "$prefix $label"
}

private const val ELAPSED_TICK_MILLIS = 30_000L
