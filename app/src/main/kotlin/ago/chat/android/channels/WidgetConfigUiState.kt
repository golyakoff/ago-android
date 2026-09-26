package ago.chat.android.channels

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.widgetconfig.WidgetConfig

/**
 * `26-193` (`docs/design/tenant-widget-android.md` §5.1): [WidgetConfigViewModel]'s whole state — the
 * identical loading/failed/loaded shape this app's other read-then-edit screens already establish
 * ([ago.chat.android.channels.ChannelConnectUiState]), with [Loaded.committed] carrying the *entire*
 * current config so every group editor seeds its own slice from the one source of truth (§3's full-DTO
 * round-trip design).
 */
internal sealed interface WidgetConfigUiState {
    data object Loading : WidgetConfigUiState

    /** The initial read itself failed — retry is the only action. */
    data class Failed(
        val reason: NetworkFailure,
    ) : WidgetConfigUiState

    /**
     * @param committed the last config the server confirmed — by the initial `GET`, or by the echo of the
     *   most recent successful save. Every group editor's local draft is seeded from this, never from its
     *   own previous draft, so a save made on another screen is always picked up the next time an editor
     *   opens.
     * @param saving `true` while a save is in flight — the open editor disables its Save button and
     *   fields for the duration.
     * @param saveError the most recent save's own refusal or failure, classified but not yet worded (the
     *   identical [ChannelActionError] split - a [NetworkFailure] is `:core:domain` data, never a
     *   sentence, so turning it into words stays the composable's job, done through
     *   [ago.chat.android.ui.components.networkFailureText] at the one call site every other screen
     *   already reads through). `null` once a new save starts or one succeeds. [committed] is unchanged
     *   while this is set — a refusal never discards the draft on screen.
     * @param savedTick bumped on every successful save — an editor's `LaunchedEffect` keyed on this value
     *   is what drives the "Сохранено" snackbar, so two consecutive identical saves each still trigger it.
     */
    data class Loaded(
        val committed: WidgetConfig,
        val saving: Boolean = false,
        val saveError: WidgetConfigSaveError? = null,
        val savedTick: Int = 0,
    ) : WidgetConfigUiState
}

/**
 * `26-193`: a widget-config save's own two honest failure outcomes — the identical split
 * [ago.chat.android.channels.ChannelActionError] already establishes for a connect/disconnect write,
 * restated here rather than reused since neither is a channel-token concern. [ServerRefusal] carries the
 * server's own validation sentence (a bad colour/notice URL, a blank auto-open greeting while enabled)
 * verbatim; [Unavailable] carries a [NetworkFailure] this app's own composable renders through
 * [ago.chat.android.ui.components.networkFailureText].
 */
internal sealed interface WidgetConfigSaveError {
    data class ServerRefusal(
        val detail: String,
    ) : WidgetConfigSaveError

    data class Unavailable(
        val reason: NetworkFailure,
    ) : WidgetConfigSaveError
}
