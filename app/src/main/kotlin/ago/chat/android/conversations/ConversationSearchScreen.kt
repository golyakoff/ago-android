package ago.chat.android.conversations

import ago.chat.android.R
import ago.chat.android.core.domain.conversations.ConversationSearchHit
import ago.chat.android.core.domain.conversations.ConversationStateLabel
import ago.chat.android.core.domain.conversations.conversationStateLabel
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-245`: the conversation-search drill-in — a `site:configure` operator's site-wide full-text search,
 * reached from the Диалоги list's own search icon (`ConversationListScreen`'s top bar) and hosted as one
 * more state of [ago.chat.android.shell.ConversationsTabHost]'s hand-rolled back stack, the identical
 * shape [ago.chat.android.restrictions.RestrictedVisitorsRoute] takes for the overflow's own drill-in.
 * Obtains its own [ConversationSearchViewModel] via [hiltViewModel]; because the host composes this only
 * while search is open, that view model — and its first request — come into existence only when an
 * operator actually searches.
 *
 * [onOpenHit] opens a hit **read-only** — see [ConversationSearchViewModel]'s own doc comment for why
 * every hit, whatever its state, opens the same read-only way the «Все» tab already does rather than the
 * console's claim-or-position behaviour.
 */
@Composable
public fun ConversationSearchRoute(
    onBack: () -> Unit,
    onOpenHit: (String) -> Unit,
    viewModel: ConversationSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ConversationSearchScreen(
        state = state,
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onSearch = viewModel::search,
        onLoadMore = viewModel::loadMore,
        onOpenHit = onOpenHit,
    )
}

/**
 * The stateless half — every future UI test targets this function directly, the same "route wires,
 * screen renders" split every other screen in this app already follows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationSearchScreen(
    state: ConversationSearchUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenHit: (String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.conversation_search_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                SearchField(query = state.query, onQueryChange = onQueryChange, onSearch = onSearch)
                when (val phase = state.phase) {
                    ConversationSearchPhase.Idle -> PromptBody(stringResource(R.string.conversation_search_prompt))
                    ConversationSearchPhase.Searching -> LoadingBody()
                    is ConversationSearchPhase.Refused -> RefusedBody(detail = phase.detail)
                    is ConversationSearchPhase.Failed -> FailedBody(reason = phase.reason, onRetry = onSearch)
                    is ConversationSearchPhase.Results ->
                        if (phase.hits.isEmpty()) {
                            PromptBody(stringResource(R.string.conversation_search_empty))
                        } else {
                            ResultsList(phase = phase, onLoadMore = onLoadMore, onOpenHit = onOpenHit)
                        }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        label = { Text(text = stringResource(R.string.conversation_search_field_label)) },
        placeholder = { Text(text = stringResource(R.string.conversation_search_field_placeholder)) },
        leadingIcon = { Icon(imageVector = AgoIcons.Search, contentDescription = null) },
        // `26-245`: the software keyboard's own «Найти» / "Search" action runs the search — the same
        // "the field's IME action is the primary trigger" idiom this app already uses for its single-line
        // inputs, so there is no separate button competing with it for the phrase on screen.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PromptBody(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** A `400 Conversation.SearchInvalidQuery` — the server's own words, shown verbatim, never rendered
 * through the classification vocabulary a transport failure uses ([ConversationSearchViewModel]'s own
 * doc comment on why the two are separate arms). No retry control: the phrase/range that produced it has
 * to change first, and re-running it unchanged would only reproduce the same refusal. */
@Composable
private fun RefusedBody(detail: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = agoStatusColors().dangerText,
            textAlign = TextAlign.Center,
        )
    }
}

/** The one real read this screen depends on failed with nothing to show — the same title/detail/Retry
 * shape [ConversationListScreen]'s own `QueueLoadFailedBody` draws, a fourth independent copy rather than
 * a shared composable, the restraint those files' own doc comments state for each other (extract one only
 * when a fifth caller appears). Retry re-runs the same phrase. */
@Composable
private fun FailedBody(
    reason: NetworkFailure,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.conversation_search_failed_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = networkFailureText(reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text(text = stringResource(R.string.action_retry))
            }
        }
    }
}

/**
 * The hit list, newest first in the order the server already returns them (keyset order, never re-sorted
 * here — the identical rule [ConversationListScreen]'s own `AllList` states). The trailing item is the
 * whole paging trigger: composed only once the list has actually been scrolled to its end, and
 * [ConversationSearchViewModel.loadMore] is guarded against firing twice for one page, so a recomposition
 * while a page is in flight costs nothing. The effective searched range is shown as a header caption so
 * the window the server actually used is visible rather than silent (this item's own "the bound is
 * visible, not silent" done-when, ported).
 */
@Composable
private fun ResultsList(
    phase: ConversationSearchPhase.Results,
    onLoadMore: () -> Unit,
    onOpenHit: (String) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item(key = SEARCH_RANGE_CAPTION_KEY) {
            SearchRangeCaption(searchedFrom = phase.searchedFrom, searchedTo = phase.searchedTo, zone = zone)
        }
        items(phase.hits, key = { it.messageId }) { hit ->
            SearchHitRow(hit = hit, zone = zone, onClick = { onOpenHit(hit.conversationId) })
            HorizontalDivider()
        }
        if (phase.nextBeforeMessageId != null) {
            item(key = SEARCH_LOAD_MORE_KEY) {
                LaunchedEffect(phase.hits.size) { onLoadMore() }
                if (phase.loadingMore) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRangeCaption(
    searchedFrom: String,
    searchedTo: String,
    zone: ZoneId,
) {
    Text(
        text =
            stringResource(
                R.string.conversation_search_range,
                formatSearchedBound(searchedFrom, zone),
                formatSearchedBound(searchedTo, zone),
            ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * One hit — a status pill and the author/date meta line, then the matched message body (the complete
 * body, never a snippet — [ConversationSearchHit.matchedBody]'s own doc comment). Tapping it opens the
 * conversation read-only; [R.string.conversation_list_open_action] is reused for the tap's TalkBack hint,
 * the same wording a list row already announces.
 */
@Composable
private fun SearchHitRow(
    hit: ConversationSearchHit,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(R.string.conversation_list_open_action), onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            searchStateLabel(hit.conversationState)?.let { label -> StatePill(text = label) }
            Text(
                text = authorKindLabel(hit.authorKind),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatSearchedBound(hit.createdAt, zone),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = hit.matchedBody,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The same rounded, quiet-surface pill [ConversationListScreen]'s own `StatusPill` draws — restated
 * here since that one is `private` to its own file, the identical restraint the restrictions screen's
 * own `KindBadge` states for the same shape. */
@Composable
private fun StatePill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(
            text = text,
            style = LocalTextStyle.current.merge(MaterialTheme.typography.labelSmall).copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** `conversationStateLabel` classifies the raw wire spelling; the prose is this screen's. `Pending`/
 * `Unknown` draw no pill at all rather than a guessed word — the identical "unknown, not zero" posture
 * [ConversationListScreen]'s own `conversationStatusPillText` takes. */
@Composable
private fun searchStateLabel(state: String): String? =
    when (conversationStateLabel(state)) {
        ConversationStateLabel.Waiting -> stringResource(R.string.conversation_list_state_waiting)
        ConversationStateLabel.Assigned -> stringResource(R.string.conversation_list_state_assigned)
        ConversationStateLabel.Closed -> stringResource(R.string.conversation_list_state_closed)
        ConversationStateLabel.Pending, ConversationStateLabel.Unknown -> null
    }

/** `MessageDto.authorKind`'s three wire values, worded here — the same three the console's own
 * `authorLabel` renders, restated in this app's own vocabulary. An unrecognised value falls back to the
 * raw string rather than a guessed word. */
@Composable
private fun authorKindLabel(authorKind: String): String =
    when (authorKind) {
        "Visitor" -> stringResource(R.string.conversation_search_author_visitor)
        "Operator" -> stringResource(R.string.conversation_search_author_operator)
        "System" -> stringResource(R.string.conversation_search_author_system)
        else -> authorKind
    }

/** ISO-8601 → an absolute `d MMM, HH:mm` in the device's own zone (CLAUDE.md rule 11), the identical
 * pattern the restrictions screen uses. Falls back to the raw wire string if it does not parse, rather
 * than throwing on a value this screen only displays. */
private fun formatSearchedBound(
    iso: String,
    zone: ZoneId,
): String =
    try {
        DateTimeFormatter.ofPattern("d MMM, HH:mm").format(OffsetDateTime.parse(iso).atZoneSameInstant(zone))
    } catch (_: Exception) {
        iso
    }

private const val SEARCH_RANGE_CAPTION_KEY = "search-range-caption"
private const val SEARCH_LOAD_MORE_KEY = "search-load-more"
