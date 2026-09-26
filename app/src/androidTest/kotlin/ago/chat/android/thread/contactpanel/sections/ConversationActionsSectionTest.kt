package ago.chat.android.thread.contactpanel.sections

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.thread.contactpanel.RestrictionSectionState
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-153`: the panel's sixth and final section's own promises, proven at the Compose level with **no
 * Hilt in play** — the section is a stateless composable driven by hand-built state and plain callbacks,
 * the identical "drive the stateless composable directly" split [AttachmentUploadSectionTest] already
 * establishes:
 *
 * 1. each button is **hidden, not disabled, independently of the other**, on its own permission
 *    (`canClose`/`canRestrict`), and the whole section draws nothing when both are absent;
 * 2. a tap on either button opens a **confirm dialog** first, never calling back directly;
 * 3. confirming the dialog is what actually calls back; dismissing it does not; and
 * 4. the reversible restriction button's label follows [RestrictionSectionState.Loaded.restricted], and
 *    the section draws nothing at all for [RestrictionSectionState.Unavailable].
 *
 * `26-91`/`26-94`: the Russian literals below are safe because `LocaleForcingTestRunner` pins every
 * instrumented test's locale to `ru` before any run.
 */
@RunWith(AndroidJUnit4::class)
class ConversationActionsSectionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theWholeSectionIsHiddenWithoutEitherPermission() {
        composeTestRule.setContent {
            ConversationActionsSection(
                restriction = RestrictionSectionState.Loaded(restricted = false),
                closing = false,
                closeError = null,
                canClose = false,
                onClose = {},
                canRestrict = false,
                onToggleRestriction = {},
                onRetryRestriction = {},
            )
        }

        composeTestRule.onNodeWithTag(CONVERSATION_ACTIONS_SECTION_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun theCloseButtonIsHiddenWithoutCloseButTheRestrictionButtonStillDraws() {
        composeTestRule.setContent {
            ConversationActionsSection(
                restriction = RestrictionSectionState.Loaded(restricted = false),
                closing = false,
                closeError = null,
                canClose = false,
                onClose = {},
                canRestrict = true,
                onToggleRestriction = {},
                onRetryRestriction = {},
            )
        }

        composeTestRule.onNodeWithTag(CLOSE_CONVERSATION_BUTTON_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(RESTRICTION_BUTTON_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun tappingCloseOpensAConfirmDialogAndOnlyConfirmingCallsBack() {
        var closed = false
        composeTestRule.setContent {
            ConversationActionsSection(
                restriction = RestrictionSectionState.Loaded(restricted = false),
                closing = false,
                closeError = null,
                canClose = true,
                onClose = { closed = true },
                canRestrict = false,
                onToggleRestriction = {},
                onRetryRestriction = {},
            )
        }

        composeTestRule.onNodeWithTag(CLOSE_CONVERSATION_BUTTON_TEST_TAG).performClick()
        // The dialog, not the write, is up first.
        composeTestRule.onNodeWithText("Закрыть этот диалог?").assertIsDisplayed()
        assert(!closed)

        // Dismissing it never calls back.
        composeTestRule.onNodeWithText("Отмена").performClick()
        composeTestRule.onNodeWithText("Закрыть этот диалог?").assertDoesNotExist()
        assert(!closed)

        composeTestRule.onNodeWithTag(CLOSE_CONVERSATION_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithText("Закрыть", substring = false).performClick()

        assert(closed)
    }

    @Test
    fun anUnrestrictedVisitorShowsTheRestrictLabelAndConfirmingCallsToggle() {
        var toggled = false
        composeTestRule.setContent {
            ConversationActionsSection(
                restriction = RestrictionSectionState.Loaded(restricted = false),
                closing = false,
                closeError = null,
                canClose = false,
                onClose = {},
                canRestrict = true,
                onToggleRestriction = { toggled = true },
                onRetryRestriction = {},
            )
        }

        composeTestRule.onNodeWithText("Ограничить").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Заблокировать этого посетителя?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Да, заблокировать").performClick()

        assert(toggled)
    }

    @Test
    fun aRestrictedVisitorShowsTheLiftLabelAndItsOwnConfirmDialog() {
        var toggled = false
        composeTestRule.setContent {
            ConversationActionsSection(
                restriction = RestrictionSectionState.Loaded(restricted = true),
                closing = false,
                closeError = null,
                canClose = false,
                onClose = {},
                canRestrict = true,
                onToggleRestriction = { toggled = true },
                onRetryRestriction = {},
            )
        }

        composeTestRule.onNodeWithText("Снять ограничение").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Снять ограничение с этого посетителя?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Да, снять ограничение").performClick()

        assert(toggled)
    }

    @Test
    fun theSectionDrawsNothingForAnUnavailableRestriction() {
        composeTestRule.setContent {
            ConversationActionsSection(
                restriction = RestrictionSectionState.Unavailable,
                closing = false,
                closeError = null,
                canClose = false,
                onClose = {},
                canRestrict = true,
                onToggleRestriction = {},
                onRetryRestriction = {},
            )
        }

        composeTestRule.onNodeWithTag(RESTRICTION_BUTTON_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun aFailedRestrictionReadShowsInlineRetry() {
        var retried = false
        composeTestRule.setContent {
            ConversationActionsSection(
                restriction = RestrictionSectionState.Failed(NetworkFailure.NoConnection),
                closing = false,
                closeError = null,
                canClose = false,
                onClose = {},
                canRestrict = true,
                onToggleRestriction = {},
                onRetryRestriction = { retried = true },
            )
        }

        composeTestRule.onNodeWithText("Повторить").performClick()

        assert(retried)
    }
}
