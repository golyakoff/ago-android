package ago.chat.android.thread.contactpanel.sections

import ago.chat.android.thread.contactpanel.AttachmentUploadSectionState
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-152`: the attachment-upload toggle's own three promises, proven at the Compose level with **no
 * Hilt in play** — the section is a stateless composable driven by a hand-built
 * [AttachmentUploadSectionState] and plain callbacks, the identical "drive the stateless composable
 * directly" split [TagsSectionTest] already establishes:
 *
 * 1. the whole section is **hidden, not disabled, without `conversation:attachment_upload_grant`** —
 *    unlike every sibling section, there is no read-only fallback to fall back to;
 * 2. a tap on the toggle calls back out; and
 * 3. the granted-by caption renders only while granted, and never a resolved operator name.
 *
 * `26-91`/`26-94`: the Russian literals below are safe because `LocaleForcingTestRunner` pins every
 * instrumented test's locale to `ru` before any run.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentUploadSectionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theWholeSectionIsHiddenWithoutThePermission() {
        composeTestRule.setContent {
            AttachmentUploadSection(
                state = AttachmentUploadSectionState.Loaded(granted = false),
                canGrant = false,
                onToggle = {},
                onRetry = {},
            )
        }

        composeTestRule.onNodeWithTag(ATTACHMENT_UPLOAD_SECTION_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun tappingTheButtonWhenNotGrantedCallsOnToggle() {
        var toggled = false
        composeTestRule.setContent {
            AttachmentUploadSection(
                state = AttachmentUploadSectionState.Loaded(granted = false),
                canGrant = true,
                onToggle = { toggled = true },
                onRetry = {},
            )
        }

        composeTestRule.onNodeWithText("Разрешить загрузку файлов").assertIsDisplayed().performClick()

        assert(toggled)
        // Not granted -> no granted-by caption.
        composeTestRule.onNodeWithText("Разрешено", substring = true).assertDoesNotExist()
    }

    @Test
    fun whenGrantedShowsTheRevokeLabelAndTheGrantedByCaptionWithNoOperatorName() {
        composeTestRule.setContent {
            AttachmentUploadSection(
                state =
                    AttachmentUploadSectionState.Loaded(
                        granted = true,
                        grantedAt = "2026-03-14T09:00:00Z",
                        grantedByOperatorId = "op-1",
                    ),
                canGrant = true,
                onToggle = {},
                onRetry = {},
            )
        }

        composeTestRule.onNodeWithText("Запретить загрузку файлов").assertIsDisplayed()
        composeTestRule.onNodeWithText("Разрешено оператором", substring = true).assertIsDisplayed()
        // The raw operator id is never shown - this section resolves no display name for it.
        composeTestRule.onNodeWithText("op-1").assertDoesNotExist()
    }

    @Test
    fun theToggleIsDisabledWhileAWriteIsInFlight() {
        composeTestRule.setContent {
            AttachmentUploadSection(
                state = AttachmentUploadSectionState.Loaded(granted = false, toggling = true),
                canGrant = true,
                onToggle = {},
                onRetry = {},
            )
        }

        composeTestRule.onNodeWithText("Разрешить загрузку файлов").assertIsNotEnabled()
    }
}
