package ago.chat.android.channels

import ago.chat.android.R
import ago.chat.android.core.domain.channels.ChannelConnectionApi
import ago.chat.android.core.domain.channels.ChannelKind
import ago.chat.android.di.IoDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/**
 * `26-190`/`C3`: Каналы → VK — the third subclass of [ChannelConnectViewModel], fixing
 * [ChannelKind.Vk]. It contributes nothing of its own beyond that: the shown-once
 * `callbackUrl`/`webhookSecret` reveal is already the shared base's own `sessionReveal` concern
 * ([ChannelConnectViewModel]'s own doc comment explains why that lives there rather than on this
 * subclass), and [KtorChannelConnectionApi][ago.chat.android.core.network.channels.KtorChannelConnectionApi]
 * already parses [ago.chat.android.core.domain.channels.VkReveal] for [ChannelKind.Vk] alone. What is new
 * in this slice is [VkConfig.showVkReveal] and the reveal panel it switches on in [ChannelConnectScreen].
 */
@HiltViewModel
internal class VkChannelViewModel
    @Inject
    constructor(
        api: ChannelConnectionApi,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : ChannelConnectViewModel(api, ioDispatcher, ChannelKind.Vk)

/**
 * `26-190`/`C3`: Каналы → VK — connect a community token, watch its live verified/unreachable/refused
 * status, disconnect, and — the one thing VK alone shows — the shown-once callback URL/webhook secret
 * panel a connect response carries. Identical wiring to [TelegramChannelRoute]/[MaxChannelRoute], differing
 * only in [VkChannelViewModel] and [VkConfig].
 */
@Composable
internal fun VkChannelRoute(
    onBack: () -> Unit,
    viewModel: VkChannelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChannelConnectScreen(
        state = state,
        config = VkConfig,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onRetry = viewModel::refresh,
        onDismissDisconnectError = viewModel::dismissDisconnectError,
        onBack = onBack,
    )
}

/** VK's own thin binding of the shared scaffold - `showVkReveal = true` is the one difference from
 * Telegram's/MAX's config, and the only thing that turns the reveal panel on in [ChannelConnectScreen]
 * ([ChannelConnectConfig]'s own doc comment). */
private val VkConfig =
    ChannelConnectConfig(
        titleRes = R.string.channels_vk_title,
        notConnectedBodyRes = R.string.channels_vk_not_connected_body,
        tokenLabelRes = R.string.channels_vk_token_label,
        tokenHintRes = R.string.channels_vk_token_hint,
        disconnectDialogBodyRes = R.string.channels_vk_disconnect_dialog_body,
        showVkReveal = true,
    )
