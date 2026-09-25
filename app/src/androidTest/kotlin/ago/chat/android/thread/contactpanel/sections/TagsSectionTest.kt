package ago.chat.android.thread.contactpanel.sections

import ago.chat.android.core.domain.tags.ConversationTag
import ago.chat.android.core.domain.tags.Tag
import ago.chat.android.thread.contactpanel.TagsSectionState
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-149`: the tags section's own two promises, proven at the Compose level with **no Hilt in play** — the
 * section is a stateless composable driven by a hand-built [TagsSectionState] and plain callbacks, the
 * identical "drive the stateless composable directly" split
 * [ago.chat.android.thread.contactpanel.sections.ContactDetailsSectionTest] (S-F) already establishes:
 *
 * 1. it renders the conversation's applied tags as chips, and
 * 2. the «+ метка» picker offers the site vocabulary **minus** whatever is already applied.
 *
 * `26-91`/`26-94`: the Russian literals below are safe because `LocaleForcingTestRunner` pins every
 * instrumented test's locale to `ru` before any run.
 */
@RunWith(AndroidJUnit4::class)
class TagsSectionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rendersTheAppliedTagsAsChips() {
        composeTestRule.setContent {
            TagsSection(
                state =
                    TagsSectionState.Loaded(
                        applied =
                            listOf(
                                ConversationTag(id = "t1", name = "VIP", createdAt = "2026-01-01T00:00:00Z", source = "Operator"),
                                ConversationTag(id = "t2", name = "Возврат", createdAt = "2026-01-02T00:00:00Z", source = "Ai"),
                            ),
                        vocabulary = emptyList(),
                    ),
                canTag = false,
                onAddTag = {},
                onRemoveTag = {},
                onRetry = {},
            )
        }

        composeTestRule.onNodeWithText("VIP").assertIsDisplayed()
        composeTestRule.onNodeWithText("Возврат").assertIsDisplayed()
        // No write permission -> no add control is drawn.
        composeTestRule.onNodeWithText("+ метка").assertDoesNotExist()
    }

    @Test
    fun theAddPickerOffersTheVocabularyMinusTheApplied() {
        composeTestRule.setContent {
            TagsSection(
                state =
                    TagsSectionState.Loaded(
                        applied =
                            listOf(
                                ConversationTag(id = "t2", name = "Возврат", createdAt = "2026-01-02T00:00:00Z", source = "Operator"),
                            ),
                        vocabulary =
                            listOf(
                                Tag(id = "t2", name = "Возврат", createdAt = "2026-01-02T00:00:00Z"),
                                Tag(id = "t3", name = "Срочно", createdAt = "2026-01-03T00:00:00Z"),
                            ),
                    ),
                canTag = true,
                onAddTag = {},
                onRemoveTag = {},
                onRetry = {},
            )
        }

        composeTestRule.onNodeWithText("+ метка").performClick()
        composeTestRule.waitForIdle()

        // The addable, non-applied tag is offered in the picker.
        composeTestRule.onNodeWithText("Срочно").assertIsDisplayed()
        // The already-applied tag is a chip but is NOT offered again in the picker: exactly one node on
        // screen carries its name (the chip), never a second one from the menu.
        composeTestRule.onAllNodesWithText("Возврат").assertCountEquals(1)
    }
}
