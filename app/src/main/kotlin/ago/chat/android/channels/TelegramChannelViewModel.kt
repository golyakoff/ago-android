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
 * `26-188`: Каналы → Telegram — the first, and thinnest possible, subclass of
 * [ChannelConnectViewModel]: it contributes nothing but [ChannelKind.Telegram], the same "fix the kind,
 * inherit everything else" shape `C2`'s `MaxChannelViewModel` and `C3`'s `VkChannelViewModel` will each
 * repeat.
 */
@HiltViewModel
internal class TelegramChannelViewModel
    @Inject
    constructor(
        api: ChannelConnectionApi,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : ChannelConnectViewModel(api, ioDispatcher, ChannelKind.Telegram)

/**
 * `26-188`: Каналы → Telegram — connect a bot token, watch its live verified/unreachable/refused status,
 * disconnect. Obtains its own [TelegramChannelViewModel] via [hiltViewModel], the identical wiring
 * [ago.chat.android.channels.InstallWidgetRoute] already establishes for an Ещё drill-in;
 * [ago.chat.android.shell.MoreScreen] composes this only while the row is open and only for an operator
 * holding `site:configure`, so the view model — and its first network call — come into existence only
 * when one actually opens the screen.
 */
@Composable
internal fun TelegramChannelRoute(
    onBack: () -> Unit,
    viewModel: TelegramChannelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChannelConnectScreen(
        state = state,
        config = TelegramConfig,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onRetry = viewModel::refresh,
        onDismissDisconnectError = viewModel::dismissDisconnectError,
        onBack = onBack,
    )
}

/** Telegram's own thin binding of the shared scaffold - `showVkReveal = false`, since only VK's connect
 * response ever carries a reveal ([ChannelConnectConfig]'s own doc comment). */
private val TelegramConfig =
    ChannelConnectConfig(
        titleRes = R.string.channels_telegram_title,
        notConnectedBodyRes = R.string.channels_telegram_not_connected_body,
        tokenLabelRes = R.string.channels_telegram_token_label,
        tokenHintRes = R.string.channels_telegram_token_hint,
        disconnectDialogBodyRes = R.string.channels_telegram_disconnect_dialog_body,
        showVkReveal = false,
    )
