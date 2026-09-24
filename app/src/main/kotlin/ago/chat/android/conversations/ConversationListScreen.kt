package ago.chat.android.conversations

import ago.chat.android.R
import ago.chat.android.core.domain.conversations.ConversationStateLabel
import ago.chat.android.core.domain.conversations.ElapsedLabel
import ago.chat.android.core.domain.conversations.conversationStateLabel
import ago.chat.android.core.domain.conversations.elapsedSince
import ago.chat.android.core.domain.visitorDisplayPrefixParts
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.ui.components.AccountAvatarAction
import ago.chat.android.ui.components.VisitorAvatar
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.components.rememberTickingNow
import ago.chat.android.ui.components.russianPluralStringResource
import ago.chat.android.ui.components.shortElapsedText
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoWarningColors
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/**
 * `26-14`: the screen this whole item exists to build — see `docs/backlog/26-14-*.md`'s own Scope for
 * the five properties it has to prove. Obtains its own [ConversationListViewModel] via [hiltViewModel] —
 * independent of `ago.chat.android.signin.SignInViewModel`, which owns only the pre-session flow.
 *
 * `activeSiteId` is accepted and *told to the view model*, never drawn. It used to be drawn — `26-14`
 * put the short code on a line of its own so an operator holding several seats could see which one
 * this list was — and `26-32` removed that line, because `26-17`'s Settings screen came along and
 * answered the same question properly, and because the author, reading this screen daily, did not
 * recognise his own site's code when he saw it. What remains here is the real dependency and always
 * was: `onActiveSiteChanged` below. The screen still shows *the* active site's own queue by
 * construction (`ConversationsApi.fetchQueue` carries no site parameter of its own —
 * `X-Ago-Active-Site` is a client plugin, `docs/architecture.md` §Tenancy), so there is no second
 * source of truth to reconcile against, which is why the stateless half below no longer takes this
 * value at all.
 *
 * The hub connection's own start/stop lifecycle is owned by `OperatorHubConnectionLifecycle`
 * (process-wide), not by this screen — this composable's own `ON_START`/`ON_STOP` observer below drives
 * only [ConversationListViewModel.onScreenStarted]/[ConversationListViewModel.onScreenStopped], the
 * «Ожидают» poll's own visibility gate (that class's own doc comment on why it is narrower than
 * `ago-console`'s always-on timer).
 *
 * `26-75`: [ConversationListViewModel.claimedConversations] is collected here, not left for
 * `ConversationsTabHost` to reach into directly — this route already owns the identical collection for
 * a tapped row ([onOpenConversation] just below), so a successful claim opens the thread through the
 * exact same [onOpenConversation] callback rather than a second navigation pathway existing beside it.
 */
