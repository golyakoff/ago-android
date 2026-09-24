package ago.chat.android.conversations

import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.testing.FlakyOnCi
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.OffsetDateTime

/**
 * `26-64`: the conversation row's own promise — "a conversation row reads as one sentence that says who,
 * how old, how many unread, and what was last said" (`docs/backlog/26-64-*.md`'s own Scope) — proven at
 * the semantics-tree level, the same "drive the stateless [ConversationListScreen] directly" split
 * [ConversationListRowTest]/[ConversationListTopBarTest] already establish.
 *
 * This is deliberately *not* the item's whole verification. Its own Done-when explicitly asks for a real
 * device with TalkBack actually switched on — a Compose semantics assertion proves the tree this app
 * hands to the accessibility framework is shaped correctly, not that a given TTS engine reads it well;
 * this item's own report records the separate, real-device check.
 */
@OptIn(ExperimentalTestApi::class)
// `26-91`: this class asserts Russian text as a literal; the CI emulator boots English and cannot be
// forced to `ru-RU` by any mechanism found so far (`ci.yml`'s own comment has the detail). `26-94`
// tracks the real fix.
@FlakyOnCi
@RunWith(AndroidJUnit4::class)
class ConversationRowSemanticsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val visitorId = "01a0c839-1111-1111-1111-111111111111"

    private fun renderMine(row: ConversationRowUi) {
        composeTestRule.setContent {
            ConversationListScreen(
                state = ConversationListUiState(hasData = true, mine = listOf(row)),
                hubConnectionState = OperatorHubConnectionState.Connected,
                onTabSelected = {},
                onRefresh = {},
                onClaim = {},
                onDismissClaimError = {},
                onOpenConversation = {},
                onSignOut = {},
            )
        }
    }

    private fun renderWaiting(row: ConversationRowUi) {
        composeTestRule.setContent {
            ConversationListScreen(
                state = ConversationListUiState(hasData = true, selectedTab = ConversationListTab.Waiting, waiting = listOf(row)),
                hubConnectionState = OperatorHubConnectionState.Connected,
                onTabSelected = {},
                onRefresh = {},
                onClaim = {},
                onDismissClaimError = {},
                onOpenConversation = {},
                onSignOut = {},
            )
        }
    }

    /** Scope item 1 - the whole row's spoken description covers who, how old, how many unread and what
     * was last said, all four in the one node [CONVERSATION_ROW_CONTENT_TEST_TAG] tags. */
    @Test
    fun theRowSpeaksOneSentenceCoveringWhoHowOldHowManyUnreadAndWhatWasLastSaid() {
        renderMine(
            ConversationRowUi(
                conversationId = "c1",
                visitorId = visitorId,
                emojiCreature = "🦊",
                emojiFood = "🍊",
                visitorName = "Аня",
                createdAt = OffsetDateTime.now().minusHours(2).toString(),
                unreadCount = 2,
                lastMessagePreview = "Оплата не прошла",
                lastMessageAt = OffsetDateTime.now().minusMinutes(20).toString(),
            ),
        )

        val node = composeTestRule.onNodeWithTag(CONVERSATION_ROW_CONTENT_TEST_TAG)
        node.assert(hasContentDescription("Аня", substring = true))
        node.assert(hasContentDescription("открыт 2 часа назад", substring = true))
        node.assert(hasContentDescription("2 непрочитанных сообщения", substring = true))
        node.assert(hasContentDescription("Оплата не прошла", substring = true))
        // Part 4's own point: the two elapsed values must not sound the same.
        node.assert(hasContentDescription("последнее сообщение 20 минут назад", substring = true))
    }

    /**
     * Scope item 2 - the avatar's own emoji glyphs never reach the accessibility tree TalkBack actually
     * walks, which is Compose's default *merged* tree - the tree `AndroidComposeViewAccessibilityDelegateCompat`
     * hands to the platform, and the same tree every other assertion in this file already queries with
     * no `useUnmergedTree` override. `useUnmergedTree = true` would deliberately bypass
     * `clearAndSetSemantics {}` itself (it exists precisely to inspect the *pre*-clearing, *pre*-merge
     * structure), so asserting there would prove the opposite of what this test is for - confirmed by
     * running this exact assertion with `useUnmergedTree = true` first and watching it fail by finding
     * the raw emoji `Text` node underneath, before this fix.
     */
    @Test
    fun theAvatarEmojiIsNeverSpoken() {
        renderMine(
            ConversationRowUi(
                conversationId = "c1",
                visitorId = visitorId,
                emojiCreature = "🦊",
                emojiFood = "🍊",
                visitorName = "Аня",
                createdAt = "2026-09-22T09:00:00Z",
                unreadCount = 0,
            ),
        )

        composeTestRule.onNodeWithText("🦊").assertDoesNotExist()
        composeTestRule.onNodeWithText("🍊").assertDoesNotExist()
    }

    /** Scope item 3 - `MineRow`'s tap announces what it does, not Compose's generic activation hint. */
    @Test
    fun theMineRowTapAnnouncesOpeningTheDialogue() {
        renderMine(
            ConversationRowUi(
                conversationId = "c1",
                visitorId = visitorId,
                emojiCreature = "🦊",
                emojiFood = "🍊",
                visitorName = "Аня",
                createdAt = "2026-09-22T09:00:00Z",
                unreadCount = 0,
            ),
        )

        val hasOpenDialogueLabel =
            SemanticsMatcher("has onClickLabel «открыть диалог»") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "открыть диалог"
            }
        composeTestRule.onNode(hasOpenDialogueLabel).assertExists()
    }

    /** Scope item 4 - the age and the last-message time read as two different sentences even though
     * `shortElapsedText` draws them identically (`26-30`'s own deliberate choice, unchanged). */
    @Test
    fun theTwoElapsedValuesAreDistinguishableWhenSpoken() {
        renderMine(
            ConversationRowUi(
                conversationId = "c1",
                visitorId = visitorId,
                emojiCreature = "🦊",
                emojiFood = "🍊",
                visitorName = "Аня",
                createdAt = OffsetDateTime.now().minusHours(4).toString(),
                unreadCount = 0,
                lastMessagePreview = "Привет",
                lastMessageAt = OffsetDateTime.now().minusHours(4).toString(),
            ),
        )

        // Both read "4 ч" on screen (unchanged); the spoken forms must not collide.
        composeTestRule.onNodeWithText("4 ч").assertExists()
        val node = composeTestRule.onNodeWithTag(CONVERSATION_ROW_CONTENT_TEST_TAG)
        node.assert(hasContentDescription("открыт 4 часа назад", substring = true))
        node.assert(hasContentDescription("последнее сообщение 4 часа назад", substring = true))
    }

    /** Scope item 5 - the claim button on «Ожидают» stays independently reachable, never folded into the
     * row's own merged description. */
    @Test
    fun onWaitingRowsTheClaimButtonStaysItsOwnFocusableNode() {
        renderWaiting(
            ConversationRowUi(
                conversationId = "c1",
                visitorId = visitorId,
                emojiCreature = "🦊",
                emojiFood = "🍊",
                visitorName = "Аня",
                createdAt = "2026-09-22T09:00:00Z",
                unreadCount = 1,
            ),
        )

        // The button is its own node, with its own click action - not absorbed into the row's content.
        composeTestRule.onNodeWithText("Забрать").assertHasClickAction()

        // The merged content node itself carries no click action of its own on «Ожидают» - there is no
        // tap-to-open on this tab; only the claim button acts.
        val contentNodes = composeTestRule.onAllNodesWithTag(CONVERSATION_ROW_CONTENT_TEST_TAG).fetchSemanticsNodes()
        assertTrue("expected exactly one merged content node", contentNodes.size == 1)
        assertFalse(
            "the merged content node must not itself carry a click action - the claim button owns it",
            contentNodes.single().config.getOrNull(SemanticsActions.OnClick) != null,
        )

        // And the claim label never leaks into the merged sentence.
        val mergedDescriptionParts = contentNodes.single().config.getOrNull(SemanticsProperties.ContentDescription)
        val mergedDescription = mergedDescriptionParts?.joinToString(separator = " ").orEmpty()
        assertFalse("the claim label must not leak into the row's own sentence: $mergedDescription", mergedDescription.contains("Забрать"))
    }

    /** Scope item 6 - a row with no name, no snippet and no unread count still reads as one clean
     * sentence: no stray leading/trailing separator, and never two separators back to back. */
    @Test
    fun aRowWithNoNameNoSnippetAndNoUnreadReadsCleanlyWithNoStraySeparators() {
        renderMine(
            ConversationRowUi(
                conversationId = "c1",
                visitorId = visitorId,
                emojiCreature = null,
                emojiFood = null,
                visitorName = null,
                createdAt = "2026-09-22T09:00:00Z",
                unreadCount = 0,
            ),
        )

        val description =
            composeTestRule
                .onNodeWithTag(CONVERSATION_ROW_CONTENT_TEST_TAG)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(separator = " ")
                .orEmpty()

        assertTrue("expected a non-empty description, got: $description", description.isNotBlank())
        assertFalse("no leading separator: $description", description.startsWith(". "))
        assertFalse("no trailing separator: $description", description.endsWith(". "))
        assertFalse("no doubled separator: $description", description.contains(". . "))
        assertFalse("no stray comma: $description", description.contains(", ,"))
    }
}
