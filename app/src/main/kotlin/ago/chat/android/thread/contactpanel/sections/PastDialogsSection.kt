package ago.chat.android.thread.contactpanel.sections

import ago.chat.android.R
import ago.chat.android.core.domain.conversations.ConversationStateLabel
import ago.chat.android.core.domain.conversations.conversationStateLabel
import ago.chat.android.core.domain.visitorhistory.VisitorHistoryConversation
import ago.chat.android.thread.MessageList
import ago.chat.android.thread.contactpanel.PastDialogHistoryState
import ago.chat.android.thread.contactpanel.PastDialogsSectionState
import ago.chat.android.ui.components.russianPluralStringResource
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-151`: the fourth and final section of the contact-detail panel — «Прошлые диалоги», the row that
 * opens a **read-only** list of the visitor's other conversations on this site
 * (`docs/design/26-111-thread-contact-detail-panel.md`, P1; `26-114`/`adr/0182` widened the scope to
 * every visitor, channel-identified or widget-only alike). Its own file under
 * `thread/contactpanel/sections/`, called from [ago.chat.android.thread.contactpanel.ContactDetailPanel]'s
 * marked insertion point — the additive convention that container's own doc comment prescribes.
 *
 * ## Read is the panel's gate; there is no second one
 *
 * Exactly [NotesSection]'s own posture: the Appendix table (`docs/design/26-111-*.md`) lists "read
 * history" under the same `conversation:read` that already gates the whole sheet, so this row carries no
 * permission parameter of its own — it is drawn whenever the panel is open, hidden exactly when the panel
 * itself is (design Q7). **Q6 decided past dialogs are read-only, full stop** — unlike [TagsSection] or
 * [NotesSection], there is no write permission to gate here at all: no composer, no swipe, no tag, no
 * close, nothing but reading.
 *
 * ## Two nested screens, not one
 *
 * Tapping the row opens a list sub-screen (mirrors [NotesSection]'s own single sub-screen); tapping one
 * row of *that* list opens a second, nested screen — the transcript — inside the same
 * [ModalBottomSheet], swapped in place rather than stacking a third sheet on top. Which of the two is
 * showing is [PastDialogsSectionState.Loaded.selectedConversationId]'s own presence, not a second local
 * `remember` flag here: unlike "is the sub-screen open at all" (pure presentation, so it stays a local
 * [rememberSaveable] exactly as [NotesSection]'s `showSubScreen` does), "which past conversation is open"
 * has to survive this composable being torn down and rebuilt while a fetch for it is in flight, so it
 * rides [ago.chat.android.thread.contactpanel.ContactPanelViewModel] the same way the notes composer's own
 * live draft does ([ago.chat.android.thread.contactpanel.NotesSectionState.Loaded]'s own doc comment).
 *
 * ## Reusing the message renderer, per Q6
 *
 * The transcript reuses [ago.chat.android.thread.MessageList] verbatim — the identical list the live
 * thread renders — widened from `private` to `internal` for exactly this reuse (that function's own doc
 * comment). It already draws no composer and no swipe/tag/close actions of its own (those live in
 * [ago.chat.android.thread.ThreadScreen]'s `Scaffold` and elsewhere), so nothing had to be stripped out
 * for the read-only reuse Q6 asks for — only [onNewestVisibleSequenceChanged] is wired to a no-op, since
 * marking read applies only to the live, currently-assigned conversation.
 */
@Composable
internal fun PastDialogsSection(
    state: PastDialogsSectionState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenPastDialog: (String) -> Unit,
    onClosePastDialogHistory: () -> Unit,
    onRetryPastDialogHistory: () -> Unit,
    onLoadOlderPastDialogHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSubScreen by rememberSaveable { mutableStateOf(false) }

    PastDialogsRow(
        state = state,
        onOpen = { showSubScreen = true },
        onRetry = onRetry,
        modifier = modifier,
    )

    // The same defensive guard [NotesSection]'s own doc comment states for its identical check: nothing
    // here currently re-enters `Loading` once opened, but this is what keeps the sub-screen from ever
    // being asked to render a `null` list.
    val loaded = state as? PastDialogsSectionState.Loaded
    if (showSubScreen && loaded != null) {
        PastDialogsSubScreen(
            state = loaded,
            onDismiss = {
                // Closing the sub-screen entirely also returns it to the list for next time - an
                // operator who backs out of a transcript and later re-opens the row should land on the
                // list, never back on the transcript they just left.
                showSubScreen = false
                onClosePastDialogHistory()
            },
            onLoadMore = onLoadMore,
            onOpenPastDialog = onOpenPastDialog,
            onClosePastDialogHistory = onClosePastDialogHistory,
            onRetryPastDialogHistory = onRetryPastDialogHistory,
            onLoadOlderPastDialogHistory = onLoadOlderPastDialogHistory,
        )
    }
}

/** The «Прошлые диалоги» row itself: title, a trailing count/spinner/retry depending on [state], and a
 * chevron once [state] is [PastDialogsSectionState.Loaded] - the only arm the row is actually clickable
 * in. The identical shape [NotesSection]'s own `NotesRow` draws for its row. */
@Composable
private fun PastDialogsRow(
    state: PastDialogsSectionState,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        PastDialogsSectionState.Loading ->
            Row(
                modifier = modifier.fillMaxWidth().padding(vertical = RowVerticalPadding).testTag(PAST_DIALOGS_ROW_TEST_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.past_dialogs_row_title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                CircularProgressIndicator(modifier = Modifier.size(SpinnerSize), strokeWidth = SpinnerStroke)
            }

        is PastDialogsSectionState.Failed ->
            Row(
                modifier = modifier.fillMaxWidth().padding(vertical = RowVerticalPadding).testTag(PAST_DIALOGS_ROW_TEST_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.past_dialogs_row_title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.past_dialogs_row_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) {
                    Text(text = stringResource(R.string.action_retry))
                }
            }

        is PastDialogsSectionState.Loaded ->
            Row(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpen)
                        .padding(vertical = RowVerticalPadding)
                        .testTag(PAST_DIALOGS_ROW_TEST_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.past_dialogs_row_title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text =
                        russianPluralStringResource(
                            count = state.conversations.size.toLong(),
                            one = R.string.past_dialogs_row_count_one,
                            few = R.string.past_dialogs_row_count_few,
                            many = R.string.past_dialogs_row_count_many,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = AgoIcons.ChevronRight,
                    // Decorative, the identical reasoning `NotesSection`'s own chevron gives: the row's
                    // own visible text already states what tapping it does.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = RowSpacing).size(ChevronSize),
                )
            }
    }
}

/**
 * The sub-screen itself — a second, independent [ModalBottomSheet] stacked over the panel's own, opened
 * only from [PastDialogsRow] and never nested inside the panel's own `Column`, the identical
 * [NotesSection]'s own `NotesSubScreen` posture. Shows the past-dialogs list, or — once
 * [PastDialogsSectionState.Loaded.selectedConversationId] is set — the read-only transcript in its place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PastDialogsSubScreen(
    state: PastDialogsSectionState.Loaded,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenPastDialog: (String) -> Unit,
    onClosePastDialogHistory: () -> Unit,
    onRetryPastDialogHistory: () -> Unit,
    onLoadOlderPastDialogHistory: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        if (state.selectedConversationId != null) {
            PastDialogHistoryScreen(
                history = state.history,
                onBack = onClosePastDialogHistory,
                onRetry = onRetryPastDialogHistory,
                onLoadOlder = onLoadOlderPastDialogHistory,
            )
        } else {
            PastDialogsListScreen(
                state = state,
                onBack = onDismiss,
                onLoadMore = onLoadMore,
                onOpenPastDialog = onOpenPastDialog,
            )
        }
    }
}

/** The list of the visitor's other conversations - a [LazyColumn] rather than [NotesSection]'s own plain
 * scrollable [Column] (that section's own doc comment: "N is tiny"), since this list pages forward via
 * [onLoadMore] and can genuinely grow long. */
@Composable
private fun PastDialogsListScreen(
    state: PastDialogsSectionState.Loaded,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenPastDialog: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(SUB_SCREEN_HEIGHT_FRACTION)
                .navigationBarsPadding()
                .testTag(PAST_DIALOGS_SUB_SCREEN_TEST_TAG),
    ) {
        SubScreenHeader(title = stringResource(R.string.past_dialogs_row_title), onBack = onBack)

        if (state.conversations.isEmpty()) {
            Text(
                text = stringResource(R.string.past_dialogs_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SubScreenHorizontalPadding, vertical = RowVerticalPadding),
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.conversations, key = { it.conversationId }) { conversation ->
                    PastDialogConversationRow(
                        conversation = conversation,
                        onOpen = { onOpenPastDialog(conversation.conversationId) },
                    )
                }

                if (state.nextBeforeId != null) {
                    item(key = "load-more") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(RowVerticalPadding),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.loadingMore) {
                                CircularProgressIndicator(modifier = Modifier.padding(RowSpacing))
                            } else {
                                TextButton(onClick = onLoadMore) {
                                    Text(text = stringResource(R.string.past_dialogs_load_more))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One row of the past-dialogs list: the conversation's own started date + state word over a one-line
 * preview of its last message - never a body with no author and never an invented preview for a
 * conversation with none ([VisitorHistoryConversation]'s own doc comment on why the three preview fields
 * come together or not at all). */
@Composable
private fun PastDialogConversationRow(
    conversation: VisitorHistoryConversation,
    onOpen: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = SubScreenHorizontalPadding, vertical = RowVerticalPadding)
                .testTag(pastDialogRowTestTag(conversation.conversationId)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(RowSpacing)) {
            pastDialogDate(conversation)?.let { date ->
                Text(text = date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            pastDialogStateWord(conversation.state)?.let { word ->
                Text(text = word, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        conversation.previewBody?.takeIf { it.isNotBlank() }?.let { preview ->
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The transcript screen - one past conversation's own messages, read-only. [history] arrives `null`
 * only for the one frame between [PastDialogsSectionState.Loaded.selectedConversationId] being set and
 * [ago.chat.android.thread.contactpanel.ContactPanelViewModel.openPastDialog]'s own state update landing
 * (both happen in the same call, so in practice this is never observed, but the type is nullable and this
 * function honours it rather than asserting it away). */
@Composable
private fun PastDialogHistoryScreen(
    history: PastDialogHistoryState?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadOlder: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(SUB_SCREEN_HEIGHT_FRACTION)
                .navigationBarsPadding()
                .testTag(PAST_DIALOG_HISTORY_TEST_TAG),
    ) {
        SubScreenHeader(title = stringResource(R.string.past_dialogs_row_title), onBack = onBack)

        when (history) {
            null, PastDialogHistoryState.Loading ->
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

            is PastDialogHistoryState.Failed ->
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.past_dialogs_history_load_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onRetry) {
                            Text(text = stringResource(R.string.action_retry))
                        }
                    }
                }

            is PastDialogHistoryState.Loaded ->
                if (history.messages.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.past_dialogs_history_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // `26-151`: the read-only reuse Q6 asks for - the identical `MessageList` the live
                    // thread renders, widened to `internal` for exactly this call
                    // (`ago.chat.android.thread.ThreadScreen`'s own doc comment on that function).
                    // `onNewestVisibleSequenceChanged` is a no-op: marking read applies only to the live,
                    // currently-assigned conversation, never a past one opened only to read.
                    MessageList(
                        modifier = Modifier.weight(1f),
                        messages = history.messages,
                        canLoadOlder = history.nextBeforeSequence != null,
                        loadingOlder = history.loadingOlder,
                        historyError = history.historyError,
                        onLoadOlder = onLoadOlder,
                        onNewestVisibleSequenceChanged = {},
                    )
                }
        }
    }
}

/** The back-arrow-plus-title header both nested screens draw, the identical shape [NotesSection]'s own
 * `NotesSubScreen` header establishes - [onBack] returns one level ("close the whole sub-screen" for the
 * list, "back to the list" for the transcript), never the same action in both places. */
@Composable
private fun SubScreenHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = SubScreenHorizontalPadding, vertical = SubScreenHorizontalPadding),
    ) {
        IconButton(onClick = onBack) {
            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** [VisitorHistoryConversation.startedAt] rendered in the operator's own zone (CLAUDE.md rule 11), `null`
 * - never a guessed date - when it is absent or failed to parse, the identical posture `NotesSection`'s
 * own `noteTimestamp` takes for a note's timestamp. */
@Composable
private fun pastDialogDate(conversation: VisitorHistoryConversation): String? {
    val instant = conversation.startedAt ?: return null
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofPattern(PAST_DIALOG_DATE_PATTERN, locale).format(instant.atZone(ZoneId.systemDefault()))
}

/** [VisitorHistoryConversation.state]'s wire spelling, classified the identical way
 * [ago.chat.android.thread.contactpanel.ContactDetailPanel]'s own `StateChipWord` classifies the open
 * conversation's own state - `null` (nothing drawn) for an empty or unrecognised wire spelling, never a
 * guessed word. */
@Composable
private fun pastDialogStateWord(state: String): String? =
    when (conversationStateLabel(state)) {
        ConversationStateLabel.Pending -> stringResource(R.string.conversation_state_pending)
        ConversationStateLabel.Waiting -> stringResource(R.string.conversation_state_waiting)
        ConversationStateLabel.Assigned -> stringResource(R.string.conversation_state_assigned)
        ConversationStateLabel.Closed -> stringResource(R.string.conversation_state_closed)
        ConversationStateLabel.Unknown -> null
    }

/** `26-151`: the row's own test tag - a stable hook for `PastDialogsSectionTest` independent of the
 * (Russian, wording-sensitive) title, the same reasoning [NOTES_ROW_TEST_TAG]'s own doc comment gives. */
internal const val PAST_DIALOGS_ROW_TEST_TAG: String = "pastDialogsRow"

/** `26-151`: the list sub-screen's own root test tag - proves the list sub-screen is actually up,
 * independent of its own wording. */
internal const val PAST_DIALOGS_SUB_SCREEN_TEST_TAG: String = "pastDialogsSubScreen"

/** `26-151`: the transcript screen's own root test tag - proves the nested history screen is actually up
 * (as opposed to the list), independent of its own wording. */
internal const val PAST_DIALOG_HISTORY_TEST_TAG: String = "pastDialogHistoryScreen"

/** `26-151`: one list row's own test tag, keyed by [VisitorHistoryConversation.conversationId] rather
 * than queried by its (Russian, wording-sensitive) preview text - the identical reasoning
 * [ago.chat.android.thread.messageBubbleContentTestTag]'s own doc comment gives. */
internal fun pastDialogRowTestTag(conversationId: String): String = "pastDialogRow:$conversationId"

// `d MMMM, HH:mm` -> "14 марта, 09:00" (ru) / "14 March, 09:00" (en) - an absolute date-and-time, never a
// relative "5 minutes ago" (CLAUDE.md rule 11), the identical pattern `NotesSection`'s own
// `NOTE_TIMESTAMP_PATTERN` uses.
private const val PAST_DIALOG_DATE_PATTERN = "d MMMM, HH:mm"

// Both nested screens take almost the full sheet height (never a partial detent - `skipPartiallyExpanded`
// on the container `ModalBottomSheet` above), leaving a sliver of the underlying thread visible as the
// drag handle's own context, the same fraction shape a scrollable `LazyColumn` needs a bounded height to
// lay out at all (unlike `NotesSection`'s own plain `verticalScroll` column, which needs no such bound).
private const val SUB_SCREEN_HEIGHT_FRACTION = 0.92f

private val RowVerticalPadding = 12.dp
private val RowSpacing = 8.dp
private val SubScreenHorizontalPadding = 20.dp
private val ChevronSize = 20.dp
private val SpinnerSize = 16.dp
private val SpinnerStroke = 2.dp
