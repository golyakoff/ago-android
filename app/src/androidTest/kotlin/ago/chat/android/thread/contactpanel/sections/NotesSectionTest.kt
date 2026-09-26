package ago.chat.android.thread.contactpanel.sections

import ago.chat.android.core.domain.notes.ConversationNote
import ago.chat.android.thread.contactpanel.NotesSectionState
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
 * `26-150`: the «Заметки команды» row's own two promises, proven at the Compose level with **no Hilt in
 * play** — the section is a stateless composable driven by a hand-built [NotesSectionState] and plain
 * callbacks, the identical "drive the stateless composable directly" split
 * [ago.chat.android.thread.contactpanel.sections.TagsSectionTest] (S-G) already establishes:
 *
 * 1. tapping the row opens the notes sub-screen, showing the notes list, and
 * 2. the composer is drawn only when the operator holds `conversation:note_write` (hide-not-disable,
 *    design Q7) — never the row or the read-only list, which ride the panel's own `conversation:read`
 *    gate instead.
 *
 * `26-91`/`26-94`: the Russian literals below are safe because `LocaleForcingTestRunner` pins every
 * instrumented test's locale to `ru` before any run.
 */
@RunWith(AndroidJUnit4::class)
class NotesSectionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tappingTheRowOpensTheSubScreenShowingTheNotesList() {
        composeTestRule.setContent {
            val note =
                ConversationNote(
                    id = "n1",
                    authorId = "op1",
                    body = "Позвонить завтра",
                    createdAt = "2026-03-14T09:00:00Z",
                )
            NotesSection(
                state = NotesSectionState.Loaded(notes = listOf(note)),
                canWriteNote = false,
                onNoteDraftChanged = {},
                onAddNote = {},
                onRetry = {},
            )
        }

        composeTestRule.onNodeWithTag(NOTES_ROW_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(NOTES_SUB_SCREEN_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("Позвонить завтра").assertIsDisplayed()
    }

    @Test
    fun theComposerIsHiddenWithoutNoteWritePermission() {
        composeTestRule.setContent {
            NotesSection(
                state = NotesSectionState.Loaded(notes = emptyList()),
                canWriteNote = false,
                onNoteDraftChanged = {},
                onAddNote = {},
                onRetry = {},
            )
        }

        composeTestRule.onNodeWithTag(NOTES_ROW_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(NOTES_SUB_SCREEN_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("Добавить").assertDoesNotExist()
    }

    @Test
    fun theComposerIsShownWithNoteWritePermission() {
        composeTestRule.setContent {
            NotesSection(
                state = NotesSectionState.Loaded(notes = emptyList()),
                canWriteNote = true,
                onNoteDraftChanged = {},
                onAddNote = {},
                onRetry = {},
            )
        }

        composeTestRule.onNodeWithTag(NOTES_ROW_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Добавить").assertExists()
    }
}
