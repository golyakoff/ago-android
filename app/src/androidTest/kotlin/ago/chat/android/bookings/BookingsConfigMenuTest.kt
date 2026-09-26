package ago.chat.android.bookings

import ago.chat.android.ui.components.MENU_SCRIM_TEST_TAG
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-176`/`26-177`: the Записи `⋮` hub, driven through [BookingsScreen] directly — the stateless half
 * [BookingsRoute] wraps around a real `hiltViewModel()` (that composable's own doc comment) — rather than
 * through [BookingsRoute] itself, which this suite has no Hilt component for. This is the identical
 * `26-162` androidTest landmine every back-contract/shell test in this app already avoids the same way
 * (`BackContractBottomBarTest`'s own `bookingsTab` marker substitution): [BookingsScreen] takes plain data
 * parameters and calls no `hiltViewModel()` of its own, so it is safe to render under a bare
 * [ComponentActivity].
 *
 * `26-91`/`26-94`: the assertions below are plain Russian literals, safe for the identical reason
 * `BackContractBottomBarTest`'s own doc comment states — `LocaleForcingTestRunner` pins every instrumented
 * test's locale to `ru` before any of them run.
 */
@RunWith(AndroidJUnit4::class)
class BookingsConfigMenuTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun setBookingsScreen(showSetupSegment: Boolean = true) {
        composeTestRule.setContent {
            BookingsScreen(
                state = BookingsUiState.Loading,
                showConfirmedSegment = false,
                showClientsSegment = false,
                showReadinessEntry = false,
                showSetupSegment = showSetupSegment,
                showMastersSegment = false,
                showServicesSegment = false,
                showHoursSegment = false,
                selectedTab = BookingsTab.Pending,
                onSegmentSelected = {},
                activeConfigTab = null,
                onConfigSelected = {},
                onCloseConfig = {},
                onRetry = {},
                onReject = {},
                onCancel = {},
                confirmedState = null,
                onSelectDay = {},
                onRetryConfirmed = {},
                onRevealConfirmed = {},
                onOpenDialog = {},
                contactsState = null,
                onRetryContacts = {},
                onRevealContact = {},
                servicesState = null,
                onRetryServices = {},
                onEditService = {},
                onCancelServiceEdit = {},
                onServiceDraftChanged = {},
                onSubmitService = {},
                calendarSetupState = null,
                onRetryCalendarSetup = {},
                onAddCalendar = {},
                onEditCalendar = {},
                onCalendarFormChanged = {},
                onCancelCalendarEdit = {},
                onSubmitCalendar = {},
                mastersState = null,
                onRetryMasters = {},
                onAddMaster = {},
                onEditMaster = {},
                onToggleMasterActive = {},
                onDeleteMaster = {},
                onCancelMasterEdit = {},
                onMasterFormChanged = {},
                onSubmitMaster = {},
                mastersDrillDownWorkerId = null,
                mastersDrillDownKind = MastersDrillDownKind.Schedule,
                mastersDrillDownWorkerName = null,
                onOpenMastersSchedule = {},
                onOpenMastersSlots = {},
                onCloseMastersDrillDown = {},
                onSwitchMastersDrillDownToHours = {},
                onOpenRecutFromSchedule = {},
                workerScheduleState = null,
                onRetryWorkerSchedule = {},
                onWorkerScheduleFormChanged = {},
                onSubmitWorkerSchedule = {},
                workerSlotsState = null,
                onRetryWorkerSlots = {},
                onRevealWorkerSlot = {},
                workerRecutState = null,
                onWorkerRecutFromChanged = {},
                onPreviewWorkerRecut = {},
                onDecideWorkerRecut = { _, _ -> },
                onRequestConfirmWorkerRecut = {},
                onDismissConfirmWorkerRecut = {},
                onConfirmWorkerRecut = {},
                onRevealWorkerRecut = {},
                workingHoursState = null,
                onRetryWorkingHours = {},
                onSaveWorkingHours = { _, _, _, _ -> },
                onDeleteWorkingHours = {},
                onOpenMastersRecutFromHours = { _, _ -> },
                readinessState = null,
                onRetryReadiness = {},
            )
        }
    }

    /** `26-176`'s own Done-when: the entry that used to read «Настройка» reads «Календари» instead. Not
     * just "the new text exists somewhere" — «Настройка» must be gone from the opened menu too, since a
     * label that merely gained a second reading would still fail the rename. */
    @Test
    fun configMenu_rendersCalendariNotNastroika() {
        setBookingsScreen()

        composeTestRule.onNodeWithContentDescription("Конфигурация").performClick()

        composeTestRule.onNodeWithText("Календари").assertExists()
        composeTestRule.onNodeWithText("Настройка").assertDoesNotExist()
    }

    /** `26-177`'s own Done-when: opening the `⋮` hub shows [ScrimmedDropdownMenu]'s own scrim, and
     * selecting a row (which dismisses the menu) removes it again — the scrim never outlives the menu it
     * belongs to. */
    @Test
    fun configMenu_showsScrimWhileOpen_andRemovesItOnDismiss() {
        setBookingsScreen()

        composeTestRule.onNodeWithTag(MENU_SCRIM_TEST_TAG).assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Конфигурация").performClick()
        composeTestRule.onNodeWithTag(MENU_SCRIM_TEST_TAG).assertExists()

        composeTestRule.onNodeWithText("Календари").performClick()
        composeTestRule.onNodeWithTag(MENU_SCRIM_TEST_TAG).assertDoesNotExist()
    }
}
