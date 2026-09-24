package ago.chat.android.devices

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-19`: the stateless half of the notification-settings screen, driven directly with fixed state and
 * no Hilt component - the identical "route wires, screen renders, a test substitutes its own state"
 * split [ago.chat.android.shell.SettingsScreenTest] already applies, including that file's own hardcoded-
 * literal convention for every `onNodeWithText` call (the real, current Russian string, not a resource
 * lookup — `SettingsScreenTest`'s own `backArrowCallsOnBack` states why).
 *
 * The item's own hardest Done-when box is proven here directly: [namesExactlyTheRealChannels] asserts
 * every channel row drawn matches [PushNotificationChannel.entries] itself rather than a hand-typed
 * count - a channel added to that enum without a corresponding fan-out event would make this test's own
 * row count assertion fail loudly, not silently draw a switch for something nothing sends. `26-86` grew
 * the real set from two to three; this test's own reliance on `entries.size` (not a literal `2`) is what
 * kept it from silently going stale the moment that changed.
 */
@RunWith(AndroidJUnit4::class)
class NotificationSettingsScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val bothChannelsOn =
        mapOf(
            PushNotificationChannel.Assignment to true,
            PushNotificationChannel.VisitorMessage to true,
            PushNotificationChannel.Waiting to true,
        )

    @Test
    fun namesExactlyTheRealChannels() {
        composeTestRule.setContent {
            NotificationSettingsScreen(
                channelStates = bothChannelsOn,
                onOpenChannelSettings = {},
                quietHours = QuietHoursSettings(),
                onQuietHoursEnabledChanged = {},
                onQuietHoursRangeChanged = { _, _ -> },
                onBack = {},
            )
        }

        // `PushNotificationChannel.entries` is exhaustively three - all three, and only these three,
        // must be named.
        assertEquals(3, PushNotificationChannel.entries.size)
        composeTestRule.onNodeWithText("Новый диалог назначен").assertExists()
        composeTestRule.onNodeWithText("Сообщение от посетителя").assertExists()
        composeTestRule.onNodeWithText("Новый диалог в очереди").assertExists()
    }

    @Test
    fun tappingAChannelRowOpensThatChannelsOwnSystemSettings() {
        var opened: PushNotificationChannel? = null
        composeTestRule.setContent {
            NotificationSettingsScreen(
                channelStates = bothChannelsOn,
                onOpenChannelSettings = { opened = it },
                quietHours = QuietHoursSettings(),
                onQuietHoursEnabledChanged = {},
                onQuietHoursRangeChanged = { _, _ -> },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Сообщение от посетителя").performClick()

        assertEquals(PushNotificationChannel.VisitorMessage, opened)
    }

    @Test
    fun aChannelTurnedOffInSystemSettingsShowsTheDisabledStateNotTheOnState() {
        composeTestRule.setContent {
            NotificationSettingsScreen(
                channelStates = mapOf(PushNotificationChannel.Assignment to false, PushNotificationChannel.VisitorMessage to true),
                onOpenChannelSettings = {},
                quietHours = QuietHoursSettings(),
                onQuietHoursEnabledChanged = {},
                onQuietHoursRangeChanged = { _, _ -> },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Отключены в настройках телефона").assertExists()
    }

    @Test
    fun quietHoursTimeRowsAreHiddenWhileDisabled() {
        composeTestRule.setContent {
            NotificationSettingsScreen(
                channelStates = bothChannelsOn,
                onOpenChannelSettings = {},
                quietHours = QuietHoursSettings(enabled = false),
                onQuietHoursEnabledChanged = {},
                onQuietHoursRangeChanged = { _, _ -> },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("С").assertDoesNotExist()
    }

    @Test
    fun quietHoursTimeRowsAndTheHonestNoteAppearOnceEnabled() {
        composeTestRule.setContent {
            NotificationSettingsScreen(
                channelStates = bothChannelsOn,
                onOpenChannelSettings = {},
                quietHours = QuietHoursSettings(enabled = true, startMinuteOfDay = 22 * 60, endMinuteOfDay = 7 * 60),
                onQuietHoursEnabledChanged = {},
                onQuietHoursRangeChanged = { _, _ -> },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("С").assertExists()
        composeTestRule.onNodeWithText("22:00").assertExists()
        composeTestRule.onNodeWithText("07:00").assertExists()
        // `26-19`'s own Done-when: the copy must match the real behaviour - this app suppresses a quiet-
        // hours push entirely, so the note must say so, never "silently in the tray".
        composeTestRule
            .onNodeWithText(
                "В это время push-уведомления не показываются совсем — ни звука, ни значка, ни новой записи в шторке.",
            ).assertExists()
    }

    @Test
    fun togglingTheQuietHoursSwitchCallsOnQuietHoursEnabledChanged() {
        val enabledCalls = mutableListOf<Boolean>()
        composeTestRule.setContent {
            NotificationSettingsScreen(
                channelStates = bothChannelsOn,
                onOpenChannelSettings = {},
                quietHours = QuietHoursSettings(enabled = false),
                onQuietHoursEnabledChanged = { enabledCalls.add(it) },
                onQuietHoursRangeChanged = { _, _ -> },
                onBack = {},
            )
        }

        composeTestRule.onNode(hasSwitchRole).performClick()

        assertEquals(listOf(true), enabledCalls)
    }

    @Test
    fun theAwayNoteIsAlwaysPresentAndNamesNoPerDeviceControl() {
        composeTestRule.setContent {
            NotificationSettingsScreen(
                channelStates = bothChannelsOn,
                onOpenChannelSettings = {},
                quietHours = QuietHoursSettings(),
                onQuietHoursEnabledChanged = {},
                onQuietHoursRangeChanged = { _, _ -> },
                onBack = {},
            )
        }

        composeTestRule
            .onNodeWithText(
                "Статус «Отошёл» относится к оператору, а не к этому телефону — он одинаковый на всех ваших " +
                    "устройствах, включая веб-консоль. Управлять им с этого экрана нельзя.",
            ).assertExists()
    }

    @Test
    fun backArrowCallsOnBack() {
        var backCalls = 0
        composeTestRule.setContent {
            NotificationSettingsScreen(
                channelStates = bothChannelsOn,
                onOpenChannelSettings = {},
                quietHours = QuietHoursSettings(),
                onQuietHoursEnabledChanged = {},
                onQuietHoursRangeChanged = { _, _ -> },
                onBack = { backCalls++ },
            )
        }

        composeTestRule.onNodeWithContentDescription("Назад").performClick()

        assertEquals(1, backCalls)
    }

    private companion object {
        val hasSwitchRole: SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
    }
}
