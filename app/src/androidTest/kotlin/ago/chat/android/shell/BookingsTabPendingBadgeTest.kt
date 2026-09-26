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
 * `26-179`: Записи's own footer badge, proven the identical way [DialoguesTabUnreadBadgeTest] already
 * proves Диалоги's — [AppShellScreen]'s own `pendingBookingsTotal` parameter is a plain `Int?` with no
 * view model, no `BookingsApi` and no poll loop in play at all. What
 * `AppShellViewModel.pendingBookingsTotal` actually reads that `Int?` from is
 * `PendingBookingsPollerTest`'s own job (plain JVM, `data/bookings/`), and what `AppShellViewModel`
 * itself does with it is `AppShellViewModelTest`'s — this class is the one layer neither of those two
 * reaches: does the bottom bar actually *draw* the right thing for each of the three values that `Int?`
 * can be, worded for a booking rather than a message.
 */
@RunWith(AndroidJUnit4::class)
class BookingsTabPendingBadgeTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val allFiveVisible = OperatorPermissions.Known(setOf(Permission.CALENDAR_CONFIGURE))

    @Test
    fun noBadgeBeforeTheFirstAnswerArrives() {
        setContentWith(pendingBookingsTotal = null)

        composeTestRule.onNodeWithContentDescription("Записи", useUnmergedTree = true).assertExists()
    }

    @Test
    fun noBadgeWhenTheQueueIsGenuinelyEmpty() {
        setContentWith(pendingBookingsTotal = 0)

        composeTestRule.onNodeWithContentDescription("Записи", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aPositiveTotalDrawsTheBadgeWithASpokenCount() {
        setContentWith(pendingBookingsTotal = 5)

        composeTestRule.onNodeWithContentDescription("Записи", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription("Записи. 5 записей ожидают подтверждения", useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun theBadgeIsSingularlyWordedForOnePendingBooking() {
        setContentWith(pendingBookingsTotal = 1)

        composeTestRule
            .onNodeWithContentDescription("Записи. 1 запись ожидает подтверждения", useUnmergedTree = true)
            .assertExists()
    }

    private fun setContentWith(pendingBookingsTotal: Int?) {
        composeTestRule.setContent {
            AppShellScreen(
                permissions = allFiveVisible,
                loadError = null,
                activeSiteId = null,
                hubConnectionState = OperatorHubConnectionState.Disconnected,
                onRetry = {},
                onSignOut = {},
                pendingBookingsTotal = pendingBookingsTotal,
                conversationsTab = { Text("DIALOGI_MARKER") },
            )
        }
    }
}