@Composable
public fun ConversationListRoute(
    activeSiteId: String?,
    hubConnectionState: OperatorHubConnectionState,
    onOpenConversation: (String) -> Unit,
    onSignOut: () -> Unit,
    operatorDisplayName: String? = null,
    operatorEmail: String? = null,
    onOpenSettings: () -> Unit = {},
    // `26-90`: the two permission-derived Booleans this screen needs, computed by the caller that
    // already holds the permission set (`AppShellScreen`'s own `conversationsTab` default) rather than
    // re-read here — the identical split its `teamTab`/`bookingsTab` slots already use, and the reason
    // neither this route nor its view model ever reaches for OperatorPermissionsApi itself.
    // `canSeeAllConversations` is `site:configure`, the permission
    // `GetAllConversationsForSiteHandler` actually enforces — deliberately *not* `conversation:read`,
    // which every operator holds and which only ever unlocks their own queue.
    canSeeAllConversations: Boolean = false,
    canEraseConversations: Boolean = false,
    viewModel: ConversationListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // `26-90`: told on every composition, deduplicated inside the view model - the identical
    // tolerant-of-repetition shape `onActiveSiteChanged` right below already uses.
    LaunchedEffect(canEraseConversations) { viewModel.onEraseCapabilityChanged(canEraseConversations) }

    // `26-17`: told on every mount/remount, however often that turns out to be - see
    // `ConversationListViewModel.onActiveSiteChanged`'s own doc comment for why the dedup that makes
    // this safe lives on the view model instance rather than here.
    LaunchedEffect(activeSiteId) { viewModel.onActiveSiteChanged(activeSiteId) }

    // `26-75`: a successful claim opens the thread the same way tapping a row in «Мои» already does -
    // through this same `onOpenConversation` callback, not a second one. Deliberately *not* routed
    // through `viewModel.onRowOpened` first (the wrapped lambda passed to `ConversationListScreen`
    // below does that for a tapped row) - a freshly claimed conversation was never in
    // `newlyAssignedIds` to begin with, so that call would be a documented no-op here, and going
    // through the raw callback keeps this event's own path traceable to exactly one cause.
    LaunchedEffect(viewModel) {
        viewModel.claimedConversations.collect { conversationId -> onOpenConversation(conversationId) }
    }

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
        hubConnectionState = hubConnectionState,
        tabs = remember(canSeeAllConversations) { conversationListTabs(canSeeAllConversations) },
        onTabSelected = viewModel::onTabSelected,
        onRefresh = viewModel::refresh,
        onClaim = viewModel::claim,
        onDismissClaimError = viewModel::dismissClaimError,
        onOpenConversation = { conversationId ->
            viewModel.onRowOpened(conversationId)
            onOpenConversation(conversationId)
        },
        onSignOut = onSignOut,
        operatorDisplayName = operatorDisplayName,
        operatorEmail = operatorEmail,
        onOpenSettings = onOpenSettings,
        onStateFilterToggled = viewModel::onStateFilterToggled,
        onLoadMoreAll = viewModel::loadMoreAll,
        onConfirmErasure = viewModel::confirmErasure,
        onDismissEraseFailure = viewModel::dismissEraseFailure,
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
    hubConnectionState: OperatorHubConnectionState,
    onTabSelected: (ConversationListTab) -> Unit,
    onRefresh: () -> Unit,
    onClaim: (String) -> Unit,
    onDismissClaimError: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onSignOut: () -> Unit,
    // `26-90`: which segments exist at all - two or three, never three with one greyed out
    // ([visibleConversationListTabs]'s own doc comment). Defaulted to the two every operator has, so
    // every existing caller and every existing test compiles and behaves exactly as before.
    tabs: List<ConversationListTab> = conversationListTabs(canSeeAllConversations = false),
    operatorDisplayName: String? = null,
    operatorEmail: String? = null,
    onOpenSettings: () -> Unit = {},
    onStateFilterToggled: (ConversationStateFilter) -> Unit = {},
    onLoadMoreAll: () -> Unit = {},
    onConfirmErasure: (String) -> Unit = {},
    onDismissEraseFailure: () -> Unit = {},
) {
    // `ago-console`'s own `useNow` hook, restated: the one clock read this screen makes, so every
    // elapsed-time label re-renders together rather than each row reading `OffsetDateTime.now()` on
    // its own recomposition schedule. `26-40`: the ticker itself moved to `ui.components.ElapsedText`
    // now that the thread app-bar's subtitle is a second caller of the identical mechanism.
    val now = rememberTickingNow()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            // `26-32`: one row of chrome, which is what the mockup draws. This used to be a `Column`
            // of two — the app bar, and under it a full-width row carrying the active site's short
            // code and the words «Соединение: Подключено». Both halves of that second row are gone:
            // the connection state now lives in `actions` (`26-77`: inside `AccountAvatarAction`'s own
            // presence dot, née a bare `HubConnectionDot`), and the site
            // code is not shown at all, because `26-17`'s Settings screen shows and switches the
            // active site properly and this line was the leftover of the days before it did. The
            // author, who reads this screen daily, did not recognise that code as a site id — which is
            // the clearest possible evidence it was not carrying its line's worth.
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.conversation_list_title)) },
                    actions = {
                        // `26-77`: the avatar replaces the old dot+kebab pair
                        // (`docs/backlog/26-77-*.md`'s own Found table) - a Type-A header's rightmost
                        // element, per that item's own Scope item 1.
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
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // `26-39`: `.seg{margin:4px 16px 12px}` - an asymmetric margin, not the uniform
                // `16.dp` this used to carry on all four sides; named beside the CSS it comes from
                // the same way this file's other row metrics already are (below, `26-23`'s own block).
                SingleChoiceSegmentedButtonRow(
                    modifier =
                        Modifier.fillMaxWidth().padding(
                            start = SegmentedRowHorizontalPadding,
                            end = SegmentedRowHorizontalPadding,
                            top = SegmentedRowTopPadding,
                            bottom = SegmentedRowBottomPadding,
                        ),
                ) {
                    // `26-90`: iterates the *visible* tabs, not `ConversationListTab.entries` - the
                    // enum now has a third member that most operators must never be offered, and
                    // `itemShape` needs the visible count too, or the rightmost visible segment keeps
                    // a middle segment's square right edge.
                    tabs.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = state.selectedTab == tab,
                            onClick = { onTabSelected(tab) },
                            shape = SegmentedButtonDefaults.itemShape(index, tabs.size),
                            label = { Text(text = segmentedTabLabel(tab = tab, count = segmentedCountFor(tab, state))) },
                            icon = {},
                        )
                    }
                }

                if (state.selectedTab == ConversationListTab.All) {
                    StatusFilterChip(selected = state.allFilter, onToggle = onStateFilterToggled)
                    AllReadOnlyNote()
                }

                if (state.isStale && state.selectedTab != ConversationListTab.All) {
                    StaleBanner(onRefresh = onRefresh)
                }
                val bannerFailure =
                    if (state.selectedTab == ConversationListTab.All) state.allLoadError else state.loadError
                bannerFailure?.let { reason ->
                    Text(
                        text = networkFailureText(reason),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (state.selectedTab == ConversationListTab.All) {
                    state.eraseFailure?.let { failure ->
                        EraseFailureBanner(failure = failure, onDismiss = onDismissEraseFailure)
                    }
                }

                when {
                    state.selectedTab == ConversationListTab.All ->
                        if (!state.allHasData) {
                            LoadingBody()
                        } else {
                            AllList(
                                rows = state.all,
                                now = now,
                                canErase = state.canErase,
                                isLoadingMore = state.isLoadingAll,
                                onLoadMore = onLoadMoreAll,
                                onConfirmErasure = onConfirmErasure,
                            )
                        }

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
        ConversationListTab.All -> stringResource(R.string.conversation_list_tab_all)
    }

/**
 * `26-39`: the mockup's own `<div class="on">Мои <span class="ct">3</span></div>` — a label followed,
 * in the same run of text, by a differently-styled count. That is inline text with one styled span, not
 * two independent blocks with a gap between them, which is why this builds one [AnnotatedString] rather
 * than reaching for a `Row` of two `Text`s the way [ConversationRowIdentityLine] does for its own three
 * *independent* pieces (name, badge, time) — the first use of [AnnotatedString] in this file, and the
 * right one, because the mockup itself nests the span inside the label's own text node.
 *
 * `count == null` (before [ConversationListUiState.hasData]) renders the bare label with no trailing
 * span at all — Scope item 2's own rule: no digit is drawn for a tab whose true count is not yet known.
 */
@Composable
private fun segmentedTabLabel(
    tab: ConversationListTab,
    count: Int?,
): AnnotatedString =
    buildAnnotatedString {
        append(labelFor(tab))
        if (count != null) {
            append(" ")
            withStyle(segmentedCountStyle()) {
                append(count.toString())
            }
        }
    }

/**
 * `.seg .ct{font-size:11.5px; font-weight:700; opacity:.85; font-variant-numeric:tabular-nums}` -
 * `labelMedium` is this app's 12sp token-backed role, the nearest the scale has to `11.5px`; only the
 * weight, color and numeric variant are lifted onto it, the same rule [ConversationRowIdentityLine]
 * already states for `.rname`'s own `14.5px`. The `.85` opacity reads over [LocalContentColor] rather
 * than a flat token, the same pattern `ThreadScreen`'s `MessageBubble` already uses for its own bubble
 * timestamp's `opacity:.72` - an alpha over whatever this label's own text color already is, not a
 * second, independent color decision.
 */
@Composable
private fun segmentedCountStyle() =
    MaterialTheme.typography.labelMedium
        .copy(
            fontWeight = FontWeight.Bold,
            color = LocalContentColor.current.copy(alpha = SEGMENTED_COUNT_ALPHA),
            fontFeatureSettings = "tnum",
        ).toSpanStyle()

/**
 * `26-39`'s own Scope item 2: the two counts are already in hand as the lengths of the two lists this
 * screen renders from ([ConversationListUiState.mine]/[ConversationListUiState.waiting]) - nothing
 * fetched, nothing computed in the view model. `null` before [ConversationListUiState.hasData] is the
 * "not yet known" case [segmentedTabLabel] renders as no count at all, never an invented `0`.
 */
private fun segmentedCountFor(
    tab: ConversationListTab,
    state: ConversationListUiState,
): Int? =
    if (!state.hasData) {
        null
    } else {
        when (tab) {
            ConversationListTab.Mine -> state.mine.size
            ConversationListTab.Waiting -> state.waiting.size
            // `26-90`: **no number on «Все», ever** - an explicit author decision, not an oversight.
            // The other two counts are free (they are the lengths of two lists this screen already
            // holds whole); this one is not, because the site-wide list is keyset-paged and never knows
            // its own full length. Drawing `state.all.size` would put "how many have I scrolled past"
            // on the segment while looking exactly like "how many are there", and asking the server
            // for the real number is a `COUNT(*)` over tens of thousands of closed conversations on
            // every open of this screen, for a digit nobody acts on (`26-90`'s own Out of scope).
            ConversationListTab.All -> null
        }
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
    // `26-30`: the unread badge used to be this row's own `trailing` slot - the row's far trailing
    // edge, opposite the name. It now sits beside the name instead (`ConversationRowIdentityLine`'s own
    // doc comment), so «Мои» has nothing left to pass here; «Ожидают» still does, for its claim button.
    //
    // `26-64`: `onClickLabel` names the action a tap actually performs. Without it, TalkBack announces
    // Compose's generic clickable hint ("double-tap to activate") - true of any clickable element and
    // therefore useless for telling this row apart from the claim button two composables over. The
    // label replaces only the *hint*; the row's own `contentDescription` (set on `ConversationRow`
    // below, via `mergeDescendants`) still carries what the row itself says.
    ConversationRow(
        row = row,
        now = now,
        modifier =
            Modifier.clickable(
                onClickLabel = stringResource(R.string.conversation_list_open_action),
                onClick = onClick,
            ),
    )
}

/**
 * `26-30`: the mockup's `.row` — avatar, then a `.rmain` column of `.rtop` (name, unread badge, and the
 * bold creation-time, all trailing-aligned as a unit), `.rmid` (the snippet and its own lighter
 * last-message time, when there is a snippet at all) and `.rmeta` (the status pills), then whatever the
 * tab puts at the trailing edge outside this column entirely. «Мои» and «Ожидают» share this because
 * their rows are the same object drawn the same way; the only genuine difference is that outer trailing
 * slot (a claim button on «Ожидают», nothing on «Мои» — the unread badge moved inside `.rtop` itself,
 * see [ConversationRowIdentityLine]), which is why it stays a parameter rather than two near-copies of
 * a layout.
 *
 * **What the mockup draws that this still does not, and why that remains the honest outcome rather than
 * a shortfall.** `.rmeta`'s channel/tag pills ("Telegram", "Оплата", "Запись", "VK") have no field
 * behind them on [ConversationRowUi] — there is no channel and no tag on a conversation today,
 * incoming-channel expansion is `Ago.Chat`'s Stage 14, not built. The one pill drawn here is the one
 * with a real field behind it, [ConversationRowUi.isNewlyAssigned]. Recorded in this item's report as a
 * gap worth its own future item, same as `26-23`'s report recorded this row's now-closed gaps.
 *
 * `26-64`: **one accessibility node, one sentence.** Before this, none of the children below carried any
 * semantics of its own, so TalkBack walked the five/six `Text`/`Box` nodes one at a time — a name, then
 * an unread digit with no context, then a bold time and a quiet one that sound identical, then a
 * snippet — as five disconnected fragments (`docs/backlog/26-64-*.md`'s own Found section). Compose's
 * ordinary shape for "this whole subtree is one thing" is `Modifier.semantics(mergeDescendants = true)`
 * with an explicit `contentDescription` that overrides whatever text the merge would otherwise
 * concatenate from the children — the identical mechanism [ago.chat.android.ui.components.HubConnectionDot]
 * already uses on a childless `Box`, applied here to a `Row` with several. [VisitorAvatar]'s own
 * `clearAndSetSemantics {}` (that composable's own doc comment) keeps the emoji pair's glyph names out of
 * what gets merged, so [conversationRowContentDescription] is the *only* source of what this node says.
 *
 * On «Ожидают», [trailing] is the claim `Button` — deliberately still a child of this same `Row`, not
 * moved outside it. Compose does not fold an actionable descendant (one with its own click action) into
 * an ancestor's `mergeDescendants = true`: an element a person can act on stays independently reachable
 * by design, which is the built-in rule this composable relies on rather than re-implements — the same
 * reason a `Card(onClick = …)` containing an `IconButton` leaves that button separately double-tappable
 * elsewhere in Compose Material 3. `ConversationRowSemanticsTest` asserts this directly rather than
 * trusting the rule by citation alone.
 */
@Composable
private fun ConversationRow(
    row: ConversationRowUi,
    now: OffsetDateTime,
    modifier: Modifier = Modifier,
    // `26-90`: the third line — the status pill and `Сообщений: N` — is the «Все» tab's own addition
    // and nothing else's, so it is a flag on the shared row rather than a fourth near-copy of this
    // layout. `false` everywhere but [AllRow], which keeps «Мои»/«Ожидают» byte-for-byte what they were.
    showStatusLine: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val description = conversationRowContentDescription(row = row, now = now, includeStatusLine = showStatusLine)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(CONVERSATION_ROW_CONTENT_TEST_TAG)
                .semantics(mergeDescendants = true) { contentDescription = description },
        // `.row{align-items:flex-start}` - a multi-line row's avatar sits level with the name, not
        // floated to the vertical middle of the block.
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
            ConversationRowIdentityLine(row = row, now = now)
            // `.rmid` - only when there is a snippet to show at all; never an empty line, and
            // therefore never an orphaned timestamp either (`ConversationRowSnippetLine`'s own doc
            // comment on why this reads `lastMessagePreview` alone, not `lastMessageAt`).
            row.lastMessagePreview?.let { preview ->
                // `26-76`: exactly one module (Ago.Calendar) is wired to any site today, so "non-null
                // contentKind" and "came from Calendar" are the same fact by elimination, not something
                // Chat's own contract actually knows (adr/0065 decision 4) - a second wired module would
                // need this icon choice revisited.
                val snippetText = if (row.lastMessageContentKind != null) "📅 $preview" else preview
                ConversationRowSnippetLine(preview = snippetText, lastMessageAt = row.lastMessageAt, now = now)
            }
            if (row.isNewlyAssigned) {
                // `.rmeta{display:flex; gap:6px; margin-top:6px; flex-wrap:wrap}` - a plain `Row`
                // rather than `FlowRow`, because exactly one pill can be drawn today and an
                // experimental-API opt-in to wrap a single child would be ceremony for nothing. The
                // day a second pill has a field behind it, this becomes `FlowRow`.
                Row(modifier = Modifier.padding(top = PillRowTopGap)) {
                    StatusPill(text = stringResource(R.string.conversation_list_new_badge))
                }
            }
            if (showStatusLine) {
                ConversationRowStatusLine(row = row)
            }
        }
        trailing()
    }
}

