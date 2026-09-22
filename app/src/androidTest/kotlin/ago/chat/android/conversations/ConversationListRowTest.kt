package ago.chat.android.conversations

import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.OffsetDateTime

/**
 * `26-30`: the conversation-list row's own five-part promise, driven through the stateless
 * [ConversationListScreen] directly — the same "route wires, screen renders, a test substitutes its own
 * state" split `ConversationListTopBarTest` already establishes, and `26-23`'s own `ConversationRow` doc
 * comment named as this row's outstanding gaps before this item closed them.
 *
 * These cases are deliberately about the row's own structure — what text exists, and what does not —
 * not pixel positions: Compose UI tests have no reliable way to assert "this text sits to the right of
 * that one" without a screenshot harness this project does not have (`docs/conventions/testing.md`).
 * The exact layout (badge beside the name, both timestamps right-aligned as a column) is instead
 * checked against the mockup on a real device — this item's own Done-when — and reviewed directly in
 * [ConversationListScreen]'s own doc comments, which quote the mockup's CSS class by class.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ConversationListRowTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // A visitor id whose first eight characters are exactly the code the author reported seeing live
    // on the old row ("восьмизначные коды диалога - на экране они лишние - 01a0c839",
    // `docs/backlog/26-30-*.md`).
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

    /** Part 1 - `docs/backlog/26-30-*.md`'s own Done-when: a nameless visitor with a known emoji pair
     * renders the emoji-derived name, never the bare code. */
    @Test
    fun aNamelessVisitorWithAnEmojiPairRendersTheDerivedName() {
        renderMine(
            ConversationRowUi(
                conversationId = "c1",
                visitorId = visitorId,
                emojiCreature = "🦊",
                emojiFood = "🍊",
                visitorName = null,
                createdAt = "2026-09-22T09:00:00Z",
                unreadCount = 0,
            ),
        )

        composeTestRule.onNodeWithText("Лиса · Апельсин").assertExists()
    }

    /** A named visitor is provably unchanged - the real name renders, not the emoji-derived fallback. */
    @Test
    fun aNamedVisitorStillRendersTheirRealName() {
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

        composeTestRule.onNodeWithText("Аня").assertExists()
        composeTestRule.onNodeWithText("Лиса · Апельсин").assertDoesNotExist()
    }

    /** Part 2 - the snippet line renders under the name when `26-29`'s field is present. */
    @Test
    fun theSnippetRendersWhenLastMessagePreviewIsPresent() {
        renderMine(
            ConversationRowUi(
                conversationId = "c1",
                visitorId = visitorId,
                emojiCreature = "🦊",
                emojiFood = "🍊",
                visitorName = "Аня",
                createdAt = "2026-09-22T09:00:00Z",
                unreadCount = 0,
                lastMessagePreview = "Здравствуйте, оплата не прошла",
                lastMessageAt = "2026-09-22T09:58:00Z",
            ),
        )

        composeTestRule.onNodeWithText("Здравствуйте, оплата не прошла").assertExists()
    }

    /**
     * A row with no messages at all has no snippet line - never an empty one, and therefore no second,
     * orphaned rendering of the elapsed time either. Proven by making the name line's own creation-time
     * distinctive (`"3 ч"`) and asserting it renders exactly once - a snippet line drawn empty (or one
     * that fell back to repeating the name line's own timestamp) would make this count two.
     */
    @Test
    fun aRowWithNoMessagesHasNoSnippetLineAtAll() {
        renderMine(
            ConversationRowUi(
                conversationId = "c1",
                visitorId = visitorId,
                emojiCreature = "🦊",
                emojiFood = "🍊",
                visitorName = "Аня",
                createdAt = OffsetDateTime.now().minusHours(3).toString(),
                unreadCount = 0,
            ),
        )

        composeTestRule.onAllNodesWithText("3 ч").assertCountEquals(1)
    }

    /** Part 3 - «Новое» sits on its own line, which this proves still renders when the row carries it. */
    @Test
    fun theNewBadgePillStillRendersBelowTheSnippet() {
        renderMine(
            ConversationRowUi(
                conversationId = "c1",
                visitorId = visitorId,
                emojiCreature = "🦊",
                emojiFood = "🍊",
                visitorName = "Аня",
                createdAt = "2026-09-22T09:00:00Z",
                unreadCount = 0,
                isNewlyAssigned = true,
                lastMessagePreview = "Здравствуйте",
                lastMessageAt = "2026-09-22T09:58:00Z",
            ),
        )

        composeTestRule.onNodeWithText("Новое").assertExists()
    }

    /** Part 4 - the short elapsed format, no «Открыт»/«Ждёт» prefix, on both the name line's own
     * creation-time and the snippet line's own last-message-time. */
    @Test
    fun bothTimestampsRenderInTheShortFormatWithNoPrefix() {
        renderMine(
            ConversationRowUi(
                conversationId = "c1",
                visitorId = visitorId,
                emojiCreature = "🦊",
                emojiFood = "🍊",
                visitorName = "Аня",
                // one hour before "now" - `elapsedSince` reads a live clock in this composable, so this
                // only has to land safely inside the same coarse bucket the assertion below checks.
                createdAt = OffsetDateTime.now().minusHours(2).toString(),
                unreadCount = 0,
                lastMessagePreview = "Здравствуйте",
                lastMessageAt = OffsetDateTime.now().minusMinutes(20).toString(),
            ),
        )

        composeTestRule.onNodeWithText("2 ч").assertExists()
        composeTestRule.onNodeWithText("20 мин").assertExists()
        composeTestRule.onNodeWithText("Открыт", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Ждёт", substring = true).assertDoesNotExist()
    }

    /** Part 5 - the raw eight-character code is gone from this row entirely. */
    @Test
    fun theEightCharacterCodeNeverAppearsOnThisRow() {
        renderMine(
            ConversationRowUi(
                conversationId = "c1",
                visitorId = visitorId,
                emojiCreature = "🦊",
                emojiFood = "🍊",
                visitorName = null,
                createdAt = "2026-09-22T09:00:00Z",
                unreadCount = 3,
                lastMessagePreview = "Здравствуйте",
                lastMessageAt = "2026-09-22T09:58:00Z",
            ),
        )

        composeTestRule.onNodeWithText("01a0c839", substring = true).assertDoesNotExist()
    }

    /** The unread-count badge - second refinement - still renders on the same line as the name. */
    @Test
    fun theUnreadBadgeRendersWithTheRow() {
        renderMine(
            ConversationRowUi(
                conversationId = "c1",
                visitorId = visitorId,
                emojiCreature = "🦊",
                emojiFood = "🍊",
                visitorName = "Аня",
                createdAt = "2026-09-22T09:00:00Z",
                unreadCount = 2,
            ),
        )

        composeTestRule.onNodeWithText("2").assertExists()
    }
}
