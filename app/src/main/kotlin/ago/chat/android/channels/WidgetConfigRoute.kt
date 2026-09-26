package ago.chat.android.channels

import ago.chat.android.R
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.ui.components.SectionLabel
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * `26-193` (`docs/design/tenant-widget-android.md` §3.1/§5.3): Каналы → «Виджет на сайте» — the hub for
 * the widget's on-site config, and the one place [WidgetConfigViewModel] is obtained
 * ([hiltViewModel]), so every group editor this feature carries shares the one committed config rather
 * than each holding (and `GET`ting) its own.
 *
 * [openGroup] is a second navigation level *inside* this feature — the identical shape
 * [ago.chat.android.shell.MoreScreen]'s own `openRowId` is for Ещё. This nesting does not violate
 * `navigation.md`'s "Ещё never nests further" clause because it is the widget feature's own internal
 * nav, one level inside the single «Виджет на сайте» row Ещё itself sees — the same reasoning a
 * conversation thread's own internal nav, reached from Диалоги, already rests on (§3.1's own statement of
 * why the hub is legal here).
 *
 * **W1 wires only Внешний вид.** [WidgetConfigGroup] carries a single entry; Поведение и приветствие/
 * Согласие и запись are `W2`/`W3`'s own rows, added to this enum and [WidgetConfigHubContent]'s row list
 * when each lands (`docs/design/tenant-widget-android.md` §8.3) — not drawn disabled here, since a hub row
 * promising a screen that does not exist yet would be worse than a hub that is honestly one row short for
 * now.
 */
@Composable
internal fun WidgetConfigRoute(
    onBack: () -> Unit,
    viewModel: WidgetConfigViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var openGroup by rememberSaveable { mutableStateOf<WidgetConfigGroup?>(null) }
    BackHandler(enabled = openGroup != null) { openGroup = null }

    val loaded = state as? WidgetConfigUiState.Loaded
    if (openGroup == WidgetConfigGroup.Appearance && loaded != null) {
        WidgetAppearanceEditor(
            committed = loaded.committed,
            saving = loaded.saving,
            saveError = loaded.saveError,
            savedTick = loaded.savedTick,
            onSave = viewModel::save,
            onBack = { openGroup = null },
        )
    } else {
        WidgetConfigHubScreen(
            state = state,
            onRetry = viewModel::refresh,
            onOpenGroup = { openGroup = it },
            onBack = onBack,
        )
    }
}

/** The group screens `docs/design/tenant-widget-android.md` §2 fixes — `W1` wires [Appearance] alone;
 * `Behaviour`/`Consent` join this enum when `W2`/`W3` land. */
internal enum class WidgetConfigGroup {
    Appearance,
}

/**
 * The stateless hub — a short intro line (adr/0029's own "changes apply on the visitor's next page load"
 * note, stated once here rather than repeated on each editor, since every group save passes back through
 * this one screen) then the group rows. Route/Screen split, back arrow, no
 * [ago.chat.android.ui.components.AccountAvatarAction] (a drill-in), the identical shape
 * [ago.chat.android.channels.InstallWidgetScreen] already establishes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigHubScreen(
    state: WidgetConfigUiState,
    onRetry: () -> Unit,
    onOpenGroup: (WidgetConfigGroup) -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.widget_config_hub_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    WidgetConfigUiState.Loading -> LoadingBody()
                    is WidgetConfigUiState.Failed -> WidgetConfigFailedBody(reason = state.reason, onRetry = onRetry)
                    is WidgetConfigUiState.Loaded -> WidgetConfigHubContent(onOpenGroup = onOpenGroup)
                }
            }
        }
    }
}

@Composable
private fun WidgetConfigHubContent(onOpenGroup: (WidgetConfigGroup) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.widget_config_hub_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        SectionLabel(text = stringResource(R.string.widget_config_hub_section))
        WidgetConfigGroupRow(
            label = stringResource(R.string.widget_config_group_appearance),
            onClick = { onOpenGroup(WidgetConfigGroup.Appearance) },
        )
    }
}

@Composable
private fun WidgetConfigGroupRow(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
    )
    HorizontalDivider()
}

/** The config read itself failed — retry is the only action, the identical "title, [networkFailureText],
 * retry" shape [ago.chat.android.channels.ChannelConnectScreen]'s own private status-failed body already
 * establishes for a [NetworkFailure], restated here since neither file imports composables from the
 * other. */
@Composable
private fun WidgetConfigFailedBody(
    reason: NetworkFailure,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = networkFailureText(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

/** [WidgetConfigSaveError] rendered as a sentence — the one call site every group editor (Внешний вид now,
 * Поведение/Согласие in `W2`/`W3`) reads through, the identical single-call-site discipline
 * [ago.chat.android.ui.components.networkFailureText] already establishes for a bare [NetworkFailure].
 * `internal`, not `private`: `W2`/`W3`'s own editors reuse this rather than a second copy of the same
 * `when`. */
@Composable
internal fun widgetConfigSaveErrorText(error: WidgetConfigSaveError): String =
    when (error) {
        is WidgetConfigSaveError.ServerRefusal -> error.detail
        is WidgetConfigSaveError.Unavailable -> networkFailureText(error.reason)
    }

/** A group editor's own inline banner for a failed save — the identical placement and danger-text styling
 * [ago.chat.android.bookings.BookingsScreen]'s own `ActionErrorBanner` establishes, restated here for
 * [WidgetConfigSaveError] rather than that file's own `BookingActionErrorUi` (a different port's
 * classification). `internal`, not `private`, for the same reuse-by-`W2`/`W3` reason
 * [widgetConfigSaveErrorText] states above. */
@Composable
internal fun WidgetConfigErrorBanner(
    error: WidgetConfigSaveError,
    modifier: Modifier = Modifier,
) {
    Text(
        text = widgetConfigSaveErrorText(error),
        style = MaterialTheme.typography.bodySmall,
        color = agoStatusColors().dangerText,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