/**
 * `26-64`: the row's one spoken sentence, in the same left-to-right order the row draws it — who
 * ([ago.chat.android.core.domain.VisitorDisplayPrefixParts.displayName]), how old
 * ([ConversationRowUi.createdAt]), how many unread (named as unread, never [UnreadBadge]'s own bare
 * digit), and what was last said (the snippet, with its own elapsed time worded apart from the first so
 * the two don't sound identical).
 *
 * Assembled with [listOfNotNull] and [joinToString], the same shape
 * [ago.chat.android.core.domain.visitorDisplayPrefixParts]'s own module already uses for the *visible*
 * prefix text (`visitorDisplayPrefixText`'s own doc comment: "each present part supplies its own trailing
 * space... no part ever supplies a leading one") — applied here to the *spoken* description instead, with
 * Kotlin's own tool for "each optional part supplies itself, and a missing one leaves no trace" rather
 * than that function's hand-rolled per-part trailing space. A row with no name, no snippet and no unread
 * count simply has a shorter list to join — never a stray leading/trailing separator, never two in a row.
 */
@Composable
private fun conversationRowContentDescription(
    row: ConversationRowUi,
    now: OffsetDateTime,
    // `26-90`: the third line is spoken only where it is drawn. A «Мои» row that silently gained
    // "Сообщений: 0" in its spoken sentence would be announcing a number the screen does not show and
    // the queue read does not populate - worse than saying nothing.
    includeStatusLine: Boolean = false,
): String {
    val parts = visitorDisplayPrefixParts(row.emojiCreature, row.emojiFood, row.visitorName, row.visitorId)
    val openedClause =
        conversationRowElapsedClause(
            elapsed = elapsedSince(row.createdAt, now),
            unknownRes = R.string.conversation_row_opened_unknown,
            agoRes = R.string.conversation_row_opened_ago,
        )
    val unreadClause =
        row.unreadCount.takeIf { it > 0 }?.let { count ->
            russianPluralStringResource(
                count = count.toLong(),
                one = R.string.conversation_row_unread_one,
                few = R.string.conversation_row_unread_few,
                many = R.string.conversation_row_unread_many,
            )
        }
    val snippetClause =
        row.lastMessagePreview?.let { preview ->
            val lastMessageClause =
                row.lastMessageAt?.let { at ->
                    conversationRowElapsedClause(
                        elapsed = elapsedSince(at, now),
                        unknownRes = R.string.conversation_row_last_message_unknown,
                        agoRes = R.string.conversation_row_last_message_ago,
                    )
                }
            listOfNotNull(preview, lastMessageClause).joinToString(separator = ", ")
        }

    // `26-90`: the same two things the third line draws, in the same order - the status (or the held
    // "erasing" word, which is the one fact an operator most needs to hear before acting on this row
    // again) and the total, worded as the visible line words it rather than as a bare digit.
    val statusClause =
        if (!includeStatusLine) {
            null
        } else if (row.isErasing) {
            stringResource(R.string.conversation_list_erasing_label)
        } else {
            conversationStatusPillText(row)
        }
    val countClause =
        if (includeStatusLine) stringResource(R.string.conversation_list_message_count, row.messageCount) else null

    return listOfNotNull(parts.displayName, openedClause, unreadClause, snippetClause, statusClause, countClause)
        .joinToString(separator = ". ")
}

