package ago.chat.android.thread.contactpanel

import ago.chat.android.R
import ago.chat.android.core.domain.conversations.ConversationStateLabel
import ago.chat.android.core.domain.conversations.conversationStateLabel
import ago.chat.android.core.domain.visitorDisplayPrefixParts
import ago.chat.android.core.domain.visitorsummary.VisitorSummary
import ago.chat.android.ui.components.VisitorAvatar
import ago.chat.android.ui.components.russianPluralStringResource
import ago.chat.android.ui.components.visitorEmojiPairName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
 * `26-147`: the contact-detail panel's own container — the join point every section slice
 * (`26-148`…`26-153`) builds on. A fully-expanded Material 3 [ModalBottomSheet] over the open thread,
 * laid out as one [Column] that draws the visitor header (H1–H5) and then, below a divider, the clearly
 * marked point where each later section composable is added.
 *
 * ## The section-insertion convention (for `26-148`…`26-153`)
 *
 * Each later slice adds **one file** under `thread/contactpanel/sections/` holding one `@Composable`
 * section (e.g. `ContactDetailsSection`, `TagsSection`, …), and calls it from the marked point in the
 * [Column] below — in the order the design lists (§ "UI assembly"): КОНТАКТНЫЕ ДАННЫЕ, tags, «Заметки
 * команды», «Прошлые диалоги», «Приём файлов от посетителя», then «Закрыть диалог»/«Ограничить». A
 * section reads whatever it needs from [ContactPanelUiState] (growing it with its own async arm, that
 * type's own doc comment) and is handed its own callbacks from [ContactPanelViewModel]. Nothing about
 * this container or the header is restructured to add one — a section is an added line at the marked
 * point, never a rewrite of the [Column].
 *
 * ## Why `skipPartiallyExpanded = true`
 *
 * The panel opens straight to fully expanded — never a half-height first stop — matching the design
 * (§4: "a **modal bottom sheet** … fully expanded") and [ago.chat.android.team.InviteColleagueSheet]'s
 * own recent use of the identical flag. A partial first detent would hide the header behind a drag the
 * operator did not ask for.
 *
 * ## Why H1–H3 are parameters and H4/H5 are state
 *
 * H1 (avatar), H2 (name) and H3 (state chip) come from the in-hand
 * [ago.chat.android.core.domain.conversations.ConversationSummary] the thread already holds — passed in
 * as plain parameters, no round trip (§4). Only H4 «Первый визит {date}» and H5 «N диалог(ов)» need a
 * server read, so they alone travel through [ContactPanelUiState.summary]; if that read fails, the sheet
 * still opens and the in-hand H1–H3 stay on screen, with an inline retry on the H4/H5 line (§4:
 * per-section retry, never a whole-sheet failure).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactDetailPanel(
    state: ContactPanelUiState,
    emojiCreature: String?,
    emojiFood: String?,
    visitorName: String?,
    visitorId: String?,
    conversationState: String?,
    onRetrySummary: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = PanelHorizontalPadding)
                    .testTag(CONTACT_PANEL_TEST_TAG),
        ) {
            ContactPanelHeader(
                summary = state.summary,
                emojiCreature = emojiCreature,
                emojiFood = emojiFood,
                visitorName = visitorName,
                visitorId = visitorId,
                conversationState = conversationState,
                onRetrySummary = onRetrySummary,
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = SectionSpacing),
            )

            // ─── SECTION INSERTION POINT (26-148…26-153) ───────────────────────────────────────────
            // Each later slice adds one `sections/*.kt` composable call here, in the design's UI-assembly
            // order (КОНТАКТНЫЕ ДАННЫЕ, tags, «Заметки команды», «Прошлые диалоги», «Приём файлов от
            // посетителя», «Закрыть диалог»/«Ограничить»). See this file's own doc comment for the
            // convention. Nothing above this line changes to add one.

            Spacer(modifier = Modifier.height(SectionSpacing))
        }
    }
}

/**
 * The visitor header — H1 avatar, H2 display name and H3 state chip on one row, then the H4/H5
 * first-visit-and-count line under it. H1–H3 render the moment the sheet opens; the H4/H5 line reflects
 * [summary]'s own Loading / Loaded / Failed arm.
 */
