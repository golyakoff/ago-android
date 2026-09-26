package ago.chat.android.thread.contactpanel

import ago.chat.android.core.domain.visitorsummary.VisitorSummary
import ago.chat.android.thread.THREAD_CONTACT_PANEL_AFFORDANCE_TEST_TAG
import ago.chat.android.thread.ThreadScreen
import ago.chat.android.thread.ThreadUiState
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * `26-147`: the contact-panel shell's own two promises, proven at the Compose level:
 *
 * 1. the open affordance is **gated on `conversation:read`, hide-not-disable** (design Q7), and
 * 2. tapping it opens the sheet showing the visitor **header**.
 *
 * Both are driven through [TestThreadWithPanel] below, which reproduces exactly the wiring
 * [ago.chat.android.thread.ThreadRoute] does — the nullable affordance (present only when the operator
 * holds the permission) and the sheet host it opens — without a Hilt component in play, the same
 * "drive the stateless composables directly" split [ago.chat.android.thread.MessageBubbleSemanticsTest]
 * already establishes for this screen.
 *
 * `26-91`/`26-94`: the Russian literals below are safe because `LocaleForcingTestRunner` pins every
 * instrumented test's locale to `ru` before any run.
 */
@RunWith(AndroidJUnit4::class)
class ContactDetailPanelTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theAffordanceIsHiddenWithoutConversationReadPermission() {
        composeTestRule.setContent {
            TestThreadWithPanel(canReadContactDetail = false, summary = HeaderSummaryState.Loading)
        }

        composeTestRule.onNodeWithTag(THREAD_CONTACT_PANEL_AFFORDANCE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun tappingTheAffordanceOpensTheSheetShowingTheHeader() {
        composeTestRule.setContent {
            TestThreadWithPanel(
                canReadContactDetail = true,
                summary =
                    HeaderSummaryState.Loaded(
                        VisitorSummary(firstSeenAt = Instant.parse("2026-03-14T06:30:00Z"), conversationCount = 7),
                    ),
            )
        }

        // Gated on: with the permission the affordance is present (the negative test above proves it is
        // absent without it).
        composeTestRule.onNodeWithTag(THREAD_CONTACT_PANEL_AFFORDANCE_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(THREAD_CONTACT_PANEL_AFFORDANCE_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        // The sheet is up and shows the header: the panel root, H4 «Первый визит …» and H5 «N диалог(ов)».
        composeTestRule.onNodeWithTag(CONTACT_PANEL_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("Первый визит", substring = true).assertExists()
        composeTestRule.onNodeWithText("диалог", substring = true).assertExists()
    }

    @Composable
    private fun TestThreadWithPanel(
        canReadContactDetail: Boolean,
        summary: HeaderSummaryState,
    ) {
        var showPanel by remember { mutableStateOf(false) }
        ThreadScreen(
            state = ThreadUiState(joining = false),
            visitorId = "01a0c839-1111-1111-1111-111111111111",
            emojiCreature = "🦊",
            emojiFood = "🍊",
            visitorName = "Аня",
            createdAt = "2026-03-14T09:00:00Z",
            conversationState = "Assigned",
            hasAttachmentUploadGrant = false,
            onBack = {},
            onLoadOlder = {},
            onRetryJoin = {},
            onDraftChanged = {},
            onSend = {},
            onRetrySend = {},
            onDismissSendRefusal = {},
            onNewestVisibleSequenceChanged = {},
            onOpenContactPanel = if (canReadContactDetail) ({ showPanel = true }) else null,
        )
        if (showPanel) {
            ContactDetailPanel(
                state = ContactPanelUiState(summary = summary),
                emojiCreature = "🦊",
                emojiFood = "🍊",
                visitorName = "Аня",
                visitorId = "01a0c839-1111-1111-1111-111111111111",
                conversationState = "Assigned",
                onRetrySummary = {},
                onRevealContactDetail = {},
                onRetryContactDetails = {},
                canTag = false,
                onAddTag = {},
                onRemoveTag = {},
                onRetryTags = {},
                canWriteNote = false,
                onNoteDraftChanged = {},
                onAddNote = {},
                onRetryNotes = {},
                onRetryPastDialogs = {},
                onLoadMorePastDialogs = {},
                onOpenPastDialog = {},
                onClosePastDialogHistory = {},
                onRetryPastDialogHistory = {},
                onLoadOlderPastDialogHistory = {},
                onDismiss = { showPanel = false },
            )
        }
    }
}