/**
 * One [ElapsedLabel] spoken two different ways depending on which of the row's two timestamps it came
 * from — [agoRes] is `conversation_row_opened_ago` ("открыт %1$s назад") or
 * `conversation_row_last_message_ago` ("последнее сообщение %1$s назад"); [unknownRes] is that same
 * call's own "time unknown" wording, never [ElapsedLabel.Unknown] rendered as "0 minutes ago"
 * ([elapsedSince]'s own doc comment states that rule for the visible form; nothing about it changes for
 * the spoken one).
 */
@Composable
private fun conversationRowElapsedClause(
    elapsed: ElapsedLabel,
    @StringRes unknownRes: Int,
    @StringRes agoRes: Int,
): String =
    when (elapsed) {
        ElapsedLabel.Unknown -> stringResource(unknownRes)

        is ElapsedLabel.Minutes ->
            stringResource(
                agoRes,
                russianPluralStringResource(
                    elapsed.value,
                    R.string.conversation_row_elapsed_minutes_one,
                    R.string.conversation_row_elapsed_minutes_few,
                    R.string.conversation_row_elapsed_minutes_many,
                ),
            )

        is ElapsedLabel.Hours ->
            stringResource(
                agoRes,
                russianPluralStringResource(
                    elapsed.value,
                    R.string.conversation_row_elapsed_hours_one,
                    R.string.conversation_row_elapsed_hours_few,
                    R.string.conversation_row_elapsed_hours_many,
                ),
            )

        is ElapsedLabel.Days ->
            stringResource(
                agoRes,
                russianPluralStringResource(
                    elapsed.value,
                    R.string.conversation_row_elapsed_days_one,
                    R.string.conversation_row_elapsed_days_few,
                    R.string.conversation_row_elapsed_days_many,
                ),
            )
    }

/** `26-64`: `ConversationRowSemanticsTest`'s own hook onto the merged row node - a `testTag` rather than
 * a query built on the (Russian, wording-sensitive) `contentDescription` itself, so a future rewording
 * of the sentence doesn't also break the test that checks its shape. */
internal const val CONVERSATION_ROW_CONTENT_TEST_TAG = "conversationRowContent"

