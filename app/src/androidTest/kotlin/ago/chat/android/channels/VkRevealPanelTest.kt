package ago.chat.android.channels

import ago.chat.android.core.domain.channels.VkReveal
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * `26-190`/`C3`: the VK-only shown-once callbackUrl/webhookSecret reveal panel
 * (`docs/design/tenant-channels-android.md` §2.3) — driven directly through the stateless
 * [ChannelConnectScreen] with a fake [ChannelConnectUiState.Connected], the identical "no view model, no
 * Hilt component" shape [WidgetInstallGatingTest]'s own `theLoadedScreenRendersTheEmbedSnippetAndTheAllowedOrigins`
 * test already establishes for [InstallWidgetScreen].
 *
 * `26-91`/`26-94`: assertions are plain Russian literals, safe because `LocaleForcingTestRunner` pins
 * every instrumented test's own locale to `ru`.
 */
@RunWith(AndroidJUnit4::class)
class VkRevealPanelTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aFreshVkConnectShowsTheRevealPanelWithBothValues() {
        composeTestRule.setContent {
            ChannelConnectScreen(
                state = connectedState(reveal = REVEAL),
                config = VK_CONFIG,
                onConnect = {},
                onDisconnect = {},
                onRetry = {},
                onDismissDisconnectError = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Настройка VK Callback API").assertExists()
        composeTestRule.onNodeWithText(REVEAL.callbackUrl).assertExists()
        composeTestRule.onNodeWithText(REVEAL.webhookSecret).assertExists()
        // Neither confirmation shows until its own copy button is tapped.
        composeTestRule.onNodeWithText("Callback URL скопирован").assertDoesNotExist()
    }

    @Test
    fun tappingCopyShowsTheCopiedConfirmationForThatFieldAlone() {
        composeTestRule.setContent {
            ChannelConnectScreen(
                state = connectedState(reveal = REVEAL),
                config = VK_CONFIG,
                onConnect = {},
                onDisconnect = {},
                onRetry = {},
                onDismissDisconnectError = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Скопировать Callback URL").performClick()

        composeTestRule.onNodeWithText("Callback URL скопирован").assertExists()
        // The webhook secret's own confirmation is untouched by copying the callback URL.
        composeTestRule.onNodeWithText("Секрет вебхука скопирован").assertDoesNotExist()
    }

    @Test
    fun aReloadWithNoRevealShowsTheShownOnceHintInsteadOfAnyPanel() {
        composeTestRule.setContent {
            ChannelConnectScreen(
                state = connectedState(reveal = null),
                config = VK_CONFIG,
                onConnect = {},
                onDisconnect = {},
                onRetry = {},
                onDismissDisconnectError = {},
                onBack = {},
            )
        }

        composeTestRule
            .onNodeWithText(
                "Callback URL и секрет вебхука показываются только один раз, сразу после подключения. " +
                    "Чтобы получить их снова, отключите канал и подключите сообщество заново с новым токеном.",
            ).assertExists()
        composeTestRule.onNodeWithText("Настройка VK Callback API").assertDoesNotExist()
    }

    @Test
    fun aNonVkConfigNeverDrawsTheRevealRegionEvenWithoutOneToShow() {
        composeTestRule.setContent {
            ChannelConnectScreen(
                state = connectedState(reveal = null),
                config = VK_CONFIG.copy(showVkReveal = false),
                onConnect = {},
                onDisconnect = {},
                onRetry = {},
                onDismissDisconnectError = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Настройка VK Callback API").assertDoesNotExist()
        composeTestRule
            .onNodeWithText(
                "Callback URL и секрет вебхука показываются только один раз, сразу после подключения. " +
                    "Чтобы получить их снова, отключите канал и подключите сообщество заново с новым токеном.",
            ).assertDoesNotExist()
    }

    private companion object {
        val REVEAL = VkReveal(callbackUrl = "https://api.example/vk/callback", webhookSecret = "s3cr3t-webhook")

        val VK_CONFIG =
            ChannelConnectConfig(
                titleRes = ago.chat.android.R.string.channels_vk_title,
                notConnectedBodyRes = ago.chat.android.R.string.channels_vk_not_connected_body,
                tokenLabelRes = ago.chat.android.R.string.channels_vk_token_label,
                tokenHintRes = ago.chat.android.R.string.channels_vk_token_hint,
                disconnectDialogBodyRes = ago.chat.android.R.string.channels_vk_disconnect_dialog_body,
                showVkReveal = true,
            )

        fun connectedState(reveal: VkReveal?) =
            ChannelConnectUiState.Connected(
                verified = true,
                unreachable = false,
                refusalReason = null,
                createdAt = Instant.parse("2026-09-01T00:00:00Z"),
                checkedAt = Instant.parse("2026-09-26T12:00:00Z"),
                channelCredentialId = "cc-vk",
                reveal = reveal,
                disconnecting = false,
                disconnectError = null,
            )
    }
}
