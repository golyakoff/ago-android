package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.ConfirmedBookingsStripDay
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-254`: the «Сегодня» quick-jump moved out of the day-strip month/year header and *into* the
 * date-picker dialog that header opens. This suite pins both halves of that move on the real composable
 * ([ConfirmedBookingsBody], rendered directly the same Hilt-free way [BookingsConfigMenuTest]'s own doc
 * comment justifies for a plain data-parameter composable under a bare [ComponentActivity]):
 *
 * 1. the strip header no longer exposes «Сегодня» at all (the `assertDoesNotExist` below is the fails-before
 *    guard — it would have failed while `26-233`'s header `TextButton` still stood);
 * 2. opening the picker surfaces «Сегодня», and tapping it fires the unchanged today-jump callback and
 *    closes the picker.
 *
 * `26-91`/`26-94`: the Russian literals are safe for the identical reason [BookingsConfigMenuTest]'s own doc
 * comment states — `LocaleForcingTestRunner` pins every instrumented test's locale to `ru`.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmedStripTodayJumpTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val todayLabel = "Сегодня"

    private fun loadedState() =
        ConfirmedBookingsUiState.Loaded(
            // No day group for the selected date -> the stated empty body renders, keeping this test on the
            // strip header + picker rather than the row list, which this move does not touch.
            days = emptyList(),
            strip =
                (1..7).map { day ->
                    ConfirmedBookingsStripDay(date = "2026-10-%02d".format(day), weekday = 0, hasBookings = false)
                },
            selectedDate = "2026-10-01",
        )

    @Test
    fun today_isAbsentFromHeader_thenPresentInPicker_andJumpsOnTap() {
        var jumpToTodayCalls = 0
        composeTestRule.setContent {
            ConfirmedBookingsBody(
                state = loadedState(),
                onSelectDay = {},
                onJumpToDate = {},
                onRetry = {},
                onReveal = {},
                onOpenDialog = {},
                onPullToLoadWeek = {},
                onJumpToToday = { jumpToTodayCalls++ },
            )
        }

        // 1. The header strip no longer offers «Сегодня» (fails-before: `26-233`'s header button did).
        composeTestRule.onNodeWithText(todayLabel).assertDoesNotExist()

        // 2. Tapping the sticky month/year header opens the date picker, which now carries «Сегодня».
        composeTestRule.onNodeWithTag(CONFIRMED_STRIP_HEADER_TEST_TAG).performClick()
        composeTestRule.onNodeWithText(todayLabel).assertExists()

        // Tapping it fires the unchanged today-jump and dismisses the picker.
        composeTestRule.onNodeWithText(todayLabel).performClick()
        assertEquals(1, jumpToTodayCalls)
        composeTestRule.onNodeWithText(todayLabel).assertDoesNotExist()
    }
}
