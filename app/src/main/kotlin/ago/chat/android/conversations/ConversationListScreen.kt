package ago.chat.android.conversations

import ago.chat.android.R
import ago.chat.android.core.domain.conversations.ElapsedLabel
import ago.chat.android.core.domain.conversations.elapsedSince
import ago.chat.android.core.domain.visitorDisplayPrefixParts
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.ui.components.HubConnectionDot
import ago.chat.android.ui.components.VisitorAvatar
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.components.rememberTickingNow
import ago.chat.android.ui.components.russianPluralStringResource
import ago.chat.android.ui.components.shortElapsedText
import ago.chat.android.ui.icons.AgoIcons
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.OffsetDateTime

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
    // its own recomposition schedule. `26-40`: the ticker itself moved to `ui.components.ElapsedText`
    // now that the thread app-bar's subtitle is a second caller of the identical mechanism.
    val now = rememberTickingNow()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            // `26-32`: one row of chrome, which is what the mockup draws. This used to be a `Column`
            // of two — the app bar, and under it a full-width row carrying the active site's short
            // code and the words «Соединение: Подключено». Both halves of that second row are gone:
            // the connection state is now the dot in [actions] (see `HubConnectionDot`), and the site
            // code is not shown at all, because `26-17`'s Settings screen shows and switches the
            // active site properly and this line was the leftover of the days before it did. The
            // author, who reads this screen daily, did not recognise that code as a site id — which is
            // the clearest possible evidence it was not carrying its line's worth.
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.conversation_list_title)) },
                    actions = {
                        // The dot lives in `actions`, not beside the title, on purpose — and the
                        // thread screen puts it in the same slot for the same reason. A title slot has
                        // to ellipsise (the thread's title is a visitor identity of unbounded length),
                        // and a fixed-size indicator inside something that ellipsises is how it ends
                        // up clipped on the one device nobody tested on.
                        HubConnectionDot(
                            state = hubConnectionState,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        ConversationListOverflowMenu(onSignOut = onSignOut)
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
                    ConversationListTab.entries.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = state.selectedTab == tab,
                            onClick = { onTabSelected(tab) },
                            shape = SegmentedButtonDefaults.itemShape(index, ConversationListTab.entries.size),
                            label = { Text(text = segmentedTabLabel(tab = tab, count = segmentedCountFor(tab, state))) },
                        )
                    }
                }

                if (state.isStale) {
                    StaleBanner(onRefresh = onRefresh)
                }
                state.loadError?.let { reason ->
                    Text(
                        text = networkFailureText(reason),
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

/**
 * `26-32`: the mockup's `⋮`, and «Выйти» inside it rather than standing on the app bar as its own
 * text button.
 *
 * Sign-out was the most visually prominent control on the busiest screen in the app, which is the
 * wrong end of the scale for something an operator does once a week and never by accident. The
 * mockup puts an overflow here and nothing else; this menu therefore has exactly one item today, and
 * that is the point — it is the place the *next* screen-level action goes without the app bar growing
 * another button.
 *
 * `expanded` is a plain `remember`, not `rememberSaveable`: an open menu that survives a rotation is
 * not a property anybody wants, and Material 3's own `DropdownMenu` samples do the same.
 */
@Composable
private fun ConversationListOverflowMenu(onSignOut: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = AgoIcons.MoreVertical,
                contentDescription = stringResource(R.string.action_more_options),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.action_sign_out)) },
                onClick = {
                    // Closed before the callback, not after: `onSignOut` tears this whole composition
                    // down, and a `setExpanded` landing on a composable that no longer exists is the
                    // ordinary way this shape produces a leak warning.
                    expanded = false
                    onSignOut()
                },
            )
        }
    }
}

@Composable
private fun labelFor(tab: ConversationListTab): String =
    when (tab) {
        ConversationListTab.Mine -> stringResource(R.string.conversation_list_tab_mine)
        ConversationListTab.Waiting -> stringResource(R.string.conversation_list_tab_waiting)
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
    trailing: @Composable () -> Unit = {},
) {
    val description = conversationRowContentDescription(row = row, now = now)
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

    return listOfNotNull(parts.displayName, openedClause, unreadClause, snippetClause).joinToString(separator = ". ")
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
