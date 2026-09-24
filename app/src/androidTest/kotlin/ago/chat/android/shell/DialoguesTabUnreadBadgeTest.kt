package ago.chat.android.shell

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.Permission
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-46`: the Диалоги tab's own unread badge, proven directly at [AppShellScreen] - the identical
 * Hilt-free rig `BackContractBottomBarTest` already establishes for this file's own back-button
 * contract tests, since [AppShellScreen]'s own `unreadConversationsTotal` parameter is a plain `Int?`
 * with no view model, no Room database and no hub connection in play at all. What
 * `AppShellViewModel.unreadConversationsTotal` actually reads that `Int?` from is
 * `RoomConversationListCacheTest`'s own job (androidTest, `data/conversations/`), and what
 * `AppShellViewModel` itself does with it is `AppShellViewModelTest`'s (plain JVM) - this class is the
 * one layer neither of those two reaches: does the bottom bar actually *draw* the right thing for each
 * of the three values that `Int?` can be.
 */
@RunWith(AndroidJUnit4::class)
class DialoguesTabUnreadBadgeTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val allFiveVisible = OperatorPermissions.Known(setOf(Permission.CALENDAR_CONFIGURE))

    @Test
    fun noBadgeBeforeTheFirstAnswerArrives() {
        setContentWith(unreadConversationsTotal = null)

        // The icon's own contentDescription is still the bare label - never a numeral, and never a
        // sentence built around one, for a total this app does not have an answer for yet
        // (`docs/backlog/26-46-*.md`'s own "no `0` that really means unknown" rule).
        composeTestRule.onNodeWithContentDescription("Диалоги").assertExists()
    }

    @Test
    fun noBadgeWhenTheTotalIsGenuinelyZero() {
        setContentWith(unreadConversationsTotal = 0)

        // A real, loaded `0` renders exactly like "not loaded yet" - no badge either way - but for a
        // different reason: there is genuinely nothing to announce.
        composeTestRule.onNodeWithContentDescription("Диалоги").assertExists()
    }

    @Test
    fun aPositiveTotalDrawsTheBadgeWithASpokenCount() {
        setContentWith(unreadConversationsTotal = 3)

        // The bare label alone no longer describes this node - proves the badge branch actually ran,
        // not just that it compiles.
        composeTestRule.onNodeWithContentDescription("Диалоги").assertDoesNotExist()
        // The full sentence: the tab's own name, then the count in words (`conversation_row_unread_few`,
        // reused verbatim from the row's own identical clause) - never a bare "3" a screen reader would
        // announce with no idea what it counts.
        composeTestRule.onNodeWithContentDescription("Диалоги. 3 непрочитанных сообщения").assertExists()
    }

    @Test
    fun theBadgeIsSingularlyWordedForOneUnreadMessage() {
        setContentWith(unreadConversationsTotal = 1)

        composeTestRule.onNodeWithContentDescription("Диалоги. 1 непрочитанное сообщение").assertExists()
    }

    private fun setContentWith(unreadConversationsTotal: Int?) {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = allFiveVisible,
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                unreadConversationsTotal = unreadConversationsTotal,
                conversationsTab = { Text("DIALOGI_MARKER") },
            )
        }
    }
}