/**
 * `26-30`: the mockup's `.rtop` — `display:flex; align-items:baseline; gap:8px` over exactly three
 * possible children in this order: `.rname` (flex:1, ellipsised), `.badge` (flex:0 0 auto, only when
 * [ConversationRowUi.unreadCount] is positive) and `.rtime-strong` (flex:0 0 auto, always). Read
 * left-to-right off the regenerated mockup Artifact's own "СТАЛО" row (`26-23`'s reference link,
 * confirmed against this item's own before/after image): the badge moved here from the row's far
 * trailing edge, and the two former roles of that edge — a badge, and the elapsed time — traded places,
 * which is why [UnreadBadge] now renders inside this `Row` rather than as [ConversationRow]'s own
 * `trailing` slot.
 *
 * `.rname`'s text is [ago.chat.android.core.domain.VisitorDisplayPrefixParts.displayName] — the
 * visitor's real name, or, since `26-30`, the emoji pair's own localized fallback label ("Лиса ·
 * Апельсин") when there is no real name — never the short code, which this row no longer draws at all
 * (a nameless, pair-less visitor — a row predating the emoji-pair backfill — renders no name text on
 * this line at all, the one case `displayName` returns `null` for; every real visitor today has a pair,
 * so this is not a case any current row hits).
 *
 * The emoji pair itself is deliberately *not* drawn here: [VisitorAvatar] at the row's leading edge
 * already carries it. Rather than deriving a second, ad hoc "just the name" rule, this reads
 * [visitorDisplayPrefixParts] directly — the same `:core:domain` function
 * `ago.chat.android.thread.ThreadTitleBlock` reads for the identical reason (`26-40`) — which already
 * separates "the pair" from "the name" from "the id", so the rule about which parts are present is
 * stated exactly once, in the one module that owns it, and only the *layout* differs per caller.
 *
 * `.rtime-strong{font-size:13px; font-weight:700; color:var(--ink)}` — bold and full-strength ink,
 * unlike the snippet line's own `.rtime` (`ConversationRowSnippetLine`): the mockup's own comment on
 * this class says why — "this instant is a fact about the whole dialog", the author's own framing for
 * why it gets the name line's own weight rather than a quieter one.
 */