@Composable
private fun ContactPanelHeader(
    summary: HeaderSummaryState,
    emojiCreature: String?,
    emojiFood: String?,
    visitorName: String?,
    visitorId: String?,
    conversationState: String?,
    onRetrySummary: () -> Unit,
) {
    // H2: the visitor's real name when there is one, else the resource-backed emoji-pair fallback
    // «Сова · Клубника» (`26-116`, both locales) - never a raw id on screen (design Author decision #1).
    // Read through `visitorDisplayPrefixParts` for the same blank-is-absent normalisation every other
    // caller uses, but the *fallback* comes from `visitorEmojiPairName` rather than the Russian-only
    // `:core:domain` label, since the panel is locale-aware.
    val parts = visitorDisplayPrefixParts(emojiCreature, emojiFood, visitorName, visitorId)
    val displayName =
        parts.visitorName
            ?: parts.emoji?.let { visitorEmojiPairName(it) }
            ?: stringResource(R.string.thread_identity_unavailable)

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = PanelHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HeaderGap),
    ) {
        // H1: the emoji avatar. Draws nothing at all for a pair-less visitor (its own doc comment) -
        // never a blank circle.
        VisitorAvatar(emojiCreature = emojiCreature, emojiFood = emojiFood)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ContactPanelSummaryLine(summary = summary, onRetrySummary = onRetrySummary)
        }

        // H3: the conversation-state chip («в работе»), rendered only when the wire spelling maps to a
        // known state - never a guessed word for an unknown one (`conversationStateLabel`'s own contract).
        StateChipWord(conversationState)?.let { word -> ContactPanelStateChip(word) }
    }
}

/** H4 «Первый визит {date}» · H5 «N диалог(ов)», or the loading / retry affordance for that line. The
 * in-hand H1–H3 above never depend on this arm. */
@Composable
private fun ContactPanelSummaryLine(
    summary: HeaderSummaryState,
    onRetrySummary: () -> Unit,
) {
    when (summary) {
        HeaderSummaryState.Loading ->
            CircularProgressIndicator(
                modifier = Modifier.padding(top = SummaryLineTopPadding).size(SummarySpinnerSize),
                strokeWidth = SummarySpinnerStroke,
            )

        is HeaderSummaryState.Loaded ->
            Text(
                text = summaryLineText(summary.summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = SummaryLineTopPadding),
            )

        is HeaderSummaryState.Failed ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.contact_panel_summary_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetrySummary) {
                    Text(text = stringResource(R.string.action_retry))
                }
            }
    }
}

/** Joins H4 and H5 with the mockup's own « · » separator. When the first-visit date could not be parsed
 * ([VisitorSummary.firstSeenAt] is `null`, its own doc comment) only the count is shown - never a
 * dangling separator or an invented date. */
@Composable
private fun summaryLineText(summary: VisitorSummary): String {
    val firstVisit =
        summary.firstSeenAt?.let { instant ->
            val locale = LocalConfiguration.current.locales[0]
            val date =
                DateTimeFormatter
                    .ofPattern(FIRST_VISIT_DATE_PATTERN, locale)
                    .format(instant.atZone(ZoneId.systemDefault()))
            stringResource(R.string.contact_panel_first_visit, date)
        }
    val count =
        russianPluralStringResource(
            count = summary.conversationCount.toLong(),
            one = R.string.contact_panel_conversation_count_one,
            few = R.string.contact_panel_conversation_count_few,
            many = R.string.contact_panel_conversation_count_many,
        )
    return listOfNotNull(firstVisit, count).joinToString(separator = " · ")
}

@Composable
private fun ContactPanelStateChip(word: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(percent = 50),
    ) {
        Text(
            text = word,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = StateChipHorizontalPadding, vertical = StateChipVerticalPadding),
        )
    }
}

/** The state word for the chip - the same `:core:domain` classification the thread app-bar subtitle uses
 * (`ThreadScreen`'s own `threadStateWord`), restated here because that one is `private` to its file; the
 * prose-per-arm mapping is `:app`'s job, the classification `conversationStateLabel`'s. `null` (chip not
 * drawn) for an empty or unrecognised wire spelling. */
@Composable
private fun StateChipWord(conversationState: String?): String? =
    conversationState?.let {
        when (conversationStateLabel(it)) {
            ConversationStateLabel.Pending -> stringResource(R.string.conversation_state_pending)
            ConversationStateLabel.Waiting -> stringResource(R.string.conversation_state_waiting)
            ConversationStateLabel.Assigned -> stringResource(R.string.conversation_state_assigned)
            ConversationStateLabel.Closed -> stringResource(R.string.conversation_state_closed)
            ConversationStateLabel.Unknown -> null
        }
    }

/** `26-147`: the panel's root test tag - a stable hook for `ContactDetailPanelTest` that does not depend
 * on the (Russian, wording-sensitive) header text, the same reasoning
 * [ago.chat.android.thread.messageBubbleContentTestTag]'s own doc comment gives. */
internal const val CONTACT_PANEL_TEST_TAG: String = "contactDetailPanel"

// `d MMMM` -> "14 марта" (ru) / "14 March" (en), rendered in the operator's own zone (CLAUDE.md rule 11:
// an absolute calendar date, formatted in the reader's zone).
private const val FIRST_VISIT_DATE_PATTERN = "d MMMM"

private val PanelHorizontalPadding = 20.dp
private val SectionSpacing = 16.dp
private val HeaderGap = 12.dp
private val SummaryLineTopPadding = 2.dp
private val SummarySpinnerSize = 16.dp
private val SummarySpinnerStroke = 2.dp
private val StateChipHorizontalPadding = 10.dp
private val StateChipVerticalPadding = 4.dp
