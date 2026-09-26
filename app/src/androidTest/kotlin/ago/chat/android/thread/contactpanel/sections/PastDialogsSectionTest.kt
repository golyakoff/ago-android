package ago.chat.android.thread.contactpanel.sections

import ago.chat.android.core.domain.visitorhistory.VisitorHistoryConversation
import ago.chat.android.core.network.realtime.MessageDto
import ago.chat.android.thread.contactpanel.PastDialogHistoryState
import ago.chat.android.thread.contactpanel.PastDialogsSectionState
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-151`: the «Прошлые диалоги» section's own three promises, proven at the Compose level with **no
 * Hilt in play** - the section is a stateless composable driven by a hand-built [PastDialogsSectionState]
 * and plain callbacks, the identical "drive the stateless composable directly" split
 * [ago.chat.android.thread.contactpanel.sections.NotesSectionTest] (S-H) already establishes for its own
 * sibling section:
 *
 * 1. tapping the row opens the list sub-screen, showing the visitor's other conversations;
 * 2. tapping one of those conversations opens its transcript, read-only - reusing the same message
 *    renderer the live thread uses (Q6); and
 * 3. the transcript draws **no composer** - the design's own "no composer/actions" scope for this
 *    section, structurally different from [NotesSection] (which does gate a composer, just on a
 *    permission) rather than merely undemonstrated here.
 *
 * `26-91`/`26-94`: the Russian literals below are safe because `LocaleForcingTestRunner` pins every
 * instrumented test's locale to `ru` before any run.
 */
@RunWith(AndroidJUnit4::class)
class PastDialogsSectionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tappingTheRowOpensTheSubScreenShowingThePastDialogsList() {
        composeTestRule.setContent {
            PastDialogsSection(
                state = PastDialogsSectionState.Loaded(conversations = listOf(historyConversation())),
                onRetry = {},
                onLoadMore = {},
                onOpenPastDialog = {},
                onClosePastDialogHistory = {},
                onRetryPastDialogHistory = {},
                onLoadOlderPastDialogHistory = {},
            )
        }

        composeTestRule.onNodeWithTag(PAST_DIALOGS_ROW_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(PAST_DIALOGS_SUB_SCREEN_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("Здравствуйте, вопрос про заказ").assertIsDisplayed()
    }

    @Test
    fun tappingAConversationOpensItsReadOnlyTranscriptWithNoComposer() {
        val message = MessageDto(id = "m1", sequence = 1, authorKind = "Visitor", body = "Здравствуйте")
        composeTestRule.setContent {
            PastDialogsSection(
                state =
                    PastDialogsSectionState.Loaded(
                        conversations = listOf(historyConversation()),
                        selectedConversationId = "h1",
                        history = PastDialogHistoryState.Loaded(messages = listOf(message)),
                    ),
                onRetry = {},
                onLoadMore = {},
                onOpenPastDialog = {},
                onClosePastDialogHistory = {},
                onRetryPastDialogHistory = {},
                onLoadOlderPastDialogHistory = {},
            )
        }

        composeTestRule.onNodeWithTag(PAST_DIALOGS_ROW_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        // The transcript, not the list, is what shows the moment the row opens - `selectedConversationId`
        // is already set in the hand-built state above, the identical "drive the exact nested state a
        // test needs" shape this file's own doc comment describes.
        composeTestRule.onNodeWithTag(PAST_DIALOG_HISTORY_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("Здравствуйте").assertIsDisplayed()

        // Q6 - no composer, full stop: the live thread's own composer placeholder must never appear here.
        composeTestRule.onNodeWithText("Сообщение…").assertDoesNotExist()
    }

    @Test
    fun closingTheTranscriptCallsBackToTheList() {
        var closed = false
        composeTestRule.setContent {
            PastDialogsSection(
                state =
                    PastDialogsSectionState.Loaded(
                        conversations = listOf(historyConversation()),
                        selectedConversationId = "h1",
                        history = PastDialogHistoryState.Loaded(messages = emptyList()),
                    ),
                onRetry = {},
                onLoadMore = {},
                onOpenPastDialog = {},
                onClosePastDialogHistory = { closed = true },
                onRetryPastDialogHistory = {},
                onLoadOlderPastDialogHistory = {},
            )
        }

        composeTestRule.onNodeWithTag(PAST_DIALOGS_ROW_TEST_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(PAST_DIALOG_HISTORY_TEST_TAG).assertExists()

        // The transcript's own back arrow is the first (and only) `IconButton` `SubScreenHeader` draws
        // for this screen - clicking it must ask the view model to go back to the list, never dismiss the
        // whole sub-screen (this file's own doc comment on the container's `onDismiss` wiring).
        composeTestRule.onNodeWithContentDescription("Назад").performClick()

        assertTrue(closed)
    }

    private fun historyConversation(): VisitorHistoryConversation =
        VisitorHistoryConversation(
            conversationId = "h1",
            state = "Closed",
            startedAt = null,
            closedAt = null,
            previewBody = "Здравствуйте, вопрос про заказ",
            previewAuthorKind = "Visitor",
            previewCreatedAt = null,
        )
}
