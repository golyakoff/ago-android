package ago.chat.android.thread

import ago.chat.android.core.network.realtime.MessageDto
import ago.chat.android.testing.FlakyOnCi
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-65`: a message bubble's own promise — "a screen reader can tell who wrote each message"
 * (`docs/backlog/26-65-*.md`'s own Scope) — proven at the semantics-tree level, the same
 * "drive the stateless [ThreadScreen] directly" split `ConversationRowSemanticsTest` already
 * establishes for the conversation-list row's identical technique.
 *
 * This is deliberately not the item's whole verification. TalkBack itself is not installed on the real
 * device this item was checked against (a LineageOS build with no Google accessibility services,
 * `26-64`'s own finding, reused here) and reaching a real thread with real messages from all three
 * author kinds requires a signed-in session this session's own SSO could not renew mid-task. This test
 * proves the tree Compose hands to the accessibility framework is shaped correctly; the report for this
 * item records the separate `adb shell uiautomator dump` check against the live app instead of a real
 * TalkBack readout.
 */
@OptIn(ExperimentalTestApi::class)
// `26-91`: this class asserts Russian text as a literal; the CI emulator boots English and cannot be
// forced to `ru-RU` by any mechanism found so far (`ci.yml`'s own comment has the detail). `26-94`
// tracks the real fix.
@FlakyOnCi
@RunWith(AndroidJUnit4::class)
class MessageBubbleSemanticsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun renderThread(messages: List<MessageDto>) {
        composeTestRule.setContent {
            ThreadScreen(
                state = ThreadUiState(joining = false, messages = messages),
                visitorId = "01a0c839-1111-1111-1111-111111111111",
                emojiCreature = "🦊",
                emojiFood = "🍊",
                visitorName = "Аня",
                createdAt = "2026-09-22T09:00:00Z",
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
            )
        }
    }

    private fun descriptionOf(messageId: String): String =
        composeTestRule
            .onNodeWithTag(messageBubbleContentTestTag(messageId))
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(separator = " ")
            .orEmpty()

    /** Scope item 1 - one node per bubble, its description naming the author, then the body, then the
     * time, in that order. */
    @Test
    fun theBubbleSpeaksAuthorThenBodyThenTime() {
        renderThread(
            listOf(
                MessageDto(
                    id = "m1",
                    sequence = 1,
                    authorKind = "Operator",
                    body = "Добрый день",
                    createdAt = "2026-09-22T09:39:00+03:00",
                ),
            ),
        )

        val node = composeTestRule.onNodeWithTag(messageBubbleContentTestTag("m1"))
        node.assert(hasContentDescription("Оператор", substring = true))
        node.assert(hasContentDescription("Добрый день", substring = true))

        val description = descriptionOf("m1")
        val authorIndex = description.indexOf("Оператор")
        val bodyIndex = description.indexOf("Добрый день")
        val timeIndex = description.indexOf("в ")
        assertTrue("expected author, then body, then time in: $description", authorIndex < bodyIndex && bodyIndex < timeIndex)
        assertTrue(
            "expected the time framed as a time, not bare digits: $description",
            Regex("в \\d{2}:\\d{2}").containsMatchIn(description),
        )
    }

    /** Scope item 2 - a system message is never announced as the visitor's own. */
    @Test
    fun aSystemMessageIsNotAnnouncedAsTheVisitors() {
        renderThread(
            listOf(
                MessageDto(
                    id = "m2",
                    sequence = 1,
                    authorKind = "System",
                    body = "Оператор подключился к диалогу",
                    createdAt = "2026-09-22T09:40:00+03:00",
                ),
            ),
        )

        val description = descriptionOf("m2")
        assertTrue("expected the system author word: $description", description.contains("Система"))
        assertFalse("must not read as the visitor's own message: $description", description.contains("Посетитель"))
    }

    /** Scope item 2, other side - a visitor message is never announced as the operator's. */
    @Test
    fun aVisitorMessageIsAnnouncedAsTheVisitors() {
        renderThread(
            listOf(
                MessageDto(
                    id = "m3",
                    sequence = 1,
                    authorKind = "Visitor",
                    body = "Оплата не прошла",
                    createdAt = "2026-09-22T09:38:00+03:00",
                ),
            ),
        )

        val description = descriptionOf("m3")
        assertTrue("expected the visitor author word: $description", description.contains("Посетитель"))
        assertFalse("must not read as the operator's own message: $description", description.contains("Оператор"))
    }

    /** Out of scope note in the item's own report - the delivery tick glyph (`26-42`) must never leak
     * into the spoken form, even though the same `.t` line draws it right beside the visible time. */
    @Test
    fun theDeliveryTickGlyphIsNeverSpoken() {
        renderThread(
            listOf(
                MessageDto(
                    id = "m4",
                    sequence = 1,
                    authorKind = "Operator",
                    body = "Сейчас посмотрю",
                    createdAt = "2026-09-22T09:41:00+03:00",
                    deliveredAt = "2026-09-22T09:41:05+03:00",
                ),
            ),
        )

        val description = descriptionOf("m4")
        assertFalse("the tick glyph must never be spoken: $description", description.contains("✓"))
    }

    /** A blank body (an attachment-only message) is dropped from the sentence rather than read as an
     * empty clause - no doubled separator, no stray leading one. */
    @Test
    fun aBlankBodyIsDroppedCleanly() {
        renderThread(
            listOf(
                MessageDto(
                    id = "m5",
                    sequence = 1,
                    authorKind = "Visitor",
                    body = "",
                    attachmentId = "a1",
                    createdAt = "2026-09-22T09:42:00+03:00",
                ),
            ),
        )

        val description = descriptionOf("m5")
        assertTrue("expected a non-empty description, got: $description", description.isNotBlank())
        assertFalse("no doubled separator: $description", description.contains(". . "))
        assertFalse("no leading separator: $description", description.startsWith(". "))
    }
}
