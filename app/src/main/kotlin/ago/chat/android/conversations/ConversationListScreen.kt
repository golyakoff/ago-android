package ago.chat.android.conversations

import ago.chat.android.R
import ago.chat.android.core.domain.conversations.ElapsedLabel
import ago.chat.android.core.domain.conversations.elapsedSince
import ago.chat.android.core.domain.visitorDisplayPrefixParts
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.ui.components.HubConnectionDebugRow
import ago.chat.android.ui.components.IdentifierText
import ago.chat.android.ui.components.VisitorAvatar
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    ConversationRow(
        row = row,
        now = now,
        elapsedPrefixRes = R.string.conversation_list_opened_prefix,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        if (row.unreadCount > 0) {
            UnreadBadge(count = row.unreadCount, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * `26-23`: the mockup's `.row` — avatar, then a `.rmain` column of `.rtop` (name + code, with the
 * elapsed time trailing) and `.rmeta` (the status pills), then whatever the tab puts at the trailing
 * edge. «Мои» and «Ожидают» share this because their rows are the same object drawn the same way; the
 * only genuine difference is that trailing slot (an unread badge on one, a claim button on the other),
 * which is why it is a parameter rather than two near-copies of a layout.
 *
 * **What the mockup draws that this does not, and why that is the honest outcome rather than a
 * shortfall.** Three of the mockup's own row elements have no field behind them in
 * [ConversationRowUi]/[ago.chat.android.core.domain.conversations.ConversationSummary], and this item
 * is presentation-only — it may not invent data:
 *
 * - `.rsnip`, the last-message snippet. Nothing on the queue row carries message text; the wire DTO
 *   does not send it. Omitted entirely rather than filled with a placeholder.
 * - `.rmeta`'s channel/tag pills ("Telegram", "Оплата", "Запись", "VK"). There is no channel and no
 *   tag on a conversation today — incoming-channel expansion is `Ago.Chat`'s Stage 14, not built.
 *   The one pill drawn here is the one with a real field behind it, [ConversationRowUi.isNewlyAssigned].
 * - `.rname`'s `.ename` ("Лиса · Апельсин") — a *name derived from the emoji pair* for a visitor who
 *   has no real name. `visitorDisplayPrefixParts` has no such derivation and neither does the console
 *   it mirrors, so a nameless visitor renders as "the short code alone", which is that function's own
 *   documented rule.
 *
 * All three are recorded in this item's report as gaps worth their own future items.
 */
@Composable
private fun ConversationRow(
    row: ConversationRowUi,
    now: OffsetDateTime,
    @StringRes elapsedPrefixRes: Int,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        // `.row{align-items:flex-start}` - a two-line row's avatar and badge sit level with the name,
        // not floated to the vertical middle of the block.
        verticalAlignment = Alignment.Top,
    ) {
        // `.row{gap:13px}` carried as the avatar's own trailing padding rather than as the `Row`'s
        // arrangement, so that a visitor with no emoji pair - for whom `VisitorAvatar` emits nothing
        // at all (see that composable's doc comment) - leaves no phantom gap where a circle would be.
        VisitorAvatar(
            emojiCreature = row.emojiCreature,
            emojiFood = row.emojiFood,
            modifier = Modifier.padding(end = RowGap),
        )
        Column(modifier = Modifier.weight(1f)) {
            ConversationRowIdentityLine(row = row, now = now, elapsedPrefixRes = elapsedPrefixRes)
            if (row.isNewlyAssigned) {
                // `.rmeta{display:flex; gap:6px; margin-top:6px; flex-wrap:wrap}` - a plain `Row`
                // rather than `FlowRow`, because exactly one pill can be drawn today and an
                // experimental-API opt-in to wrap a single child would be ceremony for nothing. The
                // day a second pill has a field behind it, this becomes `FlowRow`.
                Row(modifier = Modifier.padding(top = PillRowTopGap)) {
                    StatusPill(text = stringResource(R.string.conversation_list_new_badge))
                }
            }
        }
        trailing()
    }
}

/**
 * The mockup's `.rtop` — `.rname` (the visitor's name, then the short code, ellipsised together as one
 * flexible unit) with `.rtime` pinned at the trailing edge.
 *
 * The emoji pair is deliberately *not* drawn here: [VisitorAvatar] at the row's leading edge now
 * carries it. Rather than teach [ago.chat.android.ui.components.VisitorDisplayPrefix] to suppress its
 * own emoji half, this reads the same `:core:domain` function that composable reads —
 * [visitorDisplayPrefixParts], which already separates "the pair" from "the name" from "the id" — so
 * the rule about which parts are present is stated exactly once, in the one module that owns it, and
 * only the *layout* differs. Suppressing it via a flag on `VisitorDisplayPrefix` would not have been
 * enough anyway: this line needs ellipsising and a weighted name, which that composable's fixed `Row`
 * does not express, and which the thread screen's app-bar title must not have.
 */
@Composable
private fun ConversationRowIdentityLine(
    row: ConversationRowUi,
    now: OffsetDateTime,
    @StringRes elapsedPrefixRes: Int,
) {
    val parts = visitorDisplayPrefixParts(row.emojiCreature, row.emojiFood, row.visitorName, row.visitorId)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(modifier = Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
            parts.visitorName?.let { name ->
                Text(
                    text = name,
                    // `.rname{font-size:14.5px; font-weight:700}` - `titleMedium` is this app's own
                    // 15sp token-backed role, the nearest the scale has; only the weight is lifted,
                    // rather than an untraceable 14.5sp literal being introduced for one line.
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp),
                )
            }
            // `.rname .code{font-size:13px; color:var(--ink-soft)}` - the monospace face is
            // `IdentifierText`'s own, never restated at this call site.
            IdentifierText(
                id = parts.visitorId,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        Text(
            text = elapsedText(row.createdAt, now, elapsedPrefixRes),
            // `.rtime{font-size:11.5px; color:var(--ink-faint); flex:0 0 auto}`. `--ink-faint` has no
            // Material 3 `ColorScheme` slot of its own - the scheme's one "quieter than body text"
            // role is `onSurfaceVariant`, which `Theme.kt` already maps to `--ink-soft` - so this is
            // one token-step brighter than the mockup. Flagged in this item's report rather than
            // fixed by inventing a colour here.
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * The mockup's `.pill.brand` — a small rounded rectangle, brand-filled, its label set tight and bold.
 * Not Material 3's `Badge`, which is a circle/stadium sized for a numeral: the mockup draws a *label*
 * here and a *count* at the row's trailing edge, and those are two different shapes on purpose
 * (`.pill{border-radius:5px}` against `.badge{border-radius:10px}`).
 */
@Composable
private fun StatusPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(PillCornerRadius),
    ) {
        Text(
            text = text,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = PillLetterSpacing,
                ),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/**
 * The mockup's `.badge{min-width:20px; height:20px; padding:0 6px; border-radius:10px;
 * background:var(--brand)}` — a 20dp circle that widens into a stadium for a two-digit count, which is
 * what `CircleShape` (a 50%-of-the-shorter-side corner) already gives for free.
 *
 * Two deliberate departures from what this row drew before. Material 3's own `Badge` is 16dp tall with
 * 4dp of horizontal padding, visibly smaller than the mockup's 20dp — near enough to look like a
 * mistake rather than a variant, which is why the shape is stated here instead. And the fill moves from
 * `error` to `primary`: the mockup's unread count is brand-coloured, not red. An unread message is not
 * an error condition, and colouring it like one is the kind of thing an operator reads as alarm.
 */
@Composable
private fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = CircleShape,
    ) {
        Box(
            modifier =
                Modifier
                    .defaultMinSize(minWidth = BadgeMinSize, minHeight = BadgeMinSize)
                    .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
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
    Column(modifier = Modifier.fillMaxWidth()) {
        ConversationRow(
            row = row,
            now = now,
            elapsedPrefixRes = R.string.conversation_list_waiting_since_prefix,
        ) {
            Button(onClick = onClaim, enabled = !row.isClaiming, modifier = Modifier.padding(start = 8.dp)) {
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            ) {
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

// `26-23`: the mockup's own row metrics, named once here rather than repeated as bare literals at each
// call site, with the CSS rule each one comes from. Nothing below is a chosen number.
//
// `.row{gap:13px}`
private val RowGap = 13.dp

// `.rmeta{margin-top:6px}`
private val PillRowTopGap = 6.dp

// `.pill{border-radius:5px; letter-spacing:.04em}` — .04em of the pill's own 10.5px type.
private val PillCornerRadius = 5.dp
private val PillLetterSpacing = 0.42.sp

// `.badge{min-width:20px; height:20px}`
private val BadgeMinSize = 20.dp
