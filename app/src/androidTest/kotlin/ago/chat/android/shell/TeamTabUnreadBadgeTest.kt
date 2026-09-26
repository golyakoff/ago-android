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
 * `26-180`: Команда's own footer badge, proven the identical way [DialoguesTabUnreadBadgeTest] already
 * proves Диалоги's — [AppShellScreen]'s own `teamUnreadTotal` parameter is a plain `Int` with no view
 * model, no hub connection and no [TeamUnreadCount] in play at all. What
 * `AppShellViewModel.teamUnreadTotal` actually reads that `Int` from is `AppShellViewModelTest`'s own job
 * (plain JVM) — this class is the layer that proves the bottom bar actually *draws* the right thing for a
 * `0` and for a genuinely positive count, reusing `conversation_row_unread_*` wording
 * ([AppShellScreen]'s own doc comment on why that reuse, not a near-duplicate string, is deliberate).
 */
@RunWith(AndroidJUnit4::class)
class TeamTabUnreadBadgeTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val allFiveVisible = OperatorPermissions.Known(setOf(Permission.CALENDAR_CONFIGURE))

    @Test
    fun noBadgeWhenNothingHasArrivedYet() {
        setContentWith(teamUnreadTotal = 0)

        composeTestRule.onNodeWithContentDescription("Команда", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aPositiveTotalDrawsTheBadgeWithASpokenCount() {
        setContentWith(teamUnreadTotal = 3)

        composeTestRule.onNodeWithContentDescription("Команда", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription("Команда. 3 непрочитанных сообщения", useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun theBadgeIsSingularlyWordedForOneUnreadMessage() {
        setContentWith(teamUnreadTotal = 1)

        composeTestRule
            .onNodeWithContentDescription("Команда. 1 непрочитанное сообщение", useUnmergedTree = true)
            .assertExists()
    }

    private fun setContentWith(teamUnreadTotal: Int) {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = allFiveVisible,
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                teamUnreadTotal = teamUnreadTotal,
                conversationsTab = { Text("DIALOGI_MARKER") },
            )
        }
    }
}
