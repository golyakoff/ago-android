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
 * `26-189`/`C2`: Каналы → MAX — the second, and, as promised, thinnest possible, subclass of
 * [ChannelConnectViewModel]: it contributes nothing but [ChannelKind.Max], the identical "fix the kind,
 * inherit everything else" shape [TelegramChannelViewModel] already establishes and `C3`'s
 * `VkChannelViewModel` repeats again.
 */
@HiltViewModel
internal class MaxChannelViewModel
    @Inject
    constructor(
        api: ChannelConnectionApi,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : ChannelConnectViewModel(api, ioDispatcher, ChannelKind.Max)

/**
 * `26-189`/`C2`: Каналы → MAX — connect a bot token, watch its live verified/unreachable/refused status,
 * disconnect. The console's own description of `MaxChannelPage` — "`TelegramChannelPage` with the labels
 * swapped" — applies identically here: this route differs from [TelegramChannelRoute] only in which
 * [MaxChannelViewModel] and [MaxConfig] it wires into the shared [ChannelConnectScreen].
 */
@Composable
internal fun MaxChannelRoute(
    onBack: () -> Unit,
    viewModel: MaxChannelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChannelConnectScreen(
        state = state,
        config = MaxConfig,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onRetry = viewModel::refresh,
        onDismissDisconnectError = viewModel::dismissDisconnectError,
        onBack = onBack,
    )
}

/** MAX's own thin binding of the shared scaffold - `showVkReveal = false`, since only VK's connect
 * response ever carries a reveal ([ChannelConnectConfig]'s own doc comment). MAX connecting without a
 * deployment-wide public webhook base URL still succeeds server-side (a fallback inbound mechanism the
 * design doc names as "not a code path" - `docs/design/tenant-channels-android.md` §2.2), so nothing here
 * renders differently for that case; the scaffold's normal connect flow already covers it. */
private val MaxConfig =
    ChannelConnectConfig(
        titleRes = R.string.channels_max_title,
        notConnectedBodyRes = R.string.channels_max_not_connected_body,
        tokenLabelRes = R.string.channels_max_token_label,
        tokenHintRes = R.string.channels_max_token_hint,
        disconnectDialogBodyRes = R.string.channels_max_disconnect_dialog_body,
        showVkReveal = false,
    )