@Composable
private fun ConversationRowIdentityLine(
    row: ConversationRowUi,
    now: OffsetDateTime,
) {
    val parts = visitorDisplayPrefixParts(row.emojiCreature, row.emojiFood, row.visitorName, row.visitorId)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RtopGap),
    ) {
        parts.displayName?.let { name ->
            Text(
                text = name,
                // `.rname{font-size:14.5px; font-weight:700}` - `titleMedium` is this app's own
                // 15sp token-backed role, the nearest the scale has; only the weight is lifted,
                // rather than an untraceable 14.5sp literal being introduced for one line.
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (row.unreadCount > 0) {
            UnreadBadge(count = row.unreadCount)
        }
        Text(
            text = shortElapsedText(row.createdAt, now),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * `26-30`: the mockup's `.rmid` — `.rsnip` (flex:1, ellipsised, `13px`/`--ink-soft`) beside `.rtime`
 * (flex:0 0 auto, regular weight, `11.5px`/`--ink-faint`), `gap:8px`, drawn only by
 * [ConversationRow] when [ConversationRowUi.lastMessagePreview] is present at all.
 *
 * [lastMessageAt] is read independently of whether it is present — `26-29`'s own contract allows a
 * populated [lastMessageAt] beside a `null` preview (a system message, or one with no safe-to-preview
 * content), and that case draws no time either: this whole line already did not exist for that row (the
 * caller's own `lastMessagePreview?.let` gate), so there is no orphaned timestamp to worry about here.
 * The `null`-time branch below exists only for the narrower, largely theoretical case of a preview with
 * no timestamp at all, which the backend does not currently produce.
 *
 * Regular weight throughout, unlike the name line's own bold `.rtime-strong`
 * ([ConversationRowIdentityLine]) — the mockup's own comment on `.rmid`: "both parts describe the same
 * event (the last words, and when they were said), so they read as one whole, not a heading with an
 * afterthought."
 */
@Composable
private fun ConversationRowSnippetLine(
    preview: String,
    lastMessageAt: String?,
    now: OffsetDateTime,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = SnippetTopGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RtopGap),
    ) {
        Text(
            text = preview,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        lastMessageAt?.let { at ->
            Text(
                text = shortElapsedText(at, now),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * The mockup's `.pill.brand` — a small rounded rectangle, brand-filled, its label set tight and bold.
 * Not Material 3's `Badge`, which is a circle/stadium sized for a numeral: the mockup draws a *label*
 * here and a *count* at the row's trailing edge, and those are two different shapes on purpose
 * (`.pill{border-radius:5px}` against `.badge{border-radius:10px}`).
 *
 * `26-90`: the two colour parameters default to what this composable has always drawn (`.pill.brand`),
 * so the existing «Новое» call site is unchanged. The «Все» tab's own status pill is the mockup's
 * *plain* `.pill` — a quiet surface, not a brand-filled one — and its «Стирается…» variant is
 * `--danger`; three fills, one shape, rather than three near-copies of the same `Surface`.
 */
@Composable
private fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(PillCornerRadius),
    ) {
        Text(
            text = text,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = PillLetterSpacing,
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
 * **No confirmation dialog on claim, and this button — not the whole row — is still the trigger.**
 * `26-75` corrected which console screen this actually mirrors: `ago-console`'s
 * `ClaimConversationButton` (its own "no navigation" doc comment) is real, but it only ever appears on
 * `AdminConversationsPage`/`SearchConversationsPage`, two monitoring screens where a mis-click costs
 * nothing more than one extra row claimed while scanning a table — it is not the console's own
 * primary-workspace behaviour. That workspace's actual equivalent of this «Ожидают» tab is
 * `WorkspaceLayout`/`ConversationList.tsx`, whose waiting row is a `NavLink` that **does** navigate on
 * claim (`23-04`), which is what `ConversationListViewModel.claim`'s own `ClaimResult.Claimed` branch
 * now does here too. What stays true to `ClaimConversationButton`'s reasoning is narrower than its
 * headline: the *button* remains this row's only claim trigger — the row itself still is not one, since
 * a mis-tap in a phone-sized list costs more than it does in the console's own table — only what happens
 * *after* a successful claim changed. The button disables itself while [ConversationRowUi.isClaiming]
 * rather than being hidden behind a permission check — this app has no permission model yet to check
 * against (`scope-inventory.md`), so every operator who can see this screen at all may claim from it,
 * the same posture the console takes before `23-04`'s own `CONVERSATION_CLAIM_PERMISSION` existed.
 */
@Composable
private fun WaitingRow(
    row: ConversationRowUi,
    now: OffsetDateTime,
    onClaim: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ConversationRow(row = row, now = now) {
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
                    // `26-59`: only [ClaimErrorUi.ServerRefusal] is a sentence the server itself wrote;
                    // [ClaimErrorUi.Unavailable] renders through the identical classification-driven
                    // vocabulary every other network failure in this app now uses.
                    text =
                        when (error) {
                            is ClaimErrorUi.ServerRefusal -> error.detail
                            is ClaimErrorUi.Unavailable -> networkFailureText(error.reason)
                        },
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

/**
 * `26-90`: the mockup's `.chips > .chip.on` — one chip under the segmented control, opening the
 * «Показывать» panel. A Material 3 [FilterChip] in its selected state, because that is what the
 * mockup's `.chip.on` is (a filled, brand-tinted chip, not an outlined one), with the chevron the
 * mockup draws on it.
 *
 * **No number on the chip, and none in the panel.** `26-90`'s own Out of scope names this explicitly:
 * a per-status tally is a `COUNT(*)` over a site's whole history, run on every open of the screen, for
 * a digit nobody acts on. Three words, three checkboxes, nothing else — see [segmentedCountFor]'s own
 * comment for the same rule applied to the segment.
 *
 * The panel itself is a [DropdownMenu]: an anchored popup over a scrim, which is exactly what the
 * mockup's `.popover.filters` + `.scrim` pair is, and the same mechanism this app's own account menu
 * (`AccountAvatarAction`) already uses — rather than a bottom sheet, which would be a different
 * interaction pattern for the same "pick from a short list" job.
 */
@Composable
private fun StatusFilterChip(
    selected: Set<ConversationStateFilter>,
    onToggle: (ConversationStateFilter) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier = Modifier.padding(start = SegmentedRowHorizontalPadding, bottom = SegmentedRowBottomPadding)) {
        FilterChip(
            selected = true,
            onClick = { expanded = true },
            label = { Text(text = stringResource(R.string.conversation_list_filter_chip)) },
            trailingIcon = {
                Icon(
                    imageVector = AgoIcons.ChevronRight,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(FilterChipChevronSize)
                            // The mockup rotates the one right-pointing `#i-chev` rather than shipping
                            // a second glyph - `rotate(90deg)` on the closed frame, `rotate(-90deg)` on
                            // the open one. Same here, from the same one icon.
                            .rotate(
                                if (expanded) {
                                    FILTER_CHIP_CHEVRON_OPEN_ROTATION
                                } else {
                                    FILTER_CHIP_CHEVRON_CLOSED_ROTATION
                                },
                            ),
                )
            },
            modifier = Modifier.testTag(STATUS_FILTER_CHIP_TEST_TAG),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                text = stringResource(R.string.conversation_list_filter_heading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
            )
            ConversationStateFilter.entries.forEach { filter ->
                DropdownMenuItem(
                    text = { Text(text = stateFilterLabel(filter)) },
                    leadingIcon = {
                        // `onCheckedChange = null` - the row itself is the click target, so the box
                        // must not be a second, separately-tappable one inside it. That is Compose's
                        // own documented shape for a checkbox that only *displays* state, and it is
                        // also what keeps TalkBack from announcing two actions for one row.
                        Checkbox(checked = filter in selected, onCheckedChange = null)
                    },
                    onClick = { onToggle(filter) },
                )
            }
        }
    }
}

@Composable
private fun stateFilterLabel(filter: ConversationStateFilter): String =
    when (filter) {
        ConversationStateFilter.NotStarted -> stringResource(R.string.conversation_list_state_not_started)
        ConversationStateFilter.Assigned -> stringResource(R.string.conversation_list_state_assigned)
        ConversationStateFilter.Closed -> stringResource(R.string.conversation_list_state_closed)
    }

/**
 * `26-90`: one quiet line saying the list is for looking at, not for opening — the only addition this
 * screen makes that the approved frames do not draw, and it is here because the frames were drawn
 * against an assumption the server does not hold (see [AllRow]'s own doc comment for the full chain).
 * Without it, every tap on this tab does nothing and reads as a broken row; with it, the tab is
 * honest about being a supervisor's view. `ago-console`'s own admin page carries the same limitation
 * and the same explanation.
 */
@Composable
private fun AllReadOnlyNote() {
    Text(
        text = stringResource(R.string.conversation_list_all_read_only_note),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.padding(
                start = SegmentedRowHorizontalPadding,
                end = SegmentedRowHorizontalPadding,
                bottom = SegmentedRowBottomPadding,
            ),
    )
}

/** `26-90`: an erasure request that was refused or never landed, shown once above the list and
 * dismissed by hand — the identical "shown once, never retried automatically" posture [WaitingRow]'s
 * own claim error already takes, hoisted to the list because the row it concerns is still sitting in
 * that list exactly where it was. */
@Composable
private fun EraseFailureBanner(
    failure: EraseFailureUi,
    onDismiss: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    ) {
        Text(
            text =
                when (failure) {
                    is EraseFailureUi.ServerRefusal -> failure.detail
                    is EraseFailureUi.Unavailable -> networkFailureText(failure.reason)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(text = stringResource(R.string.action_dismiss))
        }
    }
}

/**
 * `26-90`: the «Все» tab's own list. The same rows as «Мои»/«Ожидают» — [ConversationRow], not a
 * table and not a second row composable — plus the third meta line and, for a holder of
 * `conversation:erase`, the swipe-revealed destructive action.
 *
 * **Paged on scroll, newest first, and never re-sorted here.** The server already returns this page in
 * keyset order (`c.id desc`, and conversation ids are UUID v7 so id order *is* creation order); a
 * client-side sort would at best repeat that and at worst disagree with the cursor the next page is
 * fetched with. The trailing item below is the whole paging trigger: it is composed only when the list
 * has actually been scrolled to its end, and [ConversationListViewModel.loadMoreAll] is guarded against
 * firing twice for one page, so a recomposition while a page is in flight costs nothing.
 */
@Composable
private fun AllList(
    rows: List<ConversationRowUi>,
    now: OffsetDateTime,
    canErase: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onConfirmErasure: (String) -> Unit,
) {
    if (rows.isEmpty()) {
        EmptyBody(text = stringResource(R.string.conversation_list_all_empty))
        return
    }

    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(rows, key = { it.conversationId }) { row ->
            AllRow(
                row = row,
                now = now,
                canErase = canErase,
                onConfirmErasure = { onConfirmErasure(row.conversationId) },
            )
            HorizontalDivider()
        }
        item(key = ALL_LIST_LOAD_MORE_KEY) {
            LaunchedEffect(rows.size) { onLoadMore() }
            if (isLoadingMore) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(LoadMoreSpinnerSize))
                }
            }
        }
    }
}

/**
 * `26-90`: one «Все» row, and the whole swipe mechanism.
 *
 * **Why a hand-rolled reveal rather than Material 3's `SwipeToDismissBox`.** That composable's anchors
 * are the row's own full width — settling at its `EndToStart` anchor slides the row entirely off and
 * shows the background across the whole row. What the approved frame draws is a *partial* reveal: the
 * row translated by exactly the action panel's own width, still readable beside it. So the offset is
 * driven here, by `Modifier.draggable` (stable API) clamped to [EraseActionWidth] and settled to one
 * of two positions on release — closed, or open — which is the same two-anchor behaviour with the
 * anchor this design actually has.
 *
 * **The gesture is not attached at all without `conversation:erase`.** Not attached-and-refused, not
 * attached-with-a-disabled-button: `26-90`'s own Scope says "the row does not swipe at all", the same
 * "hide, don't disable" rule the thread screen's attach control follows. An operator who cannot erase
 * has a row that behaves exactly like «Мои»'s.
 *
 * **The row is not tappable, and that is a server fact rather than a layout choice.** `26-90`'s own
 * Scope expected "the existing unchanged thread screen"; the server does not allow it. Opening a
 * thread goes through `OperatorHub.JoinConversationAsync`, which calls `AssignConversation` →
 * `Conversation.AssignTo` — and that method accepts only a `Waiting` conversation. So a tap here would
 * do one of three things, none of them "read the history": **claim** a waiting conversation out from
 * under the queue (a real write, on a row an administrator is only supervising), throw
 * `InvalidConversationStateException` for one assigned to somebody else, or throw for a closed one —
 * which is most of this tab. `ago-console`'s own `AdminConversationsPage` reached the identical
 * conclusion and is read-only for exactly this reason (its own doc comment: "deliberately read-only
 * summary data, not a way to open an arbitrary conversation's message thread ... doing so would be a
 * materially bigger change than this backlog item scoped"), which is also what this item's own One
 * promise says — "the way they already can from the console". Reported as a scope finding; the line
 * under the filter chip ([AllReadOnlyNote]) is what stops a dead tap from reading as a bug.
 *
 * **A held ("erasing") row cannot be swiped again** — a second erase request for a conversation
 * already being erased is noise, not intent.
 */
@Composable
private fun AllRow(
    row: ConversationRowUi,
    now: OffsetDateTime,
    canErase: Boolean,
    onConfirmErasure: () -> Unit,
) {
    val swipeable = canErase && !row.isErasing
    val revealWidthPx = with(LocalDensity.current) { EraseActionWidth.toPx() }
    var offsetX by remember(row.conversationId) { mutableFloatStateOf(0f) }
    var confirming by rememberSaveable(row.conversationId) { mutableStateOf(false) }
    val animatedOffset by animateFloatAsState(targetValue = offsetX, label = "eraseReveal")

    // Closes the reveal again whenever the gesture stops being available - a row that entered the
    // erasing state while held open must not be left sitting on a red panel it can no longer act on.
    LaunchedEffect(swipeable) { if (!swipeable) offsetX = 0f }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (swipeable && offsetX < 0f) {
            // `Modifier.matchParentSize()`, not `align(CenterEnd)` + `fillMaxHeight()`: this `Box` sizes
            // itself from the row below, and inside a `LazyColumn` item the incoming height constraint
            // is unbounded - a child asking to fill it would be asking to fill infinity. `matchParentSize`
            // is Compose's own answer for "as big as the parent already decided to be, and contributing
            // nothing to that decision", which is exactly what a background panel is.
            Row(modifier = Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
                EraseAction(onClick = { confirming = true })
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier =
                Modifier
                    .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                    .then(
                        if (swipeable) {
                            Modifier.draggable(
                                orientation = Orientation.Horizontal,
                                state =
                                    rememberDraggableState { delta ->
                                        offsetX = (offsetX + delta).coerceIn(-revealWidthPx, 0f)
                                    },
                                onDragStopped = {
                                    offsetX = if (offsetX < -revealWidthPx / 2f) -revealWidthPx else 0f
                                },
                            )
                        } else {
                            Modifier
                        },
                    ),
        ) {
            ConversationRow(row = row, now = now, showStatusLine = true)
        }
    }

    if (confirming) {
        EraseConfirmDialog(
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                offsetX = 0f
                onConfirmErasure()
            },
        )
    }
}

/**
 * The mockup's `.swipe-del` — a solid `--danger` panel carrying the redrawn `delete_forever` glyph
 * over a two-line «Удалить» / «диалог» caption, and nothing else on it. Two `Text`s rather than one
 * string containing a newline, so neither line can be wrapped or hyphenated by a font this app does
 * not control.
 */
@Composable
private fun EraseAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        modifier = modifier.width(EraseActionWidth).fillMaxHeight(),
    ) {
        Column(
            modifier =
                Modifier
                    .clickable(
                        onClickLabel = stringResource(R.string.conversation_list_erase_action_description),
                        onClick = onClick,
                    ).testTag(ERASE_ACTION_TEST_TAG)
                    .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(EraseActionGap, Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = AgoIcons.TrashForever,
                contentDescription = null,
                modifier = Modifier.size(EraseActionIconSize),
            )
            Text(
                text = stringResource(R.string.conversation_list_erase_action_line_one),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.conversation_list_erase_action_line_two),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/**
 * `26-90`: the confirmation between the swipe and the request. Erasure is irreversible on the server
 * (`RequestConversationErasureHandler`) and a swipe is a gesture a pocket can perform — those two
 * facts together are the whole argument for a dialog here, where «Ожидают»'s own claim button
 * deliberately has none (a mis-claimed conversation costs one release).
 */
@Composable
private fun EraseConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.conversation_list_erase_confirm_title)) },
        text = { Text(text = stringResource(R.string.conversation_list_erase_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.conversation_list_erase_confirm_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * `26-90`: the row's third line, drawn only on «Все» — the mockup's `.rmeta.split`: a status pill
 * hard left, `Сообщений: N` hard right.
 *
 * `Сообщений: N` is **a total**, and it is drawn in the snippet line's own quiet weight and colour
 * rather than as a `.badge`, for exactly the reason the mockup's own caption gives: a blue badge in
 * this product always means *unread*, and this number is the whole length of the conversation. There
 * is no unread badge on this tab at all — [ConversationRowIdentityLine] still draws one from
 * [ConversationRowUi.unreadCount], but every row of this list carries `0` there, because the site-wide
 * read has no "this operator's unread" to report for a conversation that was never theirs.
 */
@Composable
private fun ConversationRowStatusLine(row: ConversationRowUi) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = PillRowTopGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (row.isErasing) {
            // `--danger-tint`/`--danger`, the mockup's own `.pill.bad` pair - the same tint family the
            // swipe panel uses, one step quieter, because this pill reports a state rather than
            // offering an action.
            StatusPill(
                text = stringResource(R.string.conversation_list_erasing_label),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            conversationStatusPill(row)
        }
        Text(
            // `.rcount{font-size:11.5px; color:var(--ink-faint); font-variant-numeric:tabular-nums}` -
            // the snippet line's own quiet weight, never the blue `.badge`, because in this product a
            // badge always means unread and this is the conversation's whole length.
            text = stringResource(R.string.conversation_list_message_count, row.messageCount),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * The pill's own words, read off [ConversationRowUi.state] through
 * [ago.chat.android.core.domain.conversations.conversationStateLabel] rather than by comparing wire
 * strings here — the classification is `:core:domain`'s, the prose is this screen's (that function's
 * own doc comment).
 *
 * `null` for [ConversationStateLabel.Unknown] and for [ConversationStateLabel.Pending]: an empty or
 * unrecognised spelling draws no word at all rather than a guessed one, and a `Pending` conversation
 * is one a visitor opened and never wrote into — it has no status worth a word on a list of real
 * conversations, and the tab's own always-applied filter means it does not reach this row anyway.
 */
@Composable
private fun RowScope.conversationStatusPill(row: ConversationRowUi) {
    val text = conversationStatusPillText(row) ?: return
    // `.rmeta.split .pill{overflow:hidden; text-overflow:ellipsis; min-width:0}` - «Назначен: {имя}»
    // is the one pill whose width is not bounded by its own vocabulary, so it yields to the count
    // beside it rather than pushing it off the row. `fill = false` keeps a short pill short.
    val modifier = Modifier.weight(1f, fill = false)
    when (conversationStateLabel(row.state)) {
        // `.pill.warn{background:var(--warning-tint); color:var(--warning)}` - the one pair Material 3
        // has no role for, which is why `AgoWarningColors` exists (that file's own doc comment).
        ConversationStateLabel.Waiting ->
            StatusPill(
                text = text,
                containerColor = agoWarningColors().warningTint,
                contentColor = agoWarningColors().warning,
                modifier = modifier,
            )

        // `.pill.ok{background:var(--success-tint); color:var(--success)}` - `tertiaryContainer`/
        // `onTertiaryContainer` are exactly `--success-tint`/`--success` in this app's own scheme
        // (`Theme.kt`: `tertiary = AgoSuccess*`, `tertiaryContainer = AgoMint*`), so this is the
        // mockup's pair read through the role it is already wired to, not a second definition of it.
        ConversationStateLabel.Assigned ->
            StatusPill(
                text = text,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = modifier,
            )

        // The mockup's plain `.pill` - a quiet sunken surface. A closed conversation is the neutral
        // case, not a state worth a colour.
        ConversationStateLabel.Closed ->
            StatusPill(
                text = text,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier,
            )

        ConversationStateLabel.Pending, ConversationStateLabel.Unknown -> Unit
    }
}

@Composable
private fun conversationStatusPillText(row: ConversationRowUi): String? =
    when (conversationStateLabel(row.state)) {
        ConversationStateLabel.Waiting -> stringResource(R.string.conversation_list_state_not_started)
        ConversationStateLabel.Assigned ->
            row.operatorName?.let { stringResource(R.string.conversation_list_state_assigned_to, it) }
                ?: stringResource(R.string.conversation_list_state_assigned)

        ConversationStateLabel.Closed -> stringResource(R.string.conversation_list_state_closed)
        ConversationStateLabel.Pending, ConversationStateLabel.Unknown -> null
    }

@Composable
private fun EmptyBody(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

// `26-30`/`26-40`: the shared elapsed-time formatter — `ui.components.ElapsedText.shortElapsedText` —
// used to be a private function of this screen; it moved out once the thread app-bar's subtitle became
// a second caller of the identical mockup short form («4 ч», «20 мин», «2 д»). See that file's own doc
// comment for the full reasoning; nothing about the wording or the bucketing changed in the move.

// `26-23`: the mockup's own row metrics, named once here rather than repeated as bare literals at each
// call site, with the CSS rule each one comes from. Nothing below is a chosen number.
//
// `.row{gap:13px}`
private val RowGap = 13.dp

// `.rtop{gap:8px}`, also `.rmid{gap:8px}` - the same 8dp gap, named once for both lines.
private val RtopGap = 8.dp

// `.rmid{margin-top:1px}`
private val SnippetTopGap = 1.dp

// `.rmeta{margin-top:6px}`
private val PillRowTopGap = 6.dp

// `.pill{border-radius:5px; letter-spacing:.04em}` — .04em of the pill's own 10.5px type.
private val PillCornerRadius = 5.dp
private val PillLetterSpacing = 0.42.sp

// `.badge{min-width:20px; height:20px}`
private val BadgeMinSize = 20.dp

// `26-39`: `.seg{margin:4px 16px 12px}` - the segmented control's own asymmetric margin.
private val SegmentedRowTopPadding = 4.dp
private val SegmentedRowHorizontalPadding = 16.dp
private val SegmentedRowBottomPadding = 12.dp

// `.seg .ct{opacity:.85}` - see `segmentedCountStyle`'s own doc comment for why this is an alpha over
// `LocalContentColor` rather than a flat color token.
private const val SEGMENTED_COUNT_ALPHA = 0.85f

// `26-90`: the «Все» tab's own metrics, named here beside every other one in this file rather than
// written as bare literals at their call sites.
//
// `.swipe-del{width:80px}` and `.row.swiped{transform:translateX(-80px)}` - one value, because the
// mockup itself uses one: the row moves exactly as far as the panel it uncovers.
private val EraseActionWidth = 80.dp

// `.swipe-del .i.lg{width:26px;height:26px}`
private val EraseActionIconSize = 26.dp

// `.swipe-del{gap:5px}` - between the glyph and the two caption lines.
private val EraseActionGap = 5.dp

// `.chip .i.sm{width:15px;height:15px}` - the chevron on the filter chip.
private val FilterChipChevronSize = 15.dp

// `style="transform:rotate(90deg)"` on the closed chip, `rotate(-90deg)` on the open one - the mockup
// turns the one right-pointing `#i-chev` rather than shipping a second glyph.
private const val FILTER_CHIP_CHEVRON_CLOSED_ROTATION = 90f
private const val FILTER_CHIP_CHEVRON_OPEN_ROTATION = -90f

// The trailing paging spinner, at the size Material 3 uses for an inline indicator rather than the
// full-screen one `LoadingBody` draws.
private val LoadMoreSpinnerSize = 24.dp

/** `26-90`: a stable key for the «Все» list's trailing paging item, so it is never confused with a
 * conversation id (the key every other item in that list uses). */
private const val ALL_LIST_LOAD_MORE_KEY = "all-list-load-more"

/** `26-90`: test hooks for the two controls this tab adds - the filter chip and the swipe-revealed
 * erase action - for the same reason [CONVERSATION_ROW_CONTENT_TEST_TAG] exists: a query built on the
 * Russian caption would break on a rewording that changed nothing about the control. */
internal const val STATUS_FILTER_CHIP_TEST_TAG = "conversationListStatusFilterChip"
internal const val ERASE_ACTION_TEST_TAG = "conversationListEraseAction"
